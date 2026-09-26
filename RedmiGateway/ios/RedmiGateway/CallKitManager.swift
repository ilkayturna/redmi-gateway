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
            if outgoingUuids.contains(uuid) && previousState != "dialing" && previousState != "connecting" {
                provider.reportOutgoingCall(with: uuid, startedConnectingAt: Date())
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
            callIdToUuidMap = callIdToUuidMap.filter { $0.value != pending }
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
        if pendingOutgoingUuid == uuid { pendingOutgoingUuid = nil }
        if audioCallUuid == uuid { stopAudio() }
    }

    private func maybeStartAudio(for uuid: UUID) {
        guard callKitAudioActivated,
              !audioStartRequested,
              callStates[uuid] == "active",
              GatewayConnection.shared.state == .authenticated else { return }
        audioCallUuid = uuid
        audioStartRequested = true
        GatewayConnection.shared.audioStatus = "Görüşme sesi bağlanıyor"
        GatewayConnection.shared.sendAudioStart()
    }

    public func handleAudioReady(session: UInt32, port: UInt16) {
        guard audioStartRequested,
              callKitAudioActivated,
              let uuid = audioCallUuid,
              callStates[uuid] == "active" else { return }
        AudioEngine.shared.start(
            host: GatewayConnection.shared.connectedHost,
            port: port,
            session: session
        )
    }

    private func stopAudio() {
        AudioEngine.shared.stop()
        if audioStartRequested { GatewayConnection.shared.sendAudioStop() }
        audioStartRequested = false
        audioCallUuid = nil
        GatewayConnection.shared.audioStatus = "Görüşme sesi Redmi'de"
    }
    
    // MARK: - CallKit Reporting
    
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
                    GatewayConnection.shared.log("CallKit incoming call UI presented for: \(phoneNumber)")
                }
            }
        }
    }
    
    private func reportCallConnected(uuid: UUID) {
        provider.reportOutgoingCall(with: uuid, connectedAt: Date())
        GatewayConnection.shared.log("CallKit call marked CONNECTED (uuid=\(uuid.uuidString.prefix(8)))")
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
        callController.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { error in
            if let error {
                Task { @MainActor in
                    GatewayConnection.shared.lastCallError = "iPhone'da yanıtlanamadı: \(error.localizedDescription)"
                }
            }
        }
    }
    
    public func startOutgoingCall(phoneNumber: String) {
        let cleanNumber = phoneNumber.replacingOccurrences(
            of: "[\\s().-]", with: "", options: .regularExpression
        )
        guard cleanNumber.range(of: "^\\+?[0-9]{3,15}$", options: .regularExpression) != nil else {
            GatewayConnection.shared.lastCallError = "Geçerli bir telefon numarası gir."
            return
        }
        guard GatewayConnection.shared.state == .authenticated else {
            GatewayConnection.shared.lastCallError = "Redmi bağlantısı yok. Arama başlatılamadı."
            return
        }
        AudioEngine.shared.prepareSession()
        
        let uuid = UUID()
        let tempRawId = "outgoing_\(uuid.uuidString.prefix(8))"
        callIdToUuidMap[tempRawId] = uuid
        uuidToCallIdMap[uuid] = tempRawId
        callStates[uuid] = "dialing"
        outgoingUuids.insert(uuid)
        pendingOutgoingUuid = uuid
        
        let handle = CXHandle(type: .phoneNumber, value: cleanNumber)
        let action = CXStartCallAction(call: uuid, handle: handle)
        action.isVideo = false
        let transaction = CXTransaction(action: action)
        
        callController.request(transaction) { [weak self] error in
            Task { @MainActor [weak self] in
                if let error = error {
                    GatewayConnection.shared.lastCallError = "iPhone arama ekranı açılamadı: \(error.localizedDescription)"
                    GatewayConnection.shared.log("CallKit start call transaction failed: \(error.localizedDescription)")
                    self?.cleanupCall(uuid: uuid, rawId: tempRawId)
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
        guard GatewayConnection.shared.state == .authenticated,
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
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        let rawId = uuidToCallIdMap[uuid] ?? ""
        let currentState = callStates[uuid] ?? "active"
        guard GatewayConnection.shared.state == .authenticated, !rawId.isEmpty else {
            GatewayConnection.shared.lastCallError = "Redmi bağlantısı yok; görüşmeyi Redmi'den sonlandır."
            action.fail()
            return
        }
        
        GatewayConnection.shared.log("CallKit user ENDED call (state=\(currentState), uuid=\(uuid.uuidString.prefix(8)), callId=\(rawId))")
        
        if currentState == "ringing" {
            // Reject incoming call
            GatewayConnection.shared.sendReject(callId: rawId)
        } else {
            // Hang up active, dialing, or connecting call
            GatewayConnection.shared.sendHangup(callId: rawId)
        }
        
        cleanupCall(uuid: uuid, rawId: rawId)
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        GatewayConnection.shared.log("CallKit CXStartCallAction fulfilled")
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        callKitAudioActivated = true
        GatewayConnection.shared.log("CallKit audio session activated")
        for uuid in answeredOnIPhone.union(outgoingUuids) {
            maybeStartAudio(for: uuid)
        }
    }
    
    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        callKitAudioActivated = false
        stopAudio()
        GatewayConnection.shared.log("CallKit audio session deactivated")
    }
}
