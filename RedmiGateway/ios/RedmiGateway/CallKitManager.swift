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
public class CallKitManager: NSObject, CXProviderDelegate {
    public static let shared = CallKitManager()
    
    private let provider: CXProvider
    private let callController = CXCallController()
    
    // Mapping Android callId (String) <-> iOS CallKit UUID
    private var callIdToUuidMap: [String: UUID] = [:]
    private var uuidToCallIdMap: [UUID: String] = [:]
    private var callStates: [UUID: String] = [:]
    
    // Set of call UUIDs that have already been reported as incoming to CallKit
    private var reportedIncomingUuids: Set<UUID> = []
    
    override private init() {
        let config = CXProviderConfiguration()
        config.localizedName = "Redmi Cellular"
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.phoneNumber]
        
        self.provider = CXProvider(configuration: config)
        super.init()
        self.provider.setDelegate(self, queue: nil)
        
        setupGatewayObserver()
    }
    
    private func setupGatewayObserver() {
        GatewayConnection.shared.onCallEvent = { [weak self] event in
            self?.handleIncomingGatewayCallEvent(event)
        }
    }
    
    // MARK: - Incoming Gateway Event Processing
    
    public func handleIncomingGatewayCallEvent(_ event: CallEventMessage) {
        let rawId = (event.callId?.isEmpty == false) ? event.callId! : "gateway_call"
        let state = event.state.lowercased()
        let number = (event.number?.isEmpty == false) ? event.number! : "Unknown Cellular"
        
        let uuid = getOrCreateUuid(for: rawId)
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
            reportCallConnected(uuid: uuid)
            
        case "disconnected":
            reportCallEnded(uuid: uuid, reason: .remoteEnded)
            cleanupCall(uuid: uuid, rawId: rawId)
            
        case "dialing", "connecting":
            // Outgoing call in progress
            GatewayConnection.shared.log("CallKit: Call progressing (\(state))")
            
        default:
            break
        }
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
        callIdToUuidMap.removeValue(forKey: rawId)
        uuidToCallIdMap.removeValue(forKey: uuid)
        callStates.removeValue(forKey: uuid)
        reportedIncomingUuids.remove(uuid)
    }
    
    // MARK: - CallKit Reporting
    
    private func reportIncomingCall(uuid: UUID, phoneNumber: String) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .phoneNumber, value: phoneNumber)
        update.localizedCallerName = phoneNumber
        update.hasVideo = false
        update.supportsDTMF = true
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                GatewayConnection.shared.log("CallKit report incoming error: \(error.localizedDescription)")
            } else {
                GatewayConnection.shared.log("CallKit incoming call UI presented for: \(phoneNumber)")
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
    
    public func startOutgoingCall(phoneNumber: String) {
        let cleanNumber = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanNumber.isEmpty else { return }
        
        let uuid = UUID()
        let tempRawId = "outgoing_\(uuid.uuidString.prefix(8))"
        callIdToUuidMap[tempRawId] = uuid
        uuidToCallIdMap[uuid] = tempRawId
        callStates[uuid] = "dialing"
        
        let handle = CXHandle(type: .phoneNumber, value: cleanNumber)
        let action = CXStartCallAction(call: uuid, handle: handle)
        action.isVideo = false
        let transaction = CXTransaction(action: action)
        
        callController.request(transaction) { [weak self] error in
            if let error = error {
                GatewayConnection.shared.log("CallKit start call transaction failed: \(error.localizedDescription)")
                self?.cleanupCall(uuid: uuid, rawId: tempRawId)
            } else {
                GatewayConnection.shared.log("CallKit start call transaction accepted. Signaling Redmi...")
                GatewayConnection.shared.sendDial(number: cleanNumber)
            }
        }
    }
    
    // MARK: - CXProviderDelegate Callbacks
    
    public func providerDidReset(_ provider: CXProvider) {
        GatewayConnection.shared.log("CallKit provider reset")
        callIdToUuidMap.removeAll()
        uuidToCallIdMap.removeAll()
        callStates.removeAll()
        reportedIncomingUuids.removeAll()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        let uuid = action.callUUID
        let rawId = uuidToCallIdMap[uuid] ?? ""
        GatewayConnection.shared.log("CallKit user ANSWERED call (uuid=\(uuid.uuidString.prefix(8)), callId=\(rawId))")
        
        // Signal Redmi Gateway to answer the cellular call
        GatewayConnection.shared.sendAnswer(callId: rawId)
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let uuid = action.callUUID
        let rawId = uuidToCallIdMap[uuid] ?? ""
        let currentState = callStates[uuid] ?? "active"
        
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
        GatewayConnection.shared.log("CallKit audio session activated (Ready for Phase 3 audio bridge)")
    }
    
    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        GatewayConnection.shared.log("CallKit audio session deactivated")
    }
}
