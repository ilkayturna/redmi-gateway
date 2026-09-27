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
    private var autoRetried: Set<UUID> = []
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

    override private init() {
        let config = CXProviderConfiguration(localizedName: "Telefon Geçidi")
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
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
        autoRetried.remove(uuid)
        if pendingOutgoing == uuid { pendingOutgoing = nil }
        if audioCallUuid == uuid { stopAudio() }
    }

    private func end(_ uuid: UUID, reason: CXCallEndedReason) {
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

    // MARK: - Audio

    private func isAudioEligible(_ uuid: UUID) -> Bool {
        switch states[uuid] {
        case "active": return incoming.contains(uuid) ? answeredHere.contains(uuid) : outgoing.contains(uuid)
        case "dialing", "connecting": return outgoing.contains(uuid) && !pickups.contains(uuid)
        default: return false
        }
    }

    private func maybeStartAudio(for uuid: UUID) {
        guard isAudioEligible(uuid), GatewayConnection.shared.isAuthenticated else { return }
        audioCallUuid = uuid
        if !audioRequested {
            audioRequested = true
            GatewayConnection.shared.sendAudioStart()
        }
        if audioActivated, let endpoint { startEngine(endpoint) }
    }

    func handleAudioReady(session: UInt32, port: UInt16, key: Data?) {
        guard let key else {
            GatewayConnection.shared.log("Refused unsealed call audio")
            return
        }
        let ready = (session: session, port: port, key: key)
        endpoint = ready
        if audioActivated, let uuid = audioCallUuid, isAudioEligible(uuid) {
            startEngine(ready)
        }
    }

    func recoverCallAudio() {
        guard audioActivated, let uuid = audioCallUuid, isAudioEligible(uuid), let endpoint,
              GatewayConnection.shared.isAuthenticated else { return }
        startEngine(endpoint, force: true)
    }

    private func startEngine(_ endpoint: (session: UInt32, port: UInt16, key: Data), force: Bool = false) {
        let host = GatewayConnection.shared.connectedHost
        if !force, AudioEngine.shared.isRunning, let running = runningEndpoint,
           running.host == host, running.session == endpoint.session, running.port == endpoint.port {
            return
        }
        AudioEngine.shared.start(host: host, port: endpoint.port, session: endpoint.session, key: endpoint.key)
        if AudioEngine.shared.isRunning {
            runningEndpoint = (host: host, session: endpoint.session, port: endpoint.port)
        } else {
            runningEndpoint = nil
        }
    }

    private func stopAudio() {
        AudioEngine.shared.stop()
        runningEndpoint = nil
        endpoint = nil
        if audioRequested { GatewayConnection.shared.sendAudioStop() }
        audioRequested = false
        audioCallUuid = nil
    }

    /// The Redmi took the conversation back (its decision, or the person's on either phone).
    func handleAudioReturnedToRedmi(reason: String) {
        AudioEngine.shared.stop(preservingControls: true)
        runningEndpoint = nil
        endpoint = nil
        audioRequested = false
        guard let uuid = audioCallUuid, states[uuid] == "active" else { return }
        if reason == "manual_redmi" {
            // The person took the call on the Redmi; "iPhone'a al" brings it back.
            if let rawId = callIdByUuid[uuid] { endedCallIds[rawId] = Date() }
            end(uuid, reason: .answeredElsewhere)
            return
        }
        // One quiet retry for a network hiccup; after that the person decides.
        if Self.transientFallbacks.contains(reason), audioActivated, !autoRetried.contains(uuid) {
            autoRetried.insert(uuid)
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
    func takeCallOnIPhone() {
        let connection = GatewayConnection.shared
        guard let call = connection.currentCall, call.state == .active, connection.isAuthenticated else { return }
        if let uuid = currentCallUuid {
            audioCallUuid = uuid
            audioRequested = true
            connection.sendAudioStart()
            return
        }
        AudioEngine.shared.prepareSession()
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
                CallKitManager.shared.cleanup(uuid)
                GatewayConnection.shared.lastCallError = "Görüşme iPhone'a alınamadı."
            }
        }
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
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        return update
    }

    private func reportIncoming(uuid: UUID, rawId: String, number: String?) {
        AudioEngine.shared.prepareSession()
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
        connection.sendAnswer(callId: rawId)
        if connection.isAuthenticated {
            audioRequested = true
            connection.sendAudioStart()
        } else {
            // Answered over Bluetooth; audio follows as soon as the network link is back.
            connection.reconnectNow(reason: "answered on iPhone")
        }
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

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audioActivated = true
        if !AudioEngine.shared.isSessionPrepared && !AudioEngine.shared.isRunning {
            AudioEngine.shared.prepareSession()
        }
        for uuid in answeredHere.union(outgoing) { maybeStartAudio(for: uuid) }
        if let endpoint, let uuid = audioCallUuid, isAudioEligible(uuid) { startEngine(endpoint) }
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        audioActivated = false
        stopAudio()
    }
}
