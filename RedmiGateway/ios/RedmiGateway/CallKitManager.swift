import Foundation
import CallKit
import AVFoundation

/**
 * CallKitManager integrates Redmi Gateway's GSM call signaling with iOS native CallKit.
 *
 * Responsibilities:
 * 1. Bidirectional mapping: Android callId (String) <-> iOS CallKit UUID.
 * 2. Deduplication of incoming RINGING events to prevent duplicate CallKit prompts.
 * 3. Answering incoming calls via CallKit UI -> sends "answer" to Redmi.
 * 4. Ending calls via CallKit UI -> sends "reject" if ringing, "hangup" if active/dialing.
 * 5. Outgoing calls via CallKit -> triggers CXStartCallAction -> sends "dial" to Redmi.
 * 6. Reflecting state transitions (ringing -> active -> disconnected) in CallKit.
 */
@MainActor
public class CallKitManager: NSObject, CXProviderDelegate {
    public static let shared = CallKitManager()
    
    private let provider: CXProvider
    private let callController = CXCallController()
    
    // Mapping Android callId (String) <-> iOS CallKit UUID
    private var callIdToUuidMap: [String: UUID] = [:]
    private var uuidToCallIdMap: [UUID: String] = [:]
    private var callStates: [UUID: String] = [:]
    private var outgoingUuids: Set<UUID> = []
    private var pendingOutgoingUuid: UUID?
    private var answeredOnIPhone: Set<UUID> = []
    private var connectedOutgoing: Set<UUID> = []
    private var callKitAudioActivated = false
    private var audioStartRequested = false
    private var audioCallUuid: UUID?
    private var pendingAudioEndpoint: (session: UInt32, port: UInt16)?
    private var runningAudioEndpoint: (host: String, session: UInt32, port: UInt16)?
    
    // Set of call UUIDs that have already been reported as incoming to CallKit
    private var reportedIncomingUuids: Set<UUID> = []
    
    override private init() {
        let config = CXProviderConfiguration(localizedName: "Telefon Geçidi")
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.phoneNumber]
        
        self.provider = CXProvider(configuration: config)
        super.init()
        self.provider.setDelegate(self, queue: DispatchQueue.main)
        AudioEngine.shared.prepareSession()
        AudioEngine.shared.onStatus = { status in
            Task { @MainActor in GatewayConnection.shared.audioStatus = status }
        }
        AudioEngine.shared.onNeedsRestart = { [weak self] in
            Task { @MainActor [weak self] in self?.recoverCallAudio() }
        }
        
        setupGatewayObserver()
    }
    
    private func setupGatewayObserver() {
        GatewayConnection.shared.onCallEvent = { [weak self] event in
            self?.handleIncomingGatewayCallEvent(event)
        }
    }
    
    // MARK: - Incoming Gateway Event Processing
    
    public func handleIncomingGatewayCallEvent(_ event: CallEventMessage) {
        let state = event.state.lowercased()
        guard state != "idle" else { return }
        let rawId = (event.callId?.isEmpty == false) ? event.callId! : "gateway_call"
        let number = (event.number?.isEmpty == false) ? event.number! : "Unknown Cellular"
        
        let uuid = uuidForGatewayEvent(rawId: rawId, state: state)
        let previousState = callStates[uuid]
        callStates[uuid] = state
        
        GatewayConnection.shared.log("CallKit process event: [\(state)] id=\(rawId) uuid=\(uuid.uuidString.prefix(8)) prev=\(previousState ?? "nil")")
        
        switch state {
        case "ringing":
            // Requirement 10: Deduplication - prevent duplicate CallKit prompts for repeated ringing packets
            if !reportedIncomingUuids.contains(uuid) {
                reportedIncomingUuids.insert(uuid)
                reportIncomingCall(uuid: uuid, phoneNumber: number)
            } else {
                GatewayConnection.shared.log("CallKit: Suppressing duplicate ringing event for uuid=\(uuid.uuidString.prefix(8))")
            }
            
        case "active":
            if outgoingUuids.contains(uuid) {
                if connectedOutgoing.insert(uuid).inserted { reportCallConnected(uuid: uuid) }
                maybeStartAudio(for: uuid)
            } else if reportedIncomingUuids.contains(uuid) {
                if answeredOnIPhone.contains(uuid) {
                    maybeStartAudio(for: uuid)
                } else {
                    // Answered directly on Redmi: stop the iPhone ringtone without ending GSM.
                    reportCallEnded(uuid: uuid, reason: .answeredElsewhere)
                    cleanupCall(uuid: uuid, rawId: rawId)
                }
            }
            
        case "disconnected":
            if outgoingUuids.contains(uuid) || reportedIncomingUuids.contains(uuid) {
                reportCallEnded(uuid: uuid, reason: .remoteEnded)
            }
            cleanupCall(uuid: uuid, rawId: rawId)
            
        case "dialing", "connecting":
            if outgoingUuids.contains(uuid) {
                if previousState != "dialing" && previousState != "connecting" {
                    provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
                }
                maybeStartAudio(for: uuid)
            }
            
        default:
            break
        }
    }

    private func uuidForGatewayEvent(rawId: String, state: String) -> UUID {
        if let existing = callIdToUuidMap[rawId] {
            return existing
        }
        if let pending = pendingOutgoingUuid,
           state == "dialing" || state == "connecting" || state == "active" {
            pendingOutgoingUuid = nil
            if let oldTempId = uuidToCallIdMap[pending] {
                callIdToUuidMap.removeValue(forKey: oldTempId)
            }
            callIdToUuidMap[rawId] = pending
            uuidToCallIdMap[pending] = rawId
            return pending
        }
        return getOrCreateUuid(for: rawId)
    }
    
    private func getOrCreateUuid(for rawId: String) -> UUID {
        if let existing = callIdToUuidMap[rawId] {
            return existing
        }
        let newUuid = UUID(uuidString: rawId) ?? UUID()
        callIdToUuidMap[rawId] = newUuid
        uuidToCallIdMap[newUuid] = rawId
        return newUuid
    }
    
    private func cleanupCall(uuid: UUID, rawId: String) {
        callIdToUuidMap = callIdToUuidMap.filter { $0.value != uuid }
        uuidToCallIdMap.removeValue(forKey: uuid)
        callStates.removeValue(forKey: uuid)
        reportedIncomingUuids.remove(uuid)
        outgoingUuids.remove(uuid)
        answeredOnIPhone.remove(uuid)
        connectedOutgoing.remove(uuid)
        pendingAudioEndpoint = nil
        if pendingOutgoingUuid == uuid { pendingOutgoingUuid = nil }
        if audioCallUuid == uuid { stopAudio() }
    }

    private func isAudioEligible(for uuid: UUID) -> Bool {
        let state = callStates[uuid]
        if state == "active" { return true }
        if outgoingUuids.contains(uuid) && (state == "dialing" || state == "connecting") {
            return true
        }
        return false
    }

    private func maybeStartAudio(for uuid: UUID) {
        guard isAudioEligible(for: uuid),
              GatewayConnection.shared.state == .authenticated else { return }
        audioCallUuid = uuid
        if !audioStartRequested {
            audioStartRequested = true
            GatewayConnection.shared.audioStatus = "Görüşme sesi bağlanıyor"
            GatewayConnection.shared.sendAudioStart()
        }
        if callKitAudioActivated, let endpoint = pendingAudioEndpoint {
            startAudioEngine(endpoint: endpoint)
        }
    }

    public func handleAudioReady(session: UInt32, port: UInt16) {
        let endpoint = (session: session, port: port)
        pendingAudioEndpoint = endpoint
        GatewayConnection.shared.log("Audio endpoint ready: session=\(session), port=\(port)")
        if callKitAudioActivated, let uuid = audioCallUuid, isAudioEligible(for: uuid) {
            startAudioEngine(endpoint: endpoint)
        }
    }

    public func recoverCallAudio() {
        guard callKitAudioActivated,
              let uuid = audioCallUuid,
              isAudioEligible(for: uuid),
              let endpoint = pendingAudioEndpoint,
              GatewayConnection.shared.state == .authenticated else { return }
        GatewayConnection.shared.log("Rebuilding iPhone call audio after route or microphone interruption")
        startAudioEngine(endpoint: endpoint, force: true)
    }

    private func startAudioEngine(endpoint: (session: UInt32, port: UInt16), force: Bool = false) {
        let host = GatewayConnection.shared.connectedHost
        if !force, AudioEngine.shared.isRunning,
           let running = runningAudioEndpoint,
           running.host == host, running.session == endpoint.session,
           running.port == endpoint.port {
            return
        }
        AudioEngine.shared.start(
            host: host,
            port: endpoint.port,
            session: endpoint.session
        )
        runningAudioEndpoint = AudioEngine.shared.isRunning
            ? (host: host, session: endpoint.session, port: endpoint.port) : nil
    }

    private func stopAudio() {
        AudioEngine.shared.stop()
        runningAudioEndpoint = nil
        pendingAudioEndpoint = nil
        if audioStartRequested { GatewayConnection.shared.sendAudioStop() }
        audioStartRequested = false
        audioCallUuid = nil
        GatewayConnection.shared.audioStatus = "Görüşme sesi Redmi'de"
    }

    public func reportCurrentCallFailedOnNetworkDrop() {
        let activeUuids = Array(callStates.keys)
        for uuid in activeUuids {
            let rawId = uuidToCallIdMap[uuid] ?? ""
            reportCallEnded(uuid: uuid, reason: .failed)
            cleanupCall(uuid: uuid, rawId: rawId)
        }
        stopAudio()
    }

    public func handleDialFailed() {
        if let uuid = pendingOutgoingUuid {
            let rawId = uuidToCallIdMap[uuid] ?? ""
            reportCallEnded(uuid: uuid, reason: .failed)
            cleanupCall(uuid: uuid, rawId: rawId)
            pendingOutgoingUuid = nil
        }
        for uuid in outgoingUuids where callStates[uuid] == "dialing" {
            let rawId = uuidToCallIdMap[uuid] ?? ""
            reportCallEnded(uuid: uuid, reason: .failed)
            cleanupCall(uuid: uuid, rawId: rawId)
        }
        stopAudio()
    }
    
    // MARK: - CallKit Reporting
    
    private func maskPhoneNumber(_ number: String) -> String {
        let clean = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count > 4 else { return "***" }
        let prefixLen = min(3, clean.count / 3)
        let suffixLen = min(2, clean.count / 3)
        let prefix = clean.prefix(prefixLen)
        let suffix = clean.suffix(suffixLen)
        return "\(prefix)***\(suffix)"
    }

    private func reportIncomingCall(uuid: UUID, phoneNumber: String) {
        AudioEngine.shared.prepareSession()
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .phoneNumber, value: phoneNumber)
        update.localizedCallerName = phoneNumber
        update.hasVideo = false
        update.supportsDTMF = true
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        
        provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            Task { @MainActor [weak self] in
                if let error = error {
                    GatewayConnection.shared.log("CallKit report incoming error: \(error.localizedDescription)")
                    self?.cleanupCall(uuid: uuid, rawId: self?.uuidToCallIdMap[uuid] ?? "")
                } else {
                    let masked = self?.maskPhoneNumber(phoneNumber) ?? "***"
                    GatewayConnection.shared.log("CallKit incoming call UI presented for: \(masked)")
                }
            }
        }
    }
    
    private func reportCallConnected(uuid: UUID) {
        guard outgoingUuids.contains(uuid) else { return }
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
        GatewayConnection.shared.log("CallKit outgoing call marked CONNECTED (uuid=\(uuid.uuidString.prefix(8)))")
    }
    
    private func reportCallEnded(uuid: UUID, reason: CXCallEndedReason) {
        provider.reportCall(with: uuid, endedAt: Date(), reason: reason)
        GatewayConnection.shared.log("CallKit call ended (uuid=\(uuid.uuidString.prefix(8)), reason=\(reason.rawValue))")
    }
    
    // MARK: - Outgoing Call Action (From iPhone Client)

    public func answerIncomingFromApp(callId: String) {
        guard let uuid = callIdToUuidMap[callId], reportedIncomingUuids.contains(uuid) else {
            GatewayConnection.shared.lastCallError = "Çağrı ekranı hazır değil; Redmi'den yanıtla."
            return
        }
        answeredOnIPhone.insert(uuid)
        callController.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { error in
            if let error {
                Task { @MainActor in
                    GatewayConnection.shared.lastCallError = "iPhone'da yanıtlanamadı: \(error.localizedDescription)"
                }
            }
        }
    }
    
    public func startOutgoingCall(phoneNumber: String) {
        GatewayConnection.shared.lastCallError = nil
        let cleanNumber = phoneNumber.replacingOccurrences(
            of: "[\\s().-]", with: "", options: .regularExpression
        )
        guard cleanNumber.range(of: "^[0-9*#+]{3,20}$", options: .regularExpression) != nil else {
            GatewayConnection.shared.lastCallError = "Geçerli bir telefon numarası gir."
            return
        }
        guard GatewayConnection.shared.state == .authenticated else {
            GatewayConnection.shared.lastCallError = "Redmi bağlantısı yok. Arama başlatılamadı."
            return
        }
        guard pendingOutgoingUuid == nil, GatewayConnection.shared.currentCall == nil else {
            GatewayConnection.shared.lastCallError = "Zaten bir arama sürüyor."
            return
        }
        AudioEngine.shared.prepareSession()
        
        let uuid = UUID()
        let tempRawId = "outgoing_\(uuid.uuidString.prefix(8))"
        callIdToUuidMap[tempRawId] = uuid
        uuidToCallIdMap[uuid] = tempRawId
        // Wait for Redmi's dialing event before requesting its audio endpoint. CallKit can
        // activate the iPhone session before the cellular dial command reaches Redmi.
        callStates[uuid] = "pending"
        outgoingUuids.insert(uuid)
        pendingOutgoingUuid = uuid
        
        let handle = CXHandle(type: .phoneNumber, value: cleanNumber)
        let action = CXStartCallAction(call: uuid, handle: handle)
        action.isVideo = false
        let transaction = CXTransaction(action: action)
        
        callController.request(transaction) { [weak self] error in
            Task { @MainActor [weak self] in
                if let error = error {
                    let nsError = error as NSError
                    GatewayConnection.shared.log("CallKit start call transaction failed: domain=\(nsError.domain) code=\(nsError.code)")
                    self?.cleanupCall(uuid: uuid, rawId: tempRawId)
                    // The user tapped Ara. Keep that action effective even if iOS
                    // refuses its native call screen; the GSM call remains on Redmi.
                    if GatewayConnection.shared.state == .authenticated {
                        GatewayConnection.shared.sendDial(number: cleanNumber)
                        GatewayConnection.shared.lastCallError = "iPhone arama ekranı açılamadı. Arama Redmi'de başlatılıyor; görüşmeyi Redmi'de yap."
                    } else {
                        GatewayConnection.shared.lastCallError = "Redmi bağlantısı koptu. Arama başlatılamadı."
                    }
                } else {
                    GatewayConnection.shared.log("CallKit start call accepted. Dialing on Redmi.")
                    GatewayConnection.shared.sendDial(number: cleanNumber)
                }
            }
        }
    }
    
    // MARK: - CXProviderDelegate Callbacks
    
    public func providerDidReset(_ provider: CXProvider) {
        GatewayConnection.shared.log("CallKit provider reset")
        stopAudio()
        callKitAudioActivated = false
        AudioEngine.shared.resetSessionPrepared()
        callIdToUuidMap.removeAll()
        uuidToCallIdMap.removeAll()
        callStates.removeAll()
        reportedIncomingUuids.removeAll()
        outgoingUuids.removeAll()
        answeredOnIPhone.removeAll()
        connectedOutgoing.removeAll()
        pendingOutgoingUuid = nil
    }
    
    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let uuid = action.callUUID
        let rawId = uuidToCallIdMap[uuid] ?? ""
        guard (GatewayConnection.shared.state == .authenticated || BLECentralManager.shared.isConnected),
              !rawId.isEmpty,
              callStates[uuid] == "ringing" else {
            GatewayConnection.shared.lastCallError = "Redmi bağlantısı yok; arama Redmi'de çalmaya devam ediyor."
            action.fail()
            return
        }
        GatewayConnection.shared.log("CallKit user ANSWERED call (uuid=\(uuid.uuidString.prefix(8)), callId=\(rawId))")
        
        // Signal Redmi Gateway to answer the cellular call
        answeredOnIPhone.insert(uuid)
        GatewayConnection.shared.sendAnswer(callId: rawId)
        audioCallUuid = uuid
        audioStartRequested = true
        GatewayConnection.shared.audioStatus = "Görüşme sesi bağlanıyor"
        GatewayConnection.shared.sendAudioStart()
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        let rawId = uuidToCallIdMap[uuid] ?? ""
        let currentState = callStates[uuid] ?? "active"
        
        GatewayConnection.shared.log("CallKit user ENDED call (state=\(currentState), uuid=\(uuid.uuidString.prefix(8)), callId=\(rawId))")
        
        if (GatewayConnection.shared.state == .authenticated || BLECentralManager.shared.isConnected) && !rawId.isEmpty {
            if currentState == "ringing" {
                // Reject incoming call
                GatewayConnection.shared.sendReject(callId: rawId)
            } else {
                // Hang up active, dialing, or connecting call
                GatewayConnection.shared.sendHangup(callId: rawId)
            }
        } else {
            GatewayConnection.shared.lastCallError = "Redmi bağlantısı yok; görüşmeyi Redmi'den sonlandır."
        }
        
        cleanupCall(uuid: uuid, rawId: rawId)
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        GatewayConnection.shared.log("CallKit CXStartCallAction fulfilled")
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        let isMuted = action.isMuted
        GatewayConnection.shared.log("CallKit CXSetMutedCallAction: isMuted=\(isMuted)")
        AudioEngine.shared.isMuted = isMuted
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        let digits = action.digits
        let uuid = action.callUUID
        let rawId = uuidToCallIdMap[uuid]
        GatewayConnection.shared.log("CallKit DTMF action")
        for digit in digits {
            GatewayConnection.shared.sendDtmf(digit: String(digit), callId: rawId)
        }
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        callKitAudioActivated = true
        if !AudioEngine.shared.isSessionPrepared && !AudioEngine.shared.isRunning {
            AudioEngine.shared.prepareSession()
        }
        GatewayConnection.shared.log("CallKit audio session activated")
        for uuid in answeredOnIPhone.union(outgoingUuids) {
            maybeStartAudio(for: uuid)
        }
        if let endpoint = pendingAudioEndpoint, let uuid = audioCallUuid, isAudioEligible(for: uuid) {
            startAudioEngine(endpoint: endpoint)
        }
    }
    
    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        callKitAudioActivated = false
        pendingAudioEndpoint = nil
        stopAudio()
        GatewayConnection.shared.log("CallKit audio session deactivated")
    }
}
