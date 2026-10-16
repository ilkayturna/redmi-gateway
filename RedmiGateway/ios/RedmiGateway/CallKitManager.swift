import AVFoundation
import CallKit
import Foundation
import UIKit

/// Presents the Redmi's cellular calls with CallKit and moves their audio to the iPhone.
///
/// Each Redmi call id maps to one CallKit UUID. Incoming calls may be announced by Bluetooth
/// (locked iPhone) or the network link, whichever comes first; later events for the same id
/// update the same CallKit call. Audio is requested only for calls this iPhone presents as
/// answered here, and the Redmi confirms when the conversation actually moved.
@MainActor
final class CallKitManager: NSObject, CXProviderDelegate {
    static let shared = CallKitManager()

    private static let emergencyNumbers: Set<String> = ["112", "911", "999", "110", "155", "156", "158", "177"]
    private static let transientFallbacks: Set<String> = ["iphone_audio_lost", "iphone_not_receiving", "uplink_stalled", "downlink_stalled"]

    private let provider: CXProvider
    private let callController = CXCallController()

    private var uuidByCallId: [String: UUID] = [:]
    private var callIdByUuid: [UUID: String] = [:]
    private var states: [UUID: String] = [:]
    private var numbers: [UUID: String] = [:]
    private var incoming: Set<UUID> = []
    private var outgoing: Set<UUID> = []
    /// Calls already running on the Redmi that this iPhone took over ("iPhone'a al").
    private var pickups: Set<UUID> = []
    private var answeredHere: Set<UUID> = []
    private var connectedOutgoing: Set<UUID> = []
    private var autoRetries: [UUID: Int] = [:]
    private var pendingOutgoing: UUID?
    private var endedCallIds: [String: Date] = [:]
    /// Answered from the app without a CallKit call; its audio moves here once it is active.
    private var pickupAfterAnswer: String?

    private var audioActivated = false
    private var audioRequested = false
    private var audioCallUuid: UUID?
    private var endpoint: (session: UInt32, port: UInt16, key: Data)?
    private var runningEndpoint: (host: String, session: UInt32, port: UInt16)?
    private var linkLostTask: Task<Void, Never>?
    /// Background time held while a transfer starts off screen; see beginTransferTask.
    private var transferTask: UIBackgroundTaskIdentifier = .invalid
    /// Where the audio path for the current call started: the answer tap, or the pickup start.
    /// Every "Audio path:" line is stamped from here, so the iPhone's own numbers can be read
    /// against the Redmi's firstPcm (measured from the moment audio_start arms the relay).
    private var audioPathStartedAt: Date?

    private func audioPathLog(_ message: String) {
        guard let start = audioPathStartedAt else {
            GatewayConnection.shared.log("Audio path: \(message)")
            return
        }
        let ms = Int((Date().timeIntervalSince(start) * 1000).rounded())
        GatewayConnection.shared.log("Audio path: \(message) (+\(ms) ms)")
    }

    override private init() {
        let config = CXProviderConfiguration(localizedName: "Telefon Geçidi")
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 5
        // A second group lets iOS show a waiting call during the current one.
        config.maximumCallGroups = 2
        config.supportedHandleTypes = [.phoneNumber, .generic]
        config.includesCallsInRecents = true
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: DispatchQueue.main)
        AudioEngine.shared.prepareSession()
        AudioEngine.shared.onLog = { message in
            Task { @MainActor in GatewayConnection.shared.log(message) }
        }
        AudioEngine.shared.onNeedsRestart = {
            Task { @MainActor in CallKitManager.shared.recoverCallAudio() }
        }
        GatewayConnection.shared.onCallEvent = { event in
            CallKitManager.shared.handleGatewayCallEvent(event)
        }
    }

    // MARK: - Redmi call events

    func handleGatewayCallEvent(_ event: CallEventMessage) {
        let state = event.state.lowercased()
        guard state != "idle", state != "disconnecting", state != "selecting_account" else { return }
        let rawId = (event.callId?.isEmpty == false) ? event.callId! : "gateway_call"
        if state == "ringing", let ended = endedCallIds[rawId], Date().timeIntervalSince(ended) < 300 { return }
        let uuid = uuidFor(rawId: rawId, state: state)
        let previous = states[uuid]
        states[uuid] = state
        if let number = event.number, !number.isEmpty { numbers[uuid] = number }

        switch state {
        case "ringing":
            if !incoming.contains(uuid) && !outgoing.contains(uuid) {
                incoming.insert(uuid)
                reportIncoming(uuid: uuid, rawId: rawId, number: numbers[uuid])
            } else if let number = event.number, !number.isEmpty, incoming.contains(uuid) {
                provider.reportCall(with: uuid, updated: callUpdate(number: number))
            }
            // Answered here, and the Redmi took that announcement back: the radio never confirmed,
            // so nobody is on the call and it is really ringing on the Redmi again. Close this
            // answered CallKit call - it was never connected - and show the call as incoming once
            // more, so the person can answer it from this iPhone instead of being left with a
            // silent "connected" call and no way to pick the real one up.
            if incoming.contains(uuid), answeredHere.contains(uuid) {
                if audioRequested { stopAudio() }
                let number = numbers[uuid]
                GatewayConnection.shared.log("Answer not confirmed by the Redmi; showing the call as incoming again")
                GatewayConnection.shared.lastCallError = "Arama Redmi'de yanıtlanamadı."
                // Unanswered, not failed: nobody ever connected, and Recents should not show a
                // failed call for one that is still ringing and about to be presented again.
                end(uuid, reason: .unanswered)
                let again = uuidFor(rawId: rawId, state: "ringing")
                states[again] = "ringing"
                if let number, !number.isEmpty { numbers[again] = number }
                incoming.insert(again)
                reportIncoming(uuid: again, rawId: rawId, number: number)
            }
        case "active":
            if outgoing.contains(uuid) {
                if connectedOutgoing.insert(uuid).inserted { provider.reportOutgoingCall(with: uuid, connectedAt: Date()) }
                maybeStartAudio(for: uuid)
            } else if incoming.contains(uuid) {
                if answeredHere.contains(uuid) {
                    maybeStartAudio(for: uuid)
                } else {
                    // Answered on the Redmi: the iPhone stops ringing, the call goes on there.
                    end(uuid, reason: .answeredElsewhere)
                }
            } else if pickupAfterAnswer == rawId {
                pickupAfterAnswer = nil
                cleanup(uuid)
                takeCallOnIPhone()
            } else {
                // A call this iPhone never presented; the Redmi also announces the answer a second
                // time when its radio confirms it. Without this the second event would leave a
                // phantom call behind.
                cleanup(uuid)
            }
        case "dialing", "connecting":
            if outgoing.contains(uuid), !pickups.contains(uuid) {
                if previous != "dialing" && previous != "connecting" {
                    provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                }
                // Ringback and operator announcements already play on the iPhone.
                maybeStartAudio(for: uuid)
            }
        case "disconnected":
            if incoming.contains(uuid) || outgoing.contains(uuid) {
                let missed = incoming.contains(uuid) && previous == "ringing" && !answeredHere.contains(uuid)
                end(uuid, reason: missed ? .unanswered : .remoteEnded)
            } else {
                cleanup(uuid)
            }
            endedCallIds[rawId] = Date()
            endedCallIds = endedCallIds.filter { Date().timeIntervalSince($0.value) < 600 }
        default:
            break
        }

        // A live call is on the Redmi. Build the call-audio graph now, while nobody is waiting for
        // it: the measured pickup waited 1.4 s for the first frames, and part of that was the
        // voice-processing setup running after the button was pressed. Warming here (and again,
        // harmlessly, at the button) means the press only waits for CallKit to activate the session
        // and for the engine to start. Nothing captures audio until the engine starts.
        if ["dialing", "connecting", "active"].contains(state) {
            audioPathLog("live call; warming the audio graph")
            AudioEngine.shared.warmUp()
        }
    }

    private func uuidFor(rawId: String, state: String) -> UUID {
        if let existing = uuidByCallId[rawId] { return existing }
        if let pending = pendingOutgoing, ["dialing", "connecting", "active"].contains(state) {
            // The first event of the call this iPhone just dialed.
            pendingOutgoing = nil
            if let temporary = callIdByUuid[pending] { uuidByCallId.removeValue(forKey: temporary) }
            uuidByCallId[rawId] = pending
            callIdByUuid[pending] = rawId
            return pending
        }
        let uuid = UUID()
        uuidByCallId[rawId] = uuid
        callIdByUuid[uuid] = rawId
        return uuid
    }

    private func cleanup(_ uuid: UUID) {
        if let rawId = callIdByUuid.removeValue(forKey: uuid), uuidByCallId[rawId] == uuid {
            uuidByCallId.removeValue(forKey: rawId)
        }
        states.removeValue(forKey: uuid)
        numbers.removeValue(forKey: uuid)
        incoming.remove(uuid)
        outgoing.remove(uuid)
        pickups.remove(uuid)
        answeredHere.remove(uuid)
        connectedOutgoing.remove(uuid)
        autoRetries.removeValue(forKey: uuid)
        if pendingOutgoing == uuid { pendingOutgoing = nil }
        if audioCallUuid == uuid { stopAudio() }
    }

    private func end(_ uuid: UUID, reason: CXCallEndedReason) {
        // Remember the raw id: a stale "ringing" for the same call must not raise a second CallKit
        // incoming call. The Redmi announces an answer again from "ringing" when its radio does not
        // confirm it, and without this the iPhone rang a second time for a call just answered here.
        if let rawId = callIdByUuid[uuid], !rawId.isEmpty, rawId != "gateway_call" {
            endedCallIds[rawId] = Date()
            endedCallIds = endedCallIds.filter { Date().timeIntervalSince($0.value) < 600 }
        }
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
        cleanup(uuid)
    }

    /// The CallKit call that currently represents the Redmi's call, if any.
    private var currentCallUuid: UUID? {
        guard let rawId = GatewayConnection.shared.currentCall?.rawCallId,
              let uuid = uuidByCallId[rawId],
              incoming.contains(uuid) || outgoing.contains(uuid) else { return nil }
        return uuid
    }

    var presentsCurrentCall: Bool { currentCallUuid != nil }
    var hasCalls: Bool { !states.isEmpty }

    // MARK: - Audio

    private func isAudioEligible(_ uuid: UUID) -> Bool {
        switch states[uuid] {
        case "active": return incoming.contains(uuid) ? answeredHere.contains(uuid) : outgoing.contains(uuid)
        case "dialing", "connecting": return outgoing.contains(uuid) && !pickups.contains(uuid)
        case "ringing":
            // Answered here a moment ago; the Redmi's "active" event is still on its way. The audio
            // engine may start as soon as the Redmi sends its endpoint, so the sound is already
            // flowing when the call goes active ("the sound jumps to the iPhone at once").
            return incoming.contains(uuid) && answeredHere.contains(uuid)
        default: return false
        }
    }

    /// The one gate for the call audio: it decides the early engine start *and* the attach.
    ///
    /// v1 started the engine on `audioActivated` but attached it through `isAudioEligible`, and in a
    /// pickup those two never met: an engine that could not be attached (M4). Both halves read this
    /// function now, so they cannot drift apart.
    private func engineGateOpen(for uuid: UUID?) -> Bool {
        guard let uuid, audioActivated else { return false }
        return isAudioEligible(uuid)
    }

    private func maybeStartAudio(for uuid: UUID) {
        guard isAudioEligible(uuid), GatewayConnection.shared.isAuthenticated else { return }
        audioCallUuid = uuid
        // Build the graph before the Redmi's clock starts: the engine build was the largest part of
        // firstPcm and it overlapped with nothing. Nothing is sent until the Redmi answers with its
        // endpoint and the attach runs; the downlink starts with the graph.
        if engineGateOpen(for: uuid) {
            audioPathLog("startLocal called (live call)")
            AudioEngine.shared.startLocal()
            audioPathLog("startLocal returned")
        }
        if !audioRequested {
            audioRequested = true
            GatewayConnection.shared.sendAudioStart()
            audioPathLog("audio_start sent")
        }
        if audioActivated, let endpoint { startEngine(endpoint) }
    }

    func handleAudioReady(session: UInt32, port: UInt16, key: Data?, redundancy: Int = 0) {
        guard let key else {
            GatewayConnection.shared.log("Refused unsealed call audio")
            return
        }
        let ready = (session: session, port: port, key: key)
        endpoint = ready
        // The uplink copy only helps where the extra bytes are free: over the hotspot or home
        // Wi-Fi. A remote link runs on the phone's data plan, and the user's rule is to spend as
        // little of it as possible, so the plain stream stays there.
        let onLocalLink = !GatewayConnection.shared.isRemoteLink
        AudioEngine.shared.setUplinkRedundancy(redundancy > 0 && onLocalLink)
        if redundancy > 0, !onLocalLink {
            GatewayConnection.shared.log("Uplink redundancy offered but the link is remote; keeping the plain stream")
        }
        audioPathLog("audio_ready received (activated=\(audioActivated), redundancy=\(redundancy))")
        // The attach takes the endpoint and the running engine, never eligibility again: a second
        // gate here is what left v1 with an engine that could not be attached (M4). If the graph is
        // not running (a pickup whose engine was never started early, or a late ready), startEngine
        // builds it now instead of dropping the only ready message this arm will send.
        if let uuid = audioCallUuid {
            if !engineGateOpen(for: uuid) {
                GatewayConnection.shared.log("audio_ready: call gate is shut; attaching anyway rather than dropping the ready")
            }
            startEngine(ready)
        } else {
            GatewayConnection.shared.log("audio_ready arrived with no call waiting for audio; endpoint stored")
        }
    }

    func recoverCallAudio() {
        guard audioActivated, let uuid = audioCallUuid, isAudioEligible(uuid), let endpoint,
              GatewayConnection.shared.isAuthenticated else { return }
        startEngine(endpoint, force: true)
    }

    private func startEngine(_ endpoint: (session: UInt32, port: UInt16, key: Data), force: Bool = false) {
        let host = GatewayConnection.shared.connectedHost
        if !force, AudioEngine.shared.isRunning, AudioEngine.shared.isAttached, let running = runningEndpoint,
           running.host == host, running.session == endpoint.session, running.port == endpoint.port {
            return
        }
        // The engine builds and starts synchronously here; its duration is the biggest single part
        // of the iPhone's firstPcm, so it is measured from the answer tap on both sides.
        audioPathLog("engine start called")
        if AudioEngine.shared.isRunning, !AudioEngine.shared.isAttached, !force {
            // The graph was built before the Redmi armed (a transfer); attach it instead of throwing
            // it away and paying for the build a second time on the critical path.
            AudioEngine.shared.attachNetwork(host: host, port: endpoint.port,
                                             session: endpoint.session, key: endpoint.key)
        } else {
            AudioEngine.shared.start(host: host, port: endpoint.port, session: endpoint.session, key: endpoint.key)
        }
        if AudioEngine.shared.isRunning {
            runningEndpoint = (host: host, session: endpoint.session, port: endpoint.port)
            audioPathLog("engine.start() returned (running=true)")
            // CallKit keeps the app alive while the engine runs; the transfer no longer needs it.
            endTransferTask()
        } else {
            runningEndpoint = nil
            audioPathLog("engine.start() returned (running=false)")
        }
    }

    private func stopAudio() {
        AudioEngine.shared.stop()
        runningEndpoint = nil
        endpoint = nil
        if audioRequested { GatewayConnection.shared.sendAudioStop() }
        audioRequested = false
        audioCallUuid = nil
        audioPathStartedAt = nil
        endTransferTask()
    }

    /// The Redmi took the conversation back (its decision, or the person's on either phone).
    func handleAudioReturnedToRedmi(reason: String) {
        AudioEngine.shared.stop(preservingControls: true)
        runningEndpoint = nil
        endpoint = nil
        audioRequested = false
        endTransferTask()
        guard let uuid = audioCallUuid, states[uuid] == "active" else { return }
        if reason == "manual_redmi" {
            // The person took the call on the Redmi; "iPhone'a al" brings it back.
            if let rawId = callIdByUuid[uuid] { endedCallIds[rawId] = Date() }
            end(uuid, reason: .answeredElsewhere)
            return
        }
        // Quiet retries for a network hiccup (more over a remote link); after that the person decides.
        let limit = GatewayConnection.shared.isRemoteLink ? 3 : 1
        if Self.transientFallbacks.contains(reason), audioActivated, autoRetries[uuid, default: 0] < limit {
            autoRetries[uuid, default: 0] += 1
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard self.states[uuid] == "active", !self.audioRequested,
                      GatewayConnection.shared.isAuthenticated else { return }
                self.audioRequested = true
                GatewayConnection.shared.sendAudioStart()
            }
        }
    }

    /// The in-app Answer button; goes through CallKit so both screens agree.
    func answerFromApp(callId: String) {
        if let uuid = uuidByCallId[callId], incoming.contains(uuid) {
            callController.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { error in
                guard error != nil else { return }
                Task { @MainActor in GatewayConnection.shared.lastCallError = "iPhone'da yanıtlanamadı." }
            }
        } else {
            pickupAfterAnswer = callId
            GatewayConnection.shared.sendAnswer(callId: callId)
        }
    }

    func setMuted(_ muted: Bool) {
        if let uuid = currentCallUuid {
            callController.request(CXTransaction(action: CXSetMutedCallAction(call: uuid, muted: muted))) { _ in }
        } else {
            AudioEngine.shared.isMuted = muted
        }
    }

    /// "Görüşmeyi iPhone'a al": continue the Redmi's current call on this iPhone.
    ///
    /// Called by the in-app button and by the Redmi's own "iPhone'a aktar" button, which asks over
    /// the existing audio_state message.
    func takeCallOnIPhone() {
        let connection = GatewayConnection.shared
        guard let call = connection.currentCall, call.state == .active, connection.isAuthenticated else {
            // Told apart from the working case in the log: the Redmi asked, but there is no active
            // call on the link to move (yet).
            GatewayConnection.shared.log("Cannot take the call audio: no active call on the link")
            return
        }
        audioPathStartedAt = Date()
        audioPathLog("take on iPhone requested")
        // The request can arrive more than once (the Redmi repeats it, and the app's own button
        // sends the same one). A second audio_start re-arms the Redmi's relay and restarts its
        // readiness window, which the log showed as two "Call audio session armed" 196 ms apart
        // followed by a failed cutover, so sending it is idempotent here.
        if audioRequested {
            audioPathLog("audio already requested; duplicate request ignored")
            return
        }
        // One place for the warm-up stamps, so both branches read the same way in the log.
        func warmTheGraph() {
            audioPathLog("warmUp called")
            AudioEngine.shared.warmUp()
            audioPathLog("warmUp finished")
        }
        if let uuid = currentCallUuid {
            audioCallUuid = uuid
            audioRequested = true
            beginTransferTask()
            // Build the graph before the Redmi's clock starts. v1 sent audio_start first and paid the
            // build inside firstPcm, where it overlapped with nothing; the person waits for it either
            // way, so it belongs before the arm. The same gate decides this and the attach
            // (requirement 5); a shut gate skips the build and the attach path builds it instead.
            warmTheGraph()
            if engineGateOpen(for: uuid) {
                audioPathLog("startLocal called (transfer)")
                AudioEngine.shared.startLocal()
                audioPathLog("startLocal returned")
            } else {
                audioPathLog("startLocal skipped: the call audio gate is shut")
            }
            connection.sendAudioStart()
            audioPathLog("audio_start sent")
            return
        }
        // No CallKit call yet: CallKit has to be asked for one and activate it either way, so this
        // warm-up runs while that happens and the Redmi's clock starts only later.
        warmTheGraph()
        AudioEngine.shared.prepareSession()
        beginTransferTask()
        let uuid = UUID()
        uuidByCallId[call.rawCallId] = uuid
        callIdByUuid[uuid] = call.rawCallId
        states[uuid] = "active"
        numbers[uuid] = call.number
        outgoing.insert(uuid)
        pickups.insert(uuid)
        connectedOutgoing.insert(uuid)
        let action = CXStartCallAction(call: uuid, handle: handle(for: call.number))
        callController.request(CXTransaction(action: action)) { error in
            guard error != nil else { return }
            Task { @MainActor in
                let manager = CallKitManager.shared
                manager.cleanup(uuid)
                manager.endTransferTask()
                GatewayConnection.shared.lastCallError = "Görüşme iPhone'a alınamadı."
                // iOS refuses to start a call from the background; without this line the failure
                // was silent and the Redmi waited eight seconds for audio that never came.
                let place = UIApplication.shared.applicationState == .background ? "background" : "foreground"
                let code = error.map { ($0 as NSError).code } ?? -1
                GatewayConnection.shared.log("CallKit refused the pickup in the \(place): \(code)")
            }
        }
    }

    /// Background time for a transfer that starts while the app is off screen: CallKit activation
    /// and the engine start take a few hundred milliseconds that iOS must not cut short.
    private func beginTransferTask() {
        guard UIApplication.shared.applicationState != .active, transferTask == .invalid else { return }
        transferTask = UIApplication.shared.beginBackgroundTask(withName: "take-audio") { [weak self] in
            Task { @MainActor in self?.endTransferTask() }
        }
        GatewayConnection.shared.log("Holding background time for the transfer")
    }

    private func endTransferTask() {
        guard transferTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(transferTask)
        transferTask = .invalid
    }

    /// "Redmi'ye aktar": the conversation continues on the Redmi; the iPhone lets go of it.
    func handOffToRedmi() {
        let connection = GatewayConnection.shared
        connection.sendAudioToRedmi()
        guard let uuid = currentCallUuid else { return }
        if let rawId = callIdByUuid[uuid] { endedCallIds[rawId] = Date() }
        audioRequested = false
        end(uuid, reason: .answeredElsewhere)
    }

    func handleGatewayLinkLost() {
        AudioEngine.shared.stop(preservingControls: true)
        runningEndpoint = nil
        endpoint = nil
        audioRequested = false
        linkLostTask?.cancel()
        guard !states.isEmpty else { return }
        // The cellular call continues on the Redmi. Keep the iPhone call briefly so a quick
        // reconnect resumes audio here; after that the iPhone stops presenting it.
        linkLostTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            guard !Task.isCancelled, !GatewayConnection.shared.isAuthenticated else { return }
            for uuid in Array(self.states.keys) {
                if self.states[uuid] == "ringing" && BLECentralManager.shared.isConnected { continue }
                self.end(uuid, reason: .failed)
            }
            GatewayConnection.shared.lastCallError = "Redmi bağlantısı koptu; görüşme Redmi'de sürüyor."
        }
    }

    func handleGatewayLinkRestored() {
        linkLostTask?.cancel()
        linkLostTask = nil
        if let pending = pendingDial {
            pendingDial = nil
            if Date().timeIntervalSince(pending.at) < 20 { startOutgoingCall(phoneNumber: pending.number) }
        }
        // The link came back while this iPhone was carrying the call (a network move mid-call):
        // ask for the audio again here rather than waiting for the Redmi's state broadcast, so the
        // conversation is never left silent after the two phones change networks.
        if let uuid = audioCallUuid, !audioRequested, isAudioEligible(uuid),
           GatewayConnection.shared.isAuthenticated {
            audioRequested = true
            GatewayConnection.shared.sendAudioStart()
        }
    }

    // MARK: - Call waiting

    /// A second call rings during the current one; iOS shows its own call-waiting choices.
    func handleWaitingCall(callId: String, number: String?, ringing: Bool) {
        if ringing {
            guard !callId.isEmpty, uuidByCallId[callId] == nil else { return }
            let uuid = UUID()
            uuidByCallId[callId] = uuid
            callIdByUuid[uuid] = callId
            states[uuid] = "ringing"
            if let number, !number.isEmpty { numbers[uuid] = number }
            incoming.insert(uuid)
            reportIncoming(uuid: uuid, rawId: callId, number: number)
        } else if let uuid = uuidByCallId[callId], states[uuid] == "ringing", !answeredHere.contains(uuid) {
            // It stopped waiting without being answered (declined or the caller hung up).
            end(uuid, reason: .unanswered)
        }
    }

    /// In-app buttons for the waiting call.
    func declineWaiting() {
        guard let callId = GatewayConnection.shared.waitingCall?.rawCallId, let uuid = uuidByCallId[callId] else { return }
        callController.request(CXTransaction(action: CXEndCallAction(call: uuid))) { _ in }
    }

    func answerWaitingEndingCurrent() {
        guard let callId = GatewayConnection.shared.waitingCall?.rawCallId, let waiting = uuidByCallId[callId] else { return }
        var actions: [CXAction] = []
        if let current = currentCallUuid, current != waiting { actions.append(CXEndCallAction(call: current)) }
        actions.append(CXAnswerCallAction(call: waiting))
        callController.request(CXTransaction(actions: actions)) { _ in }
    }

    // MARK: - Calls started outside the app

    /// A call from iOS Recents or Contacts arrives before the link is up: dial once connected.
    private var pendingDial: (number: String, at: Date)?

    func dialFromSystem(_ number: String) {
        if GatewayConnection.shared.isAuthenticated {
            startOutgoingCall(phoneNumber: number)
        } else {
            pendingDial = (number, Date())
            GatewayConnection.shared.reconnectNow(reason: "call from iOS")
        }
    }

    /// An Apple VoIP push for an incoming call (the iPhone is away from the Redmi). iOS requires
    /// a CallKit call for every such push, reported before the handler returns.
    func reportPushedCall(callId: String?, number: String?, completion: @escaping () -> Void) {
        let rawId = callId ?? ""
        if !rawId.isEmpty, let existing = uuidByCallId[rawId] {
            // Bluetooth or the network showed it first. iOS still expects a report for every VoIP
            // push; it answers "already exists" and the call stays as it is.
            provider.reportNewIncomingCall(with: existing, update: callUpdate(number: number ?? numbers[existing])) { _ in
                completion()
            }
            return
        }
        let uuid = UUID()
        if !rawId.isEmpty {
            uuidByCallId[rawId] = uuid
            callIdByUuid[uuid] = rawId
        }
        states[uuid] = "ringing"
        if let number, !number.isEmpty { numbers[uuid] = number }
        incoming.insert(uuid)
        AudioEngine.shared.prepareSession()
        provider.reportNewIncomingCall(with: uuid, update: callUpdate(number: number)) { error in
            Task { @MainActor in
                let manager = CallKitManager.shared
                if error != nil || rawId.isEmpty {
                    // Unreadable push or a call that already ended: close it again.
                    manager.end(uuid, reason: .failed)
                } else {
                    GatewayConnection.shared.sendRingingAck(callId: rawId)
                }
                completion()
            }
        }
        GatewayConnection.shared.reconnectNow(reason: "incoming call push")
    }

    func handleDialFailed() {
        if let uuid = pendingOutgoing { end(uuid, reason: .failed) }
        for uuid in outgoing where states[uuid] == "dialing" || states[uuid] == "pending" {
            end(uuid, reason: .failed)
        }
    }

    // MARK: - Reporting

    private func handle(for number: String) -> CXHandle {
        number.isEmpty ? CXHandle(type: .generic, value: "Gizli numara") : CXHandle(type: .phoneNumber, value: number)
    }

    private func callUpdate(number: String?) -> CXCallUpdate {
        let update = CXCallUpdate()
        if let number, !number.isEmpty {
            update.remoteHandle = CXHandle(type: .phoneNumber, value: number)
            // Unknown numbers are left to iOS, which matches them against Contacts itself.
            update.localizedCallerName = PhoneContactDirectory.shared.name(for: number)
        } else {
            update.remoteHandle = CXHandle(type: .generic, value: "Gizli numara")
            update.localizedCallerName = "Gizli numara"
        }
        update.hasVideo = false
        update.supportsDTMF = true
        update.supportsHolding = true
        update.supportsGrouping = true
        update.supportsUngrouping = false
        return update
    }

    private func reportIncoming(uuid: UUID, rawId: String, number: String?) {
        AudioEngine.shared.prepareSession()
        // The audio path starts here: the user still has to answer, so warming the graph now hides
        // its cost completely (measured: the voice-processing setup is the biggest single part of
        // the iPhone's firstPcm). Nothing captures audio yet - the engine stays stopped.
        audioPathStartedAt = Date()
        audioPathLog("call presented")
        audioPathLog("warmUp called")
        AudioEngine.shared.warmUp()
        audioPathLog("warmUp finished")
        provider.reportNewIncomingCall(with: uuid, update: callUpdate(number: number)) { error in
            Task { @MainActor in
                if let error {
                    GatewayConnection.shared.log("CallKit refused the incoming call: \((error as NSError).code)")
                    CallKitManager.shared.cleanup(uuid)
                } else {
                    // The Redmi may now ring quietly: this iPhone is showing the call.
                    GatewayConnection.shared.sendRingingAck(callId: rawId)
                }
            }
        }
    }

    // MARK: - Starting calls from the iPhone

    func startOutgoingCall(phoneNumber: String) {
        let connection = GatewayConnection.shared
        connection.lastCallError = nil
        let clean = phoneNumber.replacingOccurrences(of: "[\\s().-]", with: "", options: .regularExpression)
        guard clean.range(of: "^[0-9*#+]{2,20}$", options: .regularExpression) != nil else {
            connection.lastCallError = "Geçerli bir telefon numarası gir."
            return
        }
        if Self.emergencyNumbers.contains(clean) {
            // Emergency calls stay with the iPhone's own phone service.
            if let url = URL(string: "tel://\(clean)") { UIApplication.shared.open(url) }
            return
        }
        guard connection.isAuthenticated else {
            connection.lastCallError = "Redmi bağlantısı yok. Arama başlatılamadı."
            return
        }
        guard pendingOutgoing == nil, connection.currentCall == nil else {
            connection.lastCallError = "Zaten bir arama sürüyor."
            return
        }
        AudioEngine.shared.prepareSession()
        let uuid = UUID()
        let temporaryId = "outgoing_\(uuid.uuidString.prefix(8))"
        uuidByCallId[temporaryId] = uuid
        callIdByUuid[uuid] = temporaryId
        // Audio waits for the Redmi's dialing event: CallKit can activate the iPhone session
        // before the dial command reaches the Redmi.
        states[uuid] = "pending"
        numbers[uuid] = clean
        outgoing.insert(uuid)
        pendingOutgoing = uuid

        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .phoneNumber, value: clean))
        callController.request(CXTransaction(action: action)) { error in
            Task { @MainActor in
                let connection = GatewayConnection.shared
                if error != nil {
                    CallKitManager.shared.cleanup(uuid)
                    // Keep the tap effective even if iOS refuses its call screen.
                    connection.sendDial(number: clean)
                    connection.lastCallError = "iPhone arama ekranı açılamadı; arama Redmi'de sürüyor."
                } else {
                    connection.sendDial(number: clean)
                }
            }
        }
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        stopAudio()
        audioActivated = false
        AudioEngine.shared.resetSessionPrepared()
        uuidByCallId.removeAll()
        callIdByUuid.removeAll()
        states.removeAll()
        numbers.removeAll()
        incoming.removeAll()
        outgoing.removeAll()
        pickups.removeAll()
        answeredHere.removeAll()
        connectedOutgoing.removeAll()
        pendingOutgoing = nil
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let connection = GatewayConnection.shared
        let uuid = action.callUUID
        let rawId = callIdByUuid[uuid] ?? ""
        guard !rawId.isEmpty, states[uuid] == "ringing",
              connection.isAuthenticated || BLECentralManager.shared.isReady else {
            connection.lastCallError = "Redmi'ye ulaşılamadı; arama Redmi'de çalıyor."
            action.fail()
            return
        }
        answeredHere.insert(uuid)
        audioCallUuid = uuid
        // Keep the ring-time origin when there is one, so the whole path reads on one timeline.
        if audioPathStartedAt == nil { audioPathStartedAt = Date() }
        audioPathLog("answer tapped")
        connection.sendAnswer(callId: rawId)
        if connection.isAuthenticated {
            audioRequested = true
            connection.sendAudioStart()
            audioPathLog("audio_start sent")
        } else {
            // Answered over Bluetooth; audio follows as soon as the network link is back.
            connection.reconnectNow(reason: "answered on iPhone")
        }
        // After audio_start, never before: the Redmi's arm clock starts with that frame, and the
        // ring-time warm-up has long finished for a call that rang here, so this is a no-op on the
        // critical path. Starting the engine still waits for CallKit either way.
        audioPathLog("warmUp called")
        AudioEngine.shared.warmUp()
        audioPathLog("warmUp finished")
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        let rawId = callIdByUuid[uuid] ?? ""
        let state = states[uuid] ?? "active"
        let connection = GatewayConnection.shared
        if !rawId.isEmpty, !rawId.hasPrefix("outgoing_") {
            if state == "ringing" {
                connection.sendReject(callId: rawId)
            } else {
                connection.sendHangup(callId: rawId)
            }
            endedCallIds[rawId] = Date()
        } else if rawId.hasPrefix("outgoing_"), let call = connection.currentCall {
            connection.sendHangup(callId: call.rawCallId)
        }
        cleanup(uuid)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
        if pickups.contains(action.callUUID) {
            provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: nil)
            provider.reportOutgoingCall(with: action.callUUID, connectedAt: nil)
        }
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        AudioEngine.shared.isMuted = action.isMuted
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        let rawId = callIdByUuid[action.callUUID]
        for digit in action.digits {
            GatewayConnection.shared.sendDtmf(digit: String(digit), callId: rawId)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        let uuid = action.callUUID
        let rawId = callIdByUuid[uuid] ?? ""
        GatewayConnection.shared.send([
            "type": "hold_call",
            "callId": rawId,
            "hold": action.isOnHold
        ])
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetGroupCallAction) {
        GatewayConnection.shared.send([
            "type": "merge_calls"
        ])
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audioActivated = true
        audioPathLog("didActivate received")
        if !AudioEngine.shared.isSessionPrepared && !AudioEngine.shared.isRunning {
            AudioEngine.shared.prepareSession()
        }
        for uuid in answeredHere.union(outgoing) { maybeStartAudio(for: uuid) }
        // Same gate as the early start and the attach; a session that activates late still gets its
        // engine, and an attach that already happened is a logged no-op inside AudioEngine.
        if let endpoint, engineGateOpen(for: audioCallUuid) {
            startEngine(endpoint)
        } else if endpoint != nil {
            GatewayConnection.shared.log("didActivate with no gated call waiting for audio; nothing to start")
        }
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        audioActivated = false
        stopAudio()
    }
}
