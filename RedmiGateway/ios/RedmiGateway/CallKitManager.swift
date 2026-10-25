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
    /// Incoming calls the Redmi itself reported as ringing, over the link or Bluetooth. A call shown
    /// only because of an Apple push is not in here until the Redmi confirms it; see startRingWatchdog.
    private var ringConfirmed: Set<UUID> = []
    private var connectedOutgoing: Set<UUID> = []
    private var autoRetries: [UUID: Int] = [:]
    private var pendingOutgoing: UUID?
    /// Redmi call ids that were already live when this iPhone dialled. With a second call ("Çağrı
    /// ekle") an event for the first call must never be taken as the new call's first event; see
    /// uuidFor.
    private var callIdsBeforeDial: Set<String> = []
    /// Resumes asked of CallKit only to show what the Redmi already did; the provider does not send
    /// them back. See resumeInCallKitIfRedmiDid.
    private var callKitOnlyHoldChanges: Set<UUID> = []
    /// Calls held in CallKit for "Çağrı ekle"; the Redmi holds them itself when it dials, so the
    /// provider does not send hold_call for these. See startOutgoingCall.
    private var heldForAddedCall: Set<UUID> = []
    /// When "Birleştir" was asked; see the CXSetHeldCallAction handler.
    private var mergeAskedAt: Date?
    /// Starts the paused engine if no activation follows a resume; see startAudioIfResumeLeftItOff.
    private var resumeAudioTask: Task<Void, Never>?
    /// When a hold or resume was last asked for each call through CallKit.
    private var holdRequestedAt: [UUID: Date] = [:]
    /// Waiting calls "Beklet ve yanıtla" is answering; the answer keeps the current call on hold.
    private var answerHoldingCurrent: Set<UUID> = []
    /// Waiting calls answered here with the current call kept on hold, and when; see the "ringing"
    /// case in handleGatewayCallEvent.
    private var holdAnswerAt: [UUID: Date] = [:]
    private var endedCallIds: [String: Date] = [:]
    /// Answered from the app without a CallKit call; its audio moves here once it is active.
    private var pickupAfterAnswer: String?

    private var audioActivated = false
    private var audioRequested = false
    private var audioCallUuid: UUID?
    /// Repeats the audio request after an answer until the audio moves here; see startAudioRetry.
    private var audioRetryTask: Task<Void, Never>?
    private var endpoint: (session: UInt32, port: UInt16, key: Data)?
    private var runningEndpoint: (host: String, session: UInt32, port: UInt16)?
    private var duplicateStartLogged = false
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
            ringConfirmed.insert(uuid)
            if !incoming.contains(uuid) && !outgoing.contains(uuid) {
                incoming.insert(uuid)
                reportIncoming(uuid: uuid, rawId: rawId, number: numbers[uuid])
            } else if incoming.contains(uuid) {
                // The Redmi repeats the live call state on every authentication. Sending the
                // confirmation again here is what recovers a ringing_ack that died with the socket
                // it travelled on: measured on 22.10.2026 the socket closed 1.3 s after it opened,
                // so the Redmi never learned that this iPhone was already showing the call.
                GatewayConnection.shared.sendRingingAck(callId: rawId)
                if let number = event.number, !number.isEmpty {
                    provider.reportCall(with: uuid, updated: callUpdate(number: number))
                }
            }
            if incoming.contains(uuid), answeredHere.contains(uuid),
               let heldAnswer = holdAnswerAt[uuid], Date().timeIntervalSince(heldAnswer) < 15 {
                // A waiting call answered with the current call kept on hold: the Redmi made no
                // "active" announcement for it, so "ringing" only means the radio has not confirmed
                // yet. Taking it for a withdrawn answer would stop the audio of the call on hold too.
                GatewayConnection.shared.log("Call \(rawId.prefix(8)) still ringing after hold and answer; waiting for the radio")
            } else if incoming.contains(uuid), answeredHere.contains(uuid) {
                // Answered here, and the Redmi took that announcement back: the radio never confirmed,
                // so nobody is on the call and it is really ringing on the Redmi again. Close this
                // answered CallKit call - it was never connected - and show the call as incoming once
                // more, so the person can answer it from this iPhone instead of being left with a
                // silent "connected" call and no way to pick the real one up.
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
        // Logged only when there is something to warm: the Redmi repeats a live call's state many
        // times a second, and the repeats filled the log ring until the lines that mattered dropped.
        if ["dialing", "connecting", "active"].contains(state), !AudioEngine.shared.isRunning {
            audioPathLog("live call; warming the audio graph")
            AudioEngine.shared.warmUp()
        }
    }

    private func uuidFor(rawId: String, state: String) -> UUID {
        if let existing = uuidByCallId[rawId] { return existing }
        if let pending = pendingOutgoing, ["dialing", "connecting", "active"].contains(state) {
            if callIdsBeforeDial.contains(rawId) {
                // A second call is being dialled and this event is about the first one, which has no
                // CallKit call here (it is carried on the Redmi). Taken as the new call, it would
                // have been shown as the dialled call and its real events dropped.
                GatewayConnection.shared.log("Event for call \(rawId.prefix(8)), live before this dial; not taken as the new call")
            } else {
                // The first event of the call this iPhone just dialed.
                pendingOutgoing = nil
                callIdsBeforeDial = []
                if let temporary = callIdByUuid[pending] { uuidByCallId.removeValue(forKey: temporary) }
                uuidByCallId[rawId] = pending
                callIdByUuid[pending] = rawId
                return pending
            }
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
        ringConfirmed.remove(uuid)
        connectedOutgoing.remove(uuid)
        autoRetries.removeValue(forKey: uuid)
        callKitOnlyHoldChanges.remove(uuid)
        heldForAddedCall.remove(uuid)
        holdRequestedAt.removeValue(forKey: uuid)
        answerHoldingCurrent.remove(uuid)
        holdAnswerAt.removeValue(forKey: uuid)
        if pendingOutgoing == uuid {
            pendingOutgoing = nil
            callIdsBeforeDial = []
        }
        if audioCallUuid == uuid { handOverOrStopAudio() }
    }

    /// The call that owned the audio is gone from this iPhone (ended, merged away or refused).
    ///
    /// The Redmi relays device-level audio: the same stream goes on through a second call, a hold, a
    /// swap and a merge (lab-measured), so while another call of the session is still live on the
    /// Redmi the engine keeps running and that call owns the audio. Only the last call stops it.
    private func handOverOrStopAudio() {
        if let next = audioSuccessor() {
            audioCallUuid = next
            audioPathLog("the call that owned the audio is gone; call \(callIdByUuid[next].map { String($0.prefix(8)) } ?? "?") keeps it running")
            return
        }
        audioPathLog("the call that owned the audio is gone and no other call is live; stopping the call audio")
        stopAudio()
    }

    /// Another call of this session that can carry the audio on: answered or dialled here, and live
    /// on the Redmi according to its latest calls list (an older Redmi sends none). Prefers the
    /// active call.
    private func audioSuccessor() -> UUID? {
        let liveStates: Set<String> = ["active", "holding", "dialing", "connecting"]
        let liveIds = Set(GatewayConnection.shared.calls
            .filter { !$0.isWaiting && liveStates.contains($0.state) }
            .map { $0.callId })
        if liveIds.isEmpty { return nil }
        let candidates = incoming.union(outgoing).filter { uuid in
            if incoming.contains(uuid) && !answeredHere.contains(uuid) { return false }
            guard let rawId = callIdByUuid[uuid] else { return false }
            return liveIds.contains(rawId)
        }
        return candidates.first(where: { states[$0] == "active" }) ?? candidates.first
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
        case "holding":
            // On hold inside a session that already carries its audio here (a second call, a swap).
            // The audio is device-level on the Redmi, so a hold must never stop it, skip a recovery
            // or refuse the endpoint. A held call never starts audio on its own.
            guard uuid == audioCallUuid else { return false }
            return incoming.contains(uuid) ? answeredHere.contains(uuid) : outgoing.contains(uuid)
        case "ringing":
            // Answered here a moment ago; the Redmi's "active" event is still on its way. The audio
            // engine may start as soon as the Redmi sends its endpoint, so the sound is already
            // flowing when the call goes active ("the sound jumps to the iPhone at once").
            return incoming.contains(uuid) && answeredHere.contains(uuid)
        default: return false
        }
    }

    private func maybeStartAudio(for uuid: UUID) {
        guard isAudioEligible(uuid), GatewayConnection.shared.isAuthenticated else { return }
        audioCallUuid = uuid
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
        if audioActivated, let uuid = audioCallUuid, isAudioEligible(uuid) {
            startEngine(ready)
        }
    }

    func recoverCallAudio() {
        guard audioActivated else {
            audioPathLog("recover skipped: audio session not active")
            return
        }
        guard let uuid = audioCallUuid, isAudioEligible(uuid) else {
            audioPathLog("recover skipped: no eligible call")
            return
        }
        guard let endpoint else {
            audioPathLog("recover skipped: no audio endpoint")
            return
        }
        guard GatewayConnection.shared.isAuthenticated else {
            audioPathLog("recover skipped: link not authenticated")
            return
        }
        audioPathLog("recover: restarting the audio engine")
        startEngine(endpoint, force: true)
    }

    private func startEngine(_ endpoint: (session: UInt32, port: UInt16, key: Data), force: Bool = false) {
        let host = GatewayConnection.shared.connectedHost
        if !force, AudioEngine.shared.isRunning, let running = runningEndpoint,
           running.host == host, running.session == endpoint.session, running.port == endpoint.port {
            // Once per run: every repeated call state lands here.
            if !duplicateStartLogged {
                duplicateStartLogged = true
                audioPathLog("engine already running for this endpoint; duplicate starts ignored")
            }
            return
        }
        duplicateStartLogged = false
        // The engine builds and starts synchronously here; its duration is the biggest single part
        // of the iPhone's firstPcm, so it is measured from the answer tap on both sides.
        audioPathLog("engine start called")
        AudioEngine.shared.start(host: host, port: endpoint.port, session: endpoint.session,
                                 key: endpoint.key, force: force)
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
        resumeAudioTask?.cancel()
        resumeAudioTask = nil
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
            connection.sendAudioStart()
            audioPathLog("audio_start sent")
            // After audio_start, never before: the Redmi's arm clock starts with that frame and no
            // ring warmed this path, so the cost is real and must land inside firstPcm instead of
            // being hidden from the measurement by delaying the arm.
            warmTheGraph()
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
        guard let uuid = currentCallUuid else {
            connection.log("Hand-off to the Redmi: the current call has no CallKit call here")
            return
        }
        if let rawId = callIdByUuid[uuid] { endedCallIds[rawId] = Date() }
        audioRequested = false
        // The audio is device-level, so every call of the session goes to the Redmi with it: a call
        // on hold left in CallKit would keep the audio here for a conversation this iPhone no longer
        // carries. A call still ringing here stays with the call-waiting flow.
        var leaving = [uuid]
        for other in incoming.union(outgoing) where other != uuid && other != pendingOutgoing && states[other] != "ringing" {
            leaving.append(other)
        }
        // Stopped here, before the calls end: ending them one by one would hand the audio from one
        // to the next instead of letting it go.
        if let owner = audioCallUuid, leaving.contains(owner) { stopAudio() }
        if leaving.count > 1 { connection.log("Hand-off to the Redmi: \(leaving.count) calls leave this iPhone") }
        for call in leaving { end(call, reason: .answeredElsewhere) }
    }

    func handleGatewayLinkLost() {
        resumeAudioTask?.cancel()
        resumeAudioTask = nil
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
            // Already shown, for example from an Apple push: this is the Redmi confirming that it
            // rings (the Redmi repeats the waiting call on every authentication).
            if let known = uuidByCallId[callId] { ringConfirmed.insert(known) }
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

    /// "Beklet ve yanıtla": the current call goes on hold and the waiting call is answered, the same
    /// as the system screen's "Hold & Accept" - one transaction, a hold then the answer. The answer
    /// then asks the Redmi to keep the current call (holdCurrent), even when that call has no
    /// CallKit call here. The audio session runs on: it is device-level on the Redmi.
    func answerWaitingHoldingCurrent() {
        let connection = GatewayConnection.shared
        guard let callId = connection.waitingCall?.rawCallId, let waiting = uuidByCallId[callId] else {
            connection.log("Hold and answer: no waiting call shown here")
            return
        }
        var actions: [CXAction] = []
        if let current = currentCallUuid, current != waiting,
           callController.callObserver.calls.contains(where: { $0.uuid == current && !$0.isOnHold && !$0.hasEnded }) {
            actions.append(CXSetHeldCallAction(call: current, onHold: true))
        }
        actions.append(CXAnswerCallAction(call: waiting))
        answerHoldingCurrent.insert(waiting)
        connection.log("Hold and answer: answering call \(callId.prefix(8)); \(actions.count > 1 ? "holding the current call in CallKit" : "no CallKit call to hold here, the Redmi holds it")")
        callController.request(CXTransaction(actions: actions)) { error in
            guard error != nil else { return }
            Task { @MainActor in
                let code = error.map { ($0 as NSError).code } ?? -1
                CallKitManager.shared.answerHoldingCurrent.remove(waiting)
                GatewayConnection.shared.log("CallKit refused hold and answer: \(code)")
                GatewayConnection.shared.lastCallError = "Bekleyen arama yanıtlanamadı."
            }
        }
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
            // push; it answers "already exists" and the call stays as it is. The ack is re-sent here
            // on purpose: the Redmi is waiting for one, and without it the Redmi starts its fallback
            // ring while this phone is already ringing - two phones ringing for one call.
            GatewayConnection.shared.sendRingingAck(callId: rawId)
            provider.reportNewIncomingCall(with: existing, update: callUpdate(number: number ?? numbers[existing])) { _ in
                completion()
            }
            return
        }
        if !rawId.isEmpty, let ended = endedCallIds[rawId], Date().timeIntervalSince(ended) < 300 {
            // The call is already over here (the Redmi ended it, or it was declined) and the push
            // only arrived late. iOS still requires a CallKit report for every VoIP push, so it is
            // reported and closed at once instead of ringing for a call nobody is on.
            GatewayConnection.shared.log("VoIP push for a call that already ended; reported and closed at once")
            let late = UUID()
            provider.reportNewIncomingCall(with: late, update: callUpdate(number: number)) { error in
                Task { @MainActor in
                    if let error {
                        GatewayConnection.shared.log("CallKit refused the late pushed call: \((error as NSError).code)")
                    }
                    CallKitManager.shared.end(late, reason: .remoteEnded)
                    completion()
                }
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
                if let error {
                    // This was the silent branch: a refused push call left neither a ring nor a
                    // line in the log, so a day of failed rings looked like "nothing arrived".
                    GatewayConnection.shared.log("CallKit refused the pushed call: \((error as NSError).code)")
                }
                if error != nil || rawId.isEmpty {
                    // Unreadable push or a call that already ended: close it again.
                    manager.end(uuid, reason: .failed)
                } else {
                    GatewayConnection.shared.sendRingingAck(callId: rawId)
                    manager.startRingWatchdog(uuid: uuid)
                }
                completion()
            }
        }
        GatewayConnection.shared.reconnectNow(reason: "incoming call push")
    }

    /// How long a call shown only because of an Apple push may ring without the Redmi confirming it.
    private static let unconfirmedRingLimit: TimeInterval = 45

    /// Ends a pushed ring that the Redmi never confirmed.
    ///
    /// A push only says that a call started. When the caller gives up before this iPhone reaches the
    /// Redmi (away from home, the VPN slow to come back), nothing ever says the call ended and the
    /// iPhone rang on for good. After 45 s without the Redmi reporting the call as ringing it is
    /// ended as unanswered; a call the Redmi did confirm is left to the Redmi's own state. The id is
    /// not kept as ended, so a "ringing" that still arrives from the Redmi presents the call again
    /// instead of being refused as a repeat.
    private func startRingWatchdog(uuid: UUID) {
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.unconfirmedRingLimit * 1_000_000_000))
            guard let self else { return }
            let ringing = self.incoming.contains(uuid) && self.states[uuid] == "ringing"
            let stillRinging = ringing && !self.answeredHere.contains(uuid)
            if !stillRinging {
                GatewayConnection.shared.log("Ring watchdog: the pushed call was answered or has ended")
            } else if self.ringConfirmed.contains(uuid) {
                GatewayConnection.shared.log("Ring watchdog: the Redmi confirmed the pushed call; it keeps ringing")
            } else {
                let rawId = self.callIdByUuid[uuid]
                GatewayConnection.shared.log("Ring watchdog: the Redmi never confirmed the pushed call in \(Int(Self.unconfirmedRingLimit)) s; ending it as unanswered")
                self.end(uuid, reason: .unanswered)
                if let rawId { self.endedCallIds.removeValue(forKey: rawId) }
            }
        }
    }

    /// The Redmi's own state says no call is live: a state reply without a call, or Bluetooth "idle".
    ///
    /// A call that reached this iPhone only as an Apple push never became the link's current call,
    /// so the "disconnected" that ends the current call never reached it, and a caller who hung up
    /// before the socket authenticated left this iPhone ringing for good. Every incoming call still
    /// ringing here is over, including one answered here that the Redmi never confirmed as active.
    /// Outgoing calls are left alone: a call just dialled may not exist on the Redmi yet.
    func endRingingCallsWithoutRedmiCall(source: String) {
        let stale = incoming.filter { states[$0] == "ringing" }
        if !stale.isEmpty {
            GatewayConnection.shared.log("The Redmi reports no call (\(source)); ending \(stale.count) ringing call(s) shown here")
            for uuid in stale { end(uuid, reason: .remoteEnded) }
        }
    }

    func handleDialFailed() {
        if let uuid = pendingOutgoing { end(uuid, reason: .failed) }
        for uuid in outgoing where states[uuid] == "dialing" || states[uuid] == "pending" {
            end(uuid, reason: .failed)
        }
        resumeCallHeldForAddedCall()
    }

    /// An added call that never went out: CallKit held the first call for it, and nothing resumes
    /// that call, because no call ended on the Redmi. The resume also reaches the Redmi as
    /// hold_call; Telephony ignores it for a call that was never held.
    private func resumeCallHeldForAddedCall() {
        let presented = incoming.union(outgoing)
        let held = presented.filter { uuid in
            callController.callObserver.calls.contains { $0.uuid == uuid && $0.isOnHold && !$0.hasEnded }
        }
        if presented.count == 1, let uuid = held.first {
            GatewayConnection.shared.log("The added call failed; resuming the call that was held for it")
            callController.request(CXTransaction(action: CXSetHeldCallAction(call: uuid, onHold: false))) { error in
                guard error != nil else { return }
                Task { @MainActor in
                    let code = error.map { ($0 as NSError).code } ?? -1
                    GatewayConnection.shared.log("CallKit refused to resume the held call: \(code)")
                }
            }
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
        // Every caller acts on the Redmi's own report of a ringing call.
        ringConfirmed.insert(uuid)
        // The CallKit report goes first. It is the ring the person sees and the confirmation the
        // Redmi waits for, and a background wake has a short budget: anything slow in front of it
        // (the voice-processing setup used to be here) can delay or swallow the ring itself.
        provider.reportNewIncomingCall(with: uuid, update: callUpdate(number: number)) { error in
            Task { @MainActor in
                if let error {
                    GatewayConnection.shared.log("CallKit refused the incoming call: \((error as NSError).code)")
                    CallKitManager.shared.cleanup(uuid)
                } else {
                    // The Redmi may now ring quietly: this iPhone is showing the call.
                    GatewayConnection.shared.sendRingingAck(callId: rawId)
                }
                // Nothing captures audio here; the engine only starts after CallKit activates the
                // session. Warming now still hides the cost of answering the call.
                CallKitManager.shared.warmIncomingAudio()
            }
        }
    }

    /// Builds the audio graph while the person is still deciding, so answering does not pay for it.
    private func warmIncomingAudio() {
        AudioEngine.shared.prepareSession()
        audioPathStartedAt = Date()
        audioPathLog("call presented")
        audioPathLog("warmUp called")
        AudioEngine.shared.warmUp()
        audioPathLog("warmUp finished")
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
        if let refusal = dialRefusal() {
            connection.lastCallError = refusal
            connection.log("Dial refused here: \(refusal)")
            return
        }
        // A second call ("Çağrı ekle"): the Redmi's Telecom holds the current call when it dials, and
        // the audio keeps running for the whole session. CallKit has to be told as well, in the same
        // transaction: it never holds a call by itself, and when the added call connected it ended
        // the call it still saw as active (build 149, 25.10.2026 17:21:59 and 17:22:14).
        callIdsBeforeDial = Set(connection.calls.map { $0.callId })
        if let current = connection.currentCall {
            if !current.rawCallId.isEmpty { callIdsBeforeDial.insert(current.rawCallId) }
            connection.log("Adding a second call while call \(current.rawCallId.prefix(8)) is \(current.state.rawValue)")
        }
        // Does nothing while the engine runs, so a call in progress keeps its audio untouched.
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

        let presented = incoming.union(outgoing)
        let toHold = callController.callObserver.calls
            .filter { $0.uuid != uuid && !$0.isOnHold && !$0.hasEnded && presented.contains($0.uuid) }
            .map { $0.uuid }
        var actions: [CXAction] = toHold.map { CXSetHeldCallAction(call: $0, onHold: true) }
        heldForAddedCall.formUnion(toHold)
        if !toHold.isEmpty { connection.log("Adding a call: holding \(toHold.count) call(s) in CallKit first") }
        actions.append(CXStartCallAction(call: uuid, handle: CXHandle(type: .phoneNumber, value: clean)))
        if !callIdsBeforeDial.isEmpty { startAddedCallWatchdog(uuid) }
        callController.request(CXTransaction(actions: actions)) { error in
            Task { @MainActor in
                let connection = GatewayConnection.shared
                if error != nil {
                    CallKitManager.shared.heldForAddedCall.subtract(toHold)
                    if !toHold.isEmpty {
                        connection.log("CallKit refused the added call: \(error.map { ($0 as NSError).code } ?? -1)")
                    }
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

    /// An added call the Redmi never names (its dial went nowhere) would leave the first call held in
    /// CallKit with nothing to resume it; after 20 s it is treated as a failed dial.
    private func startAddedCallWatchdog(_ uuid: UUID) {
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            guard let self, self.pendingOutgoing == uuid else { return }
            GatewayConnection.shared.log("The Redmi named no added call in 20 s; ending it here and resuming the call before it")
            self.handleDialFailed()
        }
    }

    /// Why a dial cannot go out now, or nil when it can.
    ///
    /// With no call the dial goes out as before. With one call in progress a second one may be added,
    /// as the Redmi (2.0.12) allows: one call, active or on hold, and nothing ringing, dialling or
    /// being merged. An older Redmi sends no calls list and refuses a second call, so the old answer
    /// stays for it.
    private func dialRefusal() -> String? {
        let connection = GatewayConnection.shared
        if pendingOutgoing != nil { return "Bir arama zaten başlatılıyor." }
        if connection.currentCall == nil { return nil }
        if connection.calls.isEmpty { return "Zaten bir arama sürüyor." }
        if connection.callsMerging { return "Çağrılar birleştiriliyor; biraz bekle." }
        let settingUp: Set<String> = ["ringing", "dialing", "connecting"]
        if connection.waitingCall != nil || connection.calls.contains(where: { settingUp.contains($0.state) }) {
            return "Önce çalan ya da aranan çağrı sonuçlansın."
        }
        let calls = connection.calls.filter { !$0.isWaiting && !$0.isConferenceChild }
        if calls.count > 1 { return "Aynı anda en fazla iki çağrı olabilir." }
        if let only = calls.first, only.state == "active" || only.state == "holding" { return nil }
        return "Zaten bir arama sürüyor."
    }

    // MARK: - Several calls (the Redmi's calls list)

    /// Brings the CallKit calls in line with every call the Redmi holds (the `calls` event).
    ///
    /// The `call` event names only the primary call, so a call on hold that hung up, or the calls a
    /// merge replaced with a conference under a new id, were never closed here. The list arrives
    /// just before every `call` event, and the primary call is left to that event, which keeps the
    /// single-call path as it was. Idempotent: the same list a second time changes nothing.
    func reconcile(with items: [GatewayCallInfo], primaryId: String?, merging: Bool) {
        let connection = GatewayConnection.shared
        let live = items.filter { !$0.isWaiting }
        var byId: [String: GatewayCallInfo] = [:]
        for item in live where byId[item.callId] == nil { byId[item.callId] = item }
        let waitingIds = Set(items.filter { $0.isWaiting }.map { $0.callId })
        let waitingId = connection.waitingCall?.rawCallId
        // The call being added gets its id from the list too: the call event names only the primary
        // call, and an added call that fails before the Redmi holds the first one never becomes it.
        if let pending = pendingOutgoing, !callIdsBeforeDial.isEmpty,
           let added = live.first(where: { !callIdsBeforeDial.contains($0.callId) && uuidByCallId[$0.callId] == nil && !$0.isConferenceChild }),
           ["dialing", "connecting", "active"].contains(added.state),
           uuidFor(rawId: added.callId, state: added.state) == pending {
            connection.log("Calls list names the added call \(added.callId.prefix(8)) (\(added.state))")
            if states[pending] == "pending", added.state != "active" {
                states[pending] = added.state
                provider.reportOutgoingCall(with: pending, startedConnectingAt: Date())
            }
        }
        // A conference this iPhone has not mapped yet: a merge has just finished.
        let newConference = live.first { $0.isConference && !$0.isConferenceChild && uuidByCallId[$0.callId] == nil }

        var gone: [UUID] = []
        var intoConference: [UUID] = []
        for uuid in incoming.union(outgoing) {
            // The call being dialled has no Redmi id yet; its first event maps it (uuidFor).
            if uuid == pendingOutgoing { continue }
            guard let rawId = callIdByUuid[uuid] else { continue }
            if rawId.isEmpty || rawId == "gateway_call" || rawId.hasPrefix("outgoing_") { continue }
            // The call-waiting flow owns a waiting call.
            if rawId == waitingId || waitingIds.contains(rawId) { continue }
            // Still ringing and not answered here: the call event, the state reply and the ring
            // watchdog end those, each with the right reason.
            if incoming.contains(uuid), states[uuid] == "ringing", !answeredHere.contains(uuid) { continue }
            if let item = byId[rawId] {
                if let conference = newConference, item.parentId == conference.callId {
                    // Still listed, but now a participant of the new conference.
                    intoConference.append(uuid)
                } else if rawId != primaryId, states[uuid] != item.state {
                    connection.log("Call \(rawId.prefix(8)): \(states[uuid] ?? "none") -> \(item.state) (calls list)")
                    states[uuid] = item.state
                }
                continue
            }
            // No longer listed. The primary call's own event follows and ends it as it always did.
            if rawId == primaryId { continue }
            gone.append(uuid)
        }

        if let conference = newConference, !(gone.isEmpty && intoConference.isEmpty) {
            finishMerge(into: conference, merged: gone + intoConference)
        } else if !gone.isEmpty {
            if merging && !live.isEmpty {
                // Between the merged calls leaving and the conference arriving (11 ms measured, but
                // the network may take longer). The next list brings the conference, or, if the merge
                // failed, these calls end with it.
                connection.log("Calls list: \(gone.count) call(s) left while merging; waiting for the conference")
            } else {
                for uuid in gone {
                    connection.log("Call \(callIdByUuid[uuid].map { String($0.prefix(8)) } ?? "?") is no longer on the Redmi; ending it here")
                    end(uuid, reason: .remoteEnded)
                }
            }
        }
        forgetUnpresentedCalls(listed: Set(items.map { $0.callId }), primaryId: primaryId)
        resumeInCallKitIfRedmiDid(byId)
    }

    /// A merge finished: the network replaced the merged calls with a conference under a new id.
    ///
    /// One CallKit call carries on as the conference and keeps the audio; the other closes without
    /// touching it. Before this the iPhone ended its call while the conference went on on the Redmi,
    /// the "takıldı" the owner saw when calls were merged.
    private func finishMerge(into conference: GatewayCallInfo, merged: [UUID]) {
        let connection = GatewayConnection.shared
        guard let fallback = merged.first else {
            connection.log("Merge done, but no call here to carry the conference")
            return
        }
        let keep: UUID
        if let owner = audioCallUuid, merged.contains(owner) {
            keep = owner
        } else {
            keep = merged.first(where: { states[$0] == "active" }) ?? fallback
        }
        let oldId = callIdByUuid[keep]
        if let oldId {
            if uuidByCallId[oldId] == keep { uuidByCallId.removeValue(forKey: oldId) }
            // A late event for the old id must not raise a call again.
            endedCallIds[oldId] = Date()
        }
        uuidByCallId[conference.callId] = keep
        callIdByUuid[keep] = conference.callId
        states[keep] = conference.state
        provider.reportCall(with: keep, updated: conferenceUpdate())
        connection.log("Merge done: call \(oldId.map { String($0.prefix(8)) } ?? "?") now carries the conference \(conference.callId.prefix(8)) (owns the audio: \(audioCallUuid == keep))")
        for uuid in merged where uuid != keep {
            connection.log("Merge done: closing merged call \(callIdByUuid[uuid].map { String($0.prefix(8)) } ?? "?") here; the audio stays")
            end(uuid, reason: .remoteEnded)
        }
    }

    /// A call this iPhone never presented (carried on the Redmi only) leaves an entry here when it
    /// ends while another call is the primary one, and that entry kept hasCalls true - and with it
    /// the network link open in the background - after the last call. The list is the authority.
    private func forgetUnpresentedCalls(listed: Set<String>, primaryId: String?) {
        let stale = states.keys.filter { uuid in
            if incoming.contains(uuid) || outgoing.contains(uuid) || uuid == pendingOutgoing { return false }
            guard let rawId = callIdByUuid[uuid] else { return false }
            return !listed.contains(rawId) && rawId != primaryId
        }
        if stale.isEmpty { return }
        GatewayConnection.shared.log("Calls list: forgetting \(stale.count) call(s) this iPhone never presented")
        for uuid in stale { cleanup(uuid) }
    }

    /// The Redmi resumed the last call on hold by itself (the other call ended). CallKit still shows
    /// it held until it is told, and with only a held call CallKit lets the audio session go.
    ///
    /// Only for a single remaining call that the Redmi reports active, and not right after a hold was
    /// asked for here: a list sent before that hold took effect must not undo it.
    private func resumeInCallKitIfRedmiDid(_ byId: [String: GatewayCallInfo]) {
        // A call being added: the Redmi lists the current call as active until its dial goes out, and
        // resuming it here would undo the hold that keeps CallKit from ending it.
        if pendingOutgoing != nil { return }
        let presented = incoming.union(outgoing).filter { states[$0] != "ringing" && $0 != pendingOutgoing }
        if presented.count != 1 { return }
        guard let uuid = presented.first, let rawId = callIdByUuid[uuid], byId[rawId]?.state == "active",
              callController.callObserver.calls.contains(where: { $0.uuid == uuid && $0.isOnHold && !$0.hasEnded })
        else { return }
        if callKitOnlyHoldChanges.contains(uuid) { return }
        if let asked = holdRequestedAt[uuid], Date().timeIntervalSince(asked) < 5 {
            GatewayConnection.shared.log("Call \(rawId.prefix(8)) is active on the Redmi but held in CallKit; a hold was just asked for, left as it is")
            return
        }
        GatewayConnection.shared.log("The Redmi resumed call \(rawId.prefix(8)); showing it as resumed in CallKit")
        callKitOnlyHoldChanges.insert(uuid)
        callController.request(CXTransaction(action: CXSetHeldCallAction(call: uuid, onHold: false))) { error in
            guard error != nil else { return }
            Task { @MainActor in
                let code = error.map { ($0 as NSError).code } ?? -1
                CallKitManager.shared.callKitOnlyHoldChanges.remove(uuid)
                GatewayConnection.shared.log("CallKit refused to show the resumed call: \(code)")
            }
        }
    }

    private func conferenceUpdate() -> CXCallUpdate {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: "Konferans")
        update.localizedCallerName = "Konferans"
        update.hasVideo = false
        update.supportsDTMF = true
        update.supportsHolding = true
        update.supportsGrouping = true
        update.supportsUngrouping = false
        return update
    }

    /// The two calls the Redmi lists outside a conference, one active and one on hold.
    private func activeAndHeldCalls() -> (active: GatewayCallInfo, held: GatewayCallInfo)? {
        let calls = GatewayConnection.shared.calls.filter { !$0.isWaiting && !$0.isConferenceChild }
        guard calls.count == 2,
              let active = calls.first(where: { $0.state == "active" }),
              let held = calls.first(where: { $0.state == "holding" }) else { return nil }
        return (active: active, held: held)
    }

    /// The CallKit call this iPhone shows for a Redmi call id, if any.
    private func presentedUuid(for rawId: String) -> UUID? {
        guard let uuid = uuidByCallId[rawId], incoming.contains(uuid) || outgoing.contains(uuid) else { return nil }
        return uuid
    }

    /// "Değiştir": the active call goes on hold and the held one resumes.
    ///
    /// One CallKit transaction with both hold changes, so the system call screen agrees; the provider
    /// sends hold_call for each. A call carried on the Redmi only has no CallKit call, and then the
    /// Redmi swaps by itself.
    func swapCalls() {
        let connection = GatewayConnection.shared
        if connection.callsMerging {
            connection.log("Swap refused: the calls are being merged")
            return
        }
        guard let pair = activeAndHeldCalls() else {
            connection.log("Swap refused: the Redmi does not list one active and one held call")
            connection.lastCallError = "Değiştirilecek bekleyen çağrı yok."
            return
        }
        guard let activeUuid = presentedUuid(for: pair.active.callId),
              let heldUuid = presentedUuid(for: pair.held.callId) else {
            connection.log("Swap: not both calls are in CallKit here; asking the Redmi to swap")
            connection.send(["type": "swap_calls"])
            return
        }
        connection.log("Swap: holding \(pair.active.callId.prefix(8)) and resuming \(pair.held.callId.prefix(8))")
        let actions: [CXAction] = [
            CXSetHeldCallAction(call: activeUuid, onHold: true),
            CXSetHeldCallAction(call: heldUuid, onHold: false)
        ]
        callController.request(CXTransaction(actions: actions)) { error in
            guard error != nil else { return }
            Task { @MainActor in
                let code = error.map { ($0 as NSError).code } ?? -1
                GatewayConnection.shared.log("CallKit refused the swap (\(code)); asking the Redmi to swap")
                GatewayConnection.shared.send(["type": "swap_calls"])
            }
        }
    }

    /// "Birleştir": CallKit groups the two calls and the provider sends merge_calls. The network
    /// needs about 20 s to build the conference, which then replaces both calls (finishMerge).
    func mergeCalls() {
        let connection = GatewayConnection.shared
        if connection.callsMerging {
            connection.log("Merge refused: the calls are already being merged")
            return
        }
        guard let pair = activeAndHeldCalls() else {
            connection.log("Merge refused: the Redmi does not list one active and one held call")
            connection.lastCallError = "Birleştirilecek iki çağrı yok."
            return
        }
        guard let activeUuid = presentedUuid(for: pair.active.callId),
              let heldUuid = presentedUuid(for: pair.held.callId) else {
            connection.log("Merge: not both calls are in CallKit here; asking the Redmi to merge")
            connection.send(["type": "merge_calls"])
            return
        }
        connection.log("Merge: grouping \(pair.active.callId.prefix(8)) with \(pair.held.callId.prefix(8)) in CallKit")
        mergeAskedAt = Date()
        let action = CXSetGroupCallAction(call: activeUuid, callUUIDToGroupWith: heldUuid)
        callController.request(CXTransaction(action: action)) { error in
            guard error != nil else { return }
            Task { @MainActor in
                let code = error.map { ($0 as NSError).code } ?? -1
                GatewayConnection.shared.log("CallKit refused the merge (\(code)); asking the Redmi to merge")
                GatewayConnection.shared.send(["type": "merge_calls"])
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
        ringConfirmed.removeAll()
        connectedOutgoing.removeAll()
        pendingOutgoing = nil
        callIdsBeforeDial = []
        callKitOnlyHoldChanges.removeAll()
        heldForAddedCall.removeAll()
        mergeAskedAt = nil
        holdRequestedAt.removeAll()
        answerHoldingCurrent.removeAll()
        holdAnswerAt.removeAll()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let connection = GatewayConnection.shared
        let uuid = action.callUUID
        let rawId = callIdByUuid[uuid] ?? ""
        // "Beklet ve yanıtla" from the app asks for it explicitly, even when the current call has no
        // CallKit call here (carried on the Redmi).
        let askedToHold = answerHoldingCurrent.remove(uuid) != nil
        guard !rawId.isEmpty, states[uuid] == "ringing",
              connection.isAuthenticated || BLECentralManager.shared.isReady else {
            // This refusal used to leave no trace. The answer itself is not kept for later: an
            // answer that lands after the person gave up would connect a call nobody holds.
            connection.log("Answer refused here: callId known=\(!rawId.isEmpty) state=\(states[uuid] ?? "none") link=\(connection.isAuthenticated) bluetooth=\(BLECentralManager.shared.isReady)")
            connection.lastCallError = "Redmi'ye ulaşılamadı; arama Redmi'de çalıyor."
            if !rawId.isEmpty, states[uuid] == "ringing" {
                // The call still rings on the Redmi: bring the link back so the next tap can reach it.
                connection.reconnectNow(reason: "answer without a link")
            }
            action.fail()
            return
        }
        // A waiting call answered while another call of this manager stays live: the system's "Hold &
        // Accept" (CXSetHeldCallAction on the current call, then this) or "Beklet ve yanıtla" here.
        // The Redmi then answers it without ending the current call, which Telecom puts on hold.
        // After "End & Accept" CallKit has already ended the current call (CXEndCallAction), so no
        // other call is live and the answer keeps its old meaning: end the current call, then answer.
        let othersLive = incoming.union(outgoing).contains { other in
            other != uuid && other != pendingOutgoing && states[other] != nil && states[other] != "ringing"
        }
        let holdCurrent = askedToHold || othersLive
        if holdCurrent || rawId == connection.waitingCall?.rawCallId {
            connection.log("Answering call \(rawId.prefix(8)) while another call is in progress: \(holdCurrent ? "the current call goes on hold" : "the current call was ended first") (asked=\(askedToHold) othersLive=\(othersLive))")
        }
        // The audio is device-level: with the current call kept on hold, the session already running
        // here carries the answered call too. A second audio_start would make the Redmi open a new
        // session under the running engine (rule 6.10).
        let keepsAudioSession = holdCurrent && audioRequested && connection.isAuthenticated
        answeredHere.insert(uuid)
        audioCallUuid = uuid
        if holdCurrent { holdAnswerAt[uuid] = Date() }
        // Keep the ring-time origin when there is one, so the whole path reads on one timeline.
        if audioPathStartedAt == nil { audioPathStartedAt = Date() }
        audioPathLog("answer tapped")
        connection.sendAnswer(callId: rawId, holdCurrent: holdCurrent)
        if keepsAudioSession {
            audioPathLog("answered with the current call on hold; the running audio session carries this call, no new audio_start")
        } else if connection.isAuthenticated {
            audioRequested = true
            connection.sendAudioStart()
            audioPathLog("audio_start sent")
        } else {
            // Answered over Bluetooth; audio follows as soon as the network link is back.
            connection.reconnectNow(reason: "answered on iPhone")
        }
        // The answer is the one moment the socket is most likely down (iOS suspends the app while
        // ringing), and on 22.10.2026 the person answered on the iPhone and the whole conversation
        // stayed on the Redmi: the audio request was sent once, into a link that was not there, and
        // never repeated. This keeps asking until the audio is actually here.
        startAudioRetry(uuid: uuid)
        // After audio_start, never before: the Redmi's arm clock starts with that frame, and the
        // ring-time warm-up has long finished for a call that rang here, so this is a no-op on the
        // critical path. Starting the engine still waits for CallKit either way.
        audioPathLog("warmUp called")
        AudioEngine.shared.warmUp()
        audioPathLog("warmUp finished")
        action.fulfill()
    }

    /// Repeats the audio request after an answer until the audio actually moves here.
    ///
    /// Bounded on purpose: six attempts two seconds apart covers the Redmi's eight second arm window
    /// twice, and a call that never accepts the transfer must not leave a timer running for its whole
    /// length. Every attempt is logged so the phone's own log answers "was it tried at all".
    ///
    /// A tick without the network link, or before the link has reported the call, waits instead of
    /// ending the retry: an answer sent over Bluetooth while the app was suspended reaches this loop
    /// before the socket is back, and the old guard ended it on the first tick, for good. Waiting is
    /// bounded too (60 s) and does not use up the six attempts; when it runs out, the link-up path
    /// (handleGatewayLinkRestored) still asks for the audio once.
    private func startAudioRetry(uuid: UUID) {
        audioRetryTask?.cancel()
        audioRetryTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var attempt = 0
            var waitingTicks = 0
            while attempt < 6 {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { return }
                let connection = GatewayConnection.shared
                if !self.answeredHere.contains(uuid) || self.states[uuid] == nil {
                    connection.log("Audio retry ended: the call is no longer answered here")
                    return
                }
                if connection.audioLocation == .iphone {
                    connection.log("Audio retry ended: the audio is on this iPhone")
                    return
                }
                if !connection.isAuthenticated || connection.currentCall == nil {
                    waitingTicks += 1
                    if waitingTicks == 1 || waitingTicks % 5 == 0 {
                        connection.log("Audio is still on the Redmi; waiting for the link to carry the call (\(waitingTicks * 2) s)")
                    }
                    if waitingTicks >= 30 {
                        connection.log("Audio retry ended: no link carrying the call for 60 s; the link-up path asks again")
                        return
                    }
                    continue
                }
                if AudioEngine.shared.isWaitingForInput {
                    // The hold-up is this iPhone's microphone, not the Redmi. Every extra audio_start
                    // made the Redmi open a new session and the waiting start chase it (25.10.2026).
                    connection.log("Audio retry: this iPhone's microphone is not ready yet; not asking the Redmi again")
                    continue
                }
                attempt += 1
                connection.log("Audio is still on the Redmi; asking again (attempt \(attempt))")
                self.audioRequested = true
                connection.sendAudioStart()
            }
        }
    }

    /// The Redmi refused audio_start (no_call or audio_busy).
    ///
    /// That request is over, so the flag that stops duplicates is cleared. Left set, it made every
    /// later request - the next call event, the retry, "iPhone'a al" - look like a repeat of one the
    /// Redmi had already turned down, and nothing asked again. The duplicate protection for a request
    /// still waiting for its answer is unchanged.
    func audioStartRefused(code: String) {
        audioPathLog("audio_start refused by the Redmi (\(code)); a later request may ask again")
        audioRequested = false
    }

    /// The audio is confirmed on this iPhone: stop asking.
    func audioMovedToIPhone() {
        audioRetryTask?.cancel()
        audioRetryTask = nil
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        let rawId = callIdByUuid[uuid] ?? ""
        let state = states[uuid] ?? "active"
        let connection = GatewayConnection.shared
        // Ends asked by CallKit itself are not told apart from a tap; the line says which call it was.
        connection.log("CallKit end for call \(rawId.prefix(8)) (\(state), \(incoming.union(outgoing).count) call(s) here)")
        if !rawId.isEmpty, !rawId.hasPrefix("outgoing_") {
            if state == "ringing" {
                connection.sendReject(callId: rawId)
            } else {
                connection.sendHangup(callId: rawId)
            }
            endedCallIds[rawId] = Date()
        } else if rawId.hasPrefix("outgoing_") {
            // The Redmi has not named this dial yet. Alone, the current call is this dial; while a
            // call is being added, the current call is the one before it and must stay.
            let before = callIdsBeforeDial
            if before.isEmpty, let call = connection.currentCall {
                connection.sendHangup(callId: call.rawCallId)
            } else if let added = connection.calls.first(where: { !before.contains($0.callId) }) {
                connection.sendHangup(callId: added.callId)
            } else {
                connection.log("Added call ended before the Redmi named it; the call before it is left alone")
                cleanup(uuid)
                action.fulfill()
                resumeCallHeldForAddedCall()
                return
            }
        }
        cleanup(uuid)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
        // A started call gets none of the capabilities an incoming call is reported with. Without
        // holding, CallKit ended the call on hold the moment an added call connected (twice on
        // 25.10.2026, build 146); without grouping it cannot merge.
        let capabilities = CXCallUpdate()
        capabilities.hasVideo = false
        capabilities.supportsDTMF = true
        capabilities.supportsHolding = true
        capabilities.supportsGrouping = true
        capabilities.supportsUngrouping = false
        provider.reportCall(with: action.callUUID, updated: capabilities)
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

    /// Hold or resume, from the system call screen, from "Değiştir", or from CallKit itself: it holds
    /// the current call when a second one is dialled ("Çağrı ekle") or a waiting call is taken with
    /// "Beklet ve yanıtla". The Redmi's Telecom also holds on its own then; a repeated hold is
    /// harmless. Nothing here touches the audio: the session runs on through a hold.
    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        let uuid = action.callUUID
        let rawId = callIdByUuid[uuid] ?? ""
        let connection = GatewayConnection.shared
        let change = action.isOnHold ? "hold" : "resume"
        if callKitOnlyHoldChanges.remove(uuid) != nil {
            // Only shows in CallKit what the Redmi already did; sending it back would be an echo.
            connection.log("CallKit \(change) for call \(rawId.prefix(8)) mirrors the Redmi; not sent")
        } else if action.isOnHold, heldForAddedCall.remove(uuid) != nil {
            connection.log("CallKit hold for call \(rawId.prefix(8)) while a call is added; the Redmi holds it when it dials, not sent")
        } else if connection.callsMerging || mergeAskedAt.map({ Date().timeIntervalSince($0) < 3 }) == true {
            // CallKit may change the hold of the calls it groups. Sent before merge_calls reaches the
            // Redmi, a resume would swap the calls there instead of merging them.
            connection.log("CallKit \(change) for call \(rawId.prefix(8)) during a merge; left to the merge, not sent")
        } else if rawId.isEmpty || rawId == "gateway_call" || rawId.hasPrefix("outgoing_") {
            // The Redmi has not named this call yet and ignores hold_call for an unknown id.
            connection.log("CallKit \(change) for a call the Redmi has not named yet; left to the Redmi")
        } else {
            holdRequestedAt[uuid] = Date()
            connection.log("CallKit \(change) for call \(rawId.prefix(8)); sending hold_call\(connection.isAuthenticated ? "" : " (no link to the Redmi; it will not arrive)")")
            connection.send([
                "type": "hold_call",
                "callId": rawId,
                "hold": action.isOnHold
            ])
        }
        action.fulfill()
        if !action.isOnHold { startAudioIfResumeLeftItOff(uuid) }
    }

    /// A resume after every call here was on hold. CallKit deactivated the session for the hold, and
    /// the activation that should follow the resume does not always come (Apple DTS, forum 749202;
    /// Linphone and Twilio start their audio from the resume itself). If it has not come a second
    /// later, the session is taken as active and the call's audio starts as on didActivate:
    /// the engine on its endpoint, or a new audio_start if the Redmi took the audio back meanwhile.
    /// One task at a time; didActivate and stopAudio cancel it.
    private func startAudioIfResumeLeftItOff(_ uuid: UUID) {
        guard !audioActivated else { return }
        resumeAudioTask?.cancel()
        resumeAudioTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.resumeAudioTask = nil
            if self.audioActivated { return }
            guard self.callController.callObserver.calls.contains(where: { $0.uuid == uuid && !$0.isOnHold && !$0.hasEnded }) else {
                self.audioPathLog("no didActivate after the resume, but the call is no longer resumed in CallKit; nothing started")
                return
            }
            if self.audioCallUuid.map({ self.states[$0] == nil }) ?? true { self.audioCallUuid = uuid }
            guard let owner = self.audioCallUuid, self.isAudioEligible(owner) else {
                self.audioPathLog("no didActivate after the resume, and no call here may carry the audio")
                return
            }
            self.audioPathLog("no didActivate 1 s after the resume; taking the session as active (requested=\(self.audioRequested), endpoint=\(self.endpoint != nil))")
            self.audioActivated = true
            self.maybeStartAudio(for: owner)
        }
    }

    /// Merge, from "Birleştir" or the system call screen. The network takes about 20 s to build the
    /// conference; the calls list then replaces both calls with it (finishMerge).
    func provider(_ provider: CXProvider, perform action: CXSetGroupCallAction) {
        let connection = GatewayConnection.shared
        guard let other = action.callUUIDToGroupWith else {
            // Ungrouping: the Redmi cannot split a conference (supportsUngrouping is false).
            connection.log("CallKit asked to take a call out of its group; not supported, refused")
            action.fail()
            return
        }
        let first = callIdByUuid[action.callUUID].map { String($0.prefix(8)) } ?? "?"
        let second = callIdByUuid[other].map { String($0.prefix(8)) } ?? "?"
        if connection.callsMerging {
            connection.log("Merge of \(first) + \(second) asked while the Redmi is already merging; not asked again")
        } else {
            mergeAskedAt = Date()
            connection.log("Merge of \(first) + \(second) requested; sending merge_calls\(connection.isAuthenticated ? "" : " (no link to the Redmi; it will not arrive)")")
            connection.send([
                "type": "merge_calls"
            ])
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audioActivated = true
        resumeAudioTask?.cancel()
        resumeAudioTask = nil
        audioPathLog("didActivate received")
        if !AudioEngine.shared.isSessionPrepared && !AudioEngine.shared.isRunning {
            AudioEngine.shared.prepareSession()
        }
        for uuid in answeredHere.union(outgoing) { maybeStartAudio(for: uuid) }
        if let endpoint, let uuid = audioCallUuid, isAudioEligible(uuid) { startEngine(endpoint) }
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        let here = incoming.union(outgoing).count
        audioActivated = false
        // Every call left here is on hold: the other call ended a moment before the Redmi resumed this
        // one (it waits 0.8 s), or a hold from the system screen. The Redmi keeps relaying for the
        // session, so only the engine pauses and the next didActivate starts it on the same endpoint.
        // Stopping the session too sent the conversation back to the Redmi in the pocket and then
        // asked for it again. A pause longer than 1.8 s makes the Redmi take the audio back by itself.
        if here > 0, audioRequested, endpoint != nil, let next = audioSuccessor() {
            audioCallUuid = next
            audioPathLog("didDeactivate received (\(here) call(s) here, none active); pausing the engine, the Redmi keeps the session")
            resumeAudioTask?.cancel()
            resumeAudioTask = nil
            AudioEngine.shared.pause()
            runningEndpoint = nil
            return
        }
        audioPathLog("didDeactivate received (\(here) call(s) here); stopping call audio")
        stopAudio()
    }
}
