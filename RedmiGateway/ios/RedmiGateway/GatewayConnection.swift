import Foundation
import Combine
import UIKit

public enum ConnectionState: String {
    case disconnected = "DISCONNECTED"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case authenticated = "AUTHENTICATED"
    case failed = "FAILED"
}

public struct SmsMessageItem: Identifiable, Equatable {
    public let id = UUID()
    public let senderOrRecipient: String
    public let body: String
    public let isIncoming: Bool
    public let timestamp: Date
}

@MainActor
public class GatewayConnection: ObservableObject {
    public static let shared = GatewayConnection()
    
    @Published public var state: ConnectionState = .disconnected
    @Published public var currentCall: GSMCall? = nil
    @Published public var smsMessages: [SmsMessageItem] = []
    @Published public var logs: [String] = []
    
    // Persistence keys
    private let kGatewayIp = "gateway_ip"
    private let kGatewayPort = "gateway_port"
    private let kPairingToken = "pairing_token"
    
    private var webSocketTask: URLSessionWebSocketTask?
    private var pairingToken: String = ""
    private var currentHost: String = ""
    private var currentPort: Int = 8787
    
    // Auto-reconnect state
    private var isUserDisconnected: Bool = true
    private var reconnectAttempt: Int = 0
    private var reconnectTimer: Task<Void, Never>? = nil
    
    public var onCallEvent: ((CallEventMessage) -> Void)?
    
    public init() {
        // Load persisted settings
        let defaults = UserDefaults.standard
        if let savedIp = defaults.string(forKey: kGatewayIp), !savedIp.isEmpty {
            self.currentHost = savedIp
        } else {
            self.currentHost = "192.168.207.155"
        }
        
        let savedPort = defaults.integer(forKey: kGatewayPort)
        self.currentPort = savedPort > 0 ? savedPort : 8787
        
        if let savedToken = defaults.string(forKey: kPairingToken) {
            self.pairingToken = savedToken
        }
        
        setupLifecycleObservers()
    }
    
    private func setupLifecycleObservers() {
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                if !self.isUserDisconnected && (self.state == .disconnected || self.state == .failed) {
                    self.log("App foregrounded: reconnecting to gateway...")
                    self.scheduleReconnect(delaySeconds: 1.0)
                }
            }
        }
    }
    
    public func log(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let timestamp = formatter.string(from: Date())
        logs.append("[\(timestamp)] \(message)")
        if logs.count > 100 {
            logs.removeFirst()
        }
    }
    
    public func saveConfiguration(host: String, port: Int, token: String) {
        self.currentHost = host
        self.currentPort = port
        self.pairingToken = token
        
        let defaults = UserDefaults.standard
        defaults.set(host, forKey: kGatewayIp)
        defaults.set(port, forKey: kGatewayPort)
        defaults.set(token, forKey: kPairingToken)
    }
    
    public func connect(host: String, port: Int = 8787, token: String) {
        cancelReconnect()
        disconnectInternal()
        
        self.isUserDisconnected = false
        saveConfiguration(host: host, port: port, token: token)
        
        self.state = .connecting
        log("Connecting to ws://\(host):\(port)...")
        
        guard let url = URL(string: "ws://\(host):\(port)") else {
            self.state = .failed
            log("Invalid WebSocket URL")
            return
        }
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        let session = URLSession(configuration: config)
        let task = session.webSocketTask(with: url)
        self.webSocketTask = task
        task.resume()
        
        listenForMessages()
    }
    
    public func disconnect() {
        isUserDisconnected = true
        cancelReconnect()
        disconnectInternal()
        log("Disconnected by user")
    }
    
    private func disconnectInternal() {
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        self.state = .disconnected
    }
    
    private func scheduleReconnect(delaySeconds: Double? = nil) {
        guard !isUserDisconnected else { return }
        cancelReconnect()
        
        let delay: Double
        if let custom = delaySeconds {
            delay = custom
        } else {
            // Exponential backoff: 2s, 4s, 8s, 16s, capped at 30s
            let exponent = min(reconnectAttempt, 4)
            delay = min(Double(1 << (exponent + 1)), 30.0)
        }
        
        log("Scheduling auto-reconnect in \(Int(delay))s (Attempt \(reconnectAttempt + 1))...")
        
        reconnectTimer = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self = self, !self.isUserDisconnected else { return }
            self.reconnectAttempt += 1
            self.connect(host: self.currentHost, port: self.currentPort, token: self.pairingToken)
        }
    }
    
    private func cancelReconnect() {
        reconnectTimer?.cancel()
        reconnectTimer = nil
    }
    
    private func listenForMessages() {
        guard let task = webSocketTask else { return }
        
        task.receive { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                
                switch result {
                case .success(let message):
                    switch message {
                    case .string(let text):
                        self.handleIncomingText(text)
                    case .data(let data):
                        if let text = String(data: data, encoding: .utf8) {
                            self.handleIncomingText(text)
                        }
                    @unknown default:
                        break
                    }
                    self.listenForMessages()
                    
                case .failure(let error):
                    self.state = .failed
                    self.log("WebSocket dropped: \(error.localizedDescription)")
                    if !self.isUserDisconnected {
                        self.scheduleReconnect()
                    }
                }
            }
        }
    }
    
    private func handleIncomingText(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        
        guard let jsonObject = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = jsonObject["type"] as? String else {
            log("Received unknown message format: \(text)")
            return
        }
        
        switch type {
        case "hello":
            if let hello = try? JSONDecoder().decode(HelloMessage.self, from: data) {
                self.state = .connected
                self.log("Gateway Hello: \(hello.device). Authenticating...")
                authenticate()
            }
            
        case "auth_result":
            if let authRes = try? JSONDecoder().decode(AuthResultMessage.self, from: data) {
                if authRes.success {
                    self.state = .authenticated
                    self.reconnectAttempt = 0
                    self.log("Authenticated successfully. Syncing state...")
                    requestState()
                } else {
                    self.state = .failed
                    self.log("Authentication failed: \(authRes.message ?? "Invalid token")")
                }
            }
            
        case "call":
            if let callMsg = try? JSONDecoder().decode(CallEventMessage.self, from: data) {
                applyCallEvent(callMsg)
            }
            
        case "state":
            if let stateRes = try? JSONDecoder().decode(StateResponseMessage.self, from: data) {
                handleStateResponse(stateRes)
            }
            
        case "ack":
            if let ack = try? JSONDecoder().decode(AckMessage.self, from: data) {
                self.log("ACK [\(ack.action)]: \(ack.success ? "SUCCESS" : "FAILED")")
            }
            
        case "error":
            if let err = try? JSONDecoder().decode(ErrorMessage.self, from: data) {
                self.log("Gateway Error: \(err.message)")
            }
            
        case "sms_received":
            if let sms = try? JSONDecoder().decode(SmsReceivedMessage.self, from: data) {
                let date = Date(timeIntervalSince1970: Double(sms.timestamp) / 1000.0)
                let item = SmsMessageItem(senderOrRecipient: sms.sender, body: sms.message, isIncoming: true, timestamp: date)
                self.smsMessages.append(item)
                self.log("SMS received from \(sms.sender): \(sms.message)")
            }
            
        case "sms_send_result":
            if let res = try? JSONDecoder().decode(SmsSendResultMessage.self, from: data) {
                if res.success {
                    self.log("SMS to \(res.recipient) sent successfully")
                } else {
                    self.log("SMS to \(res.recipient) failed: \(res.error ?? "Unknown error")")
                }
            }
            
        default:
            self.log("Unhandled message type: \(type)")
        }
    }
    
    private func applyCallEvent(_ callMsg: CallEventMessage) {
        let callState = GSMCallState(rawValue: callMsg.state) ?? .idle
        let rawId = callMsg.callId ?? ""
        let number = callMsg.number ?? ""
        
        self.log("Call Event: state=\(callMsg.state), num=\(number), id=\(rawId)")
        
        if callState == .idle || callState == .disconnected {
            self.currentCall = nil
        } else {
            let existingId = self.currentCall?.id ?? UUID()
            self.currentCall = GSMCall(id: existingId, rawCallId: rawId, number: number, state: callState)
        }
        
        // Dispatch to CallKitManager
        self.onCallEvent?(callMsg)
    }
    
    private func handleStateResponse(_ stateRes: StateResponseMessage) {
        if let call = stateRes.call {
            let callState = GSMCallState(rawValue: call.state) ?? .idle
            let rawId = call.callId ?? ""
            let number = call.number ?? ""
            
            self.log("State Sync: Active call found (\(call.state), num=\(number))")
            let existingId = self.currentCall?.id ?? UUID()
            self.currentCall = GSMCall(id: existingId, rawCallId: rawId, number: number, state: callState)
            
            let event = CallEventMessage(type: "call", state: call.state, number: number, callId: rawId)
            self.onCallEvent?(event)
        } else {
            self.log("State Sync: No active calls on gateway")
            if self.currentCall != nil {
                let prevId = self.currentCall?.rawCallId ?? ""
                self.currentCall = nil
                let event = CallEventMessage(type: "call", state: "disconnected", number: nil, callId: prevId)
                self.onCallEvent?(event)
            }
        }
    }
    
    private func authenticate() {
        let payload = AuthPayload(token: pairingToken)
        sendPayload(payload)
    }
    
    public func requestState() {
        let payload = GetStatePayload()
        sendPayload(payload)
    }
    
    public func sendAnswer(callId: String) {
        log("Sending ANSWER for callId: \(callId)")
        let payload = AnswerPayload(callId: callId)
        sendPayload(payload)
    }
    
    public func sendReject(callId: String) {
        log("Sending REJECT for callId: \(callId)")
        let payload = RejectPayload(callId: callId)
        sendPayload(payload)
    }
    
    public func sendHangup(callId: String) {
        log("Sending HANGUP for callId: \(callId)")
        let payload = HangupPayload(callId: callId)
        sendPayload(payload)
    }
    
    public func sendDial(number: String) {
        log("Sending DIAL for number: \(number)")
        let payload = DialPayload(number: number)
        sendPayload(payload)
    }
    
    public func sendSms(recipient: String, message: String) {
        log("Sending SMS to \(recipient): \(message)")
        let payload = SendSmsPayload(recipient: recipient, message: message)
        sendPayload(payload)
        let item = SmsMessageItem(senderOrRecipient: recipient, body: message, isIncoming: false, timestamp: Date())
        self.smsMessages.append(item)
    }
    
    private func sendPayload<T: Encodable>(_ payload: T) {
        guard let data = try? JSONEncoder().encode(payload),
              let jsonString = String(data: data, encoding: .utf8) else {
            log("Failed to encode JSON payload")
            return
        }
        
        let message = URLSessionWebSocketTask.Message.string(jsonString)
        webSocketTask?.send(message) { [weak self] error in
            if let error = error {
                Task { @MainActor [weak self] in
                    self?.log("Send error: \(error.localizedDescription)")
                }
            }
        }
    }
}
