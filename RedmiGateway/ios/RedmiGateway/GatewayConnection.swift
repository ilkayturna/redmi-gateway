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
    public let id: UUID
    public let gatewayId: Int64?
    public let senderOrRecipient: String
    public let body: String
    public let isIncoming: Bool
    public let timestamp: Date

    public init(gatewayId: Int64? = nil, senderOrRecipient: String, body: String, isIncoming: Bool, timestamp: Date) {
        self.id = UUID()
        self.gatewayId = gatewayId
        self.senderOrRecipient = senderOrRecipient
        self.body = body
        self.isIncoming = isIncoming
        self.timestamp = timestamp
    }
}

@MainActor
public class GatewayConnection: ObservableObject {
    public static let shared = GatewayConnection()
    
    @Published public var state: ConnectionState = .disconnected
    @Published public var currentCall: GSMCall? = nil
    @Published public var smsMessages: [SmsMessageItem] = []
    @Published public var logs: [String] = []
    @Published public var audioStatus = "Görüşme sesi Redmi'de"
    @Published public var lastCallError: String? = nil
    
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
    private var seenSmsIds = Set<Int64>()
    private var pendingSms: [(recipient: String, message: String)] = []
    private var hasAttemptedAutoconnect = false
    private let discovery = GatewayDiscovery()
    
    public var onCallEvent: ((CallEventMessage) -> Void)?
    public var connectedHost: String { currentHost }
    
    public init() {
        // Load persisted settings
        let defaults = UserDefaults.standard
        if let savedIp = defaults.string(forKey: kGatewayIp), !savedIp.isEmpty {
            self.currentHost = savedIp
        } else {
            self.currentHost = ""
        }
        
        let savedPort = defaults.integer(forKey: kGatewayPort)
        self.currentPort = savedPort > 0 ? savedPort : 8787
        
        if let savedToken = defaults.string(forKey: kPairingToken) {
            self.pairingToken = savedToken
        }

        discovery.onAddress = { [weak self] address in
            Task { @MainActor [weak self] in
                guard let self = self,
                      !self.isUserDisconnected,
                      !self.pairingToken.isEmpty,
                      self.state != .authenticated,
                      address != self.currentHost else { return }
                self.log("Redmi found at a new local address")
                self.connect(host: address, port: self.currentPort, token: self.pairingToken)
            }
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
                    self.discovery.start()
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

    public func connectSavedConfiguration() {
        guard !hasAttemptedAutoconnect else { return }
        hasAttemptedAutoconnect = true
        guard !pairingToken.isEmpty else { return }
        connect(host: currentHost, port: currentPort, token: pairingToken)
    }
    
    public func connect(host: String, port: Int = 8787, token: String) {
        cancelReconnect()
        disconnectInternal()
        
        self.isUserDisconnected = false
        saveConfiguration(host: host, port: port, token: token)
        discovery.start()
        
        self.state = .connecting
        if host.isEmpty {
            log("Searching for Redmi on the local network")
            return
        }
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
        discovery.stop()
        cancelReconnect()
        disconnectInternal()
        log("Disconnected by user")
    }
    
    private func disconnectInternal() {
        AudioEngine.shared.stop()
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        pendingSms.removeAll()
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
            do {
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            } catch {
                return
            }
            guard let self = self, !Task.isCancelled, !self.isUserDisconnected else { return }
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
                guard self.webSocketTask === task else { return }
                
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
                    AudioEngine.shared.stop()
                    CallKitManager.shared.reportCurrentCallFailedOnNetworkDrop()
                    self.webSocketTask = nil
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
                    self.discovery.stop()
                    self.reconnectAttempt = 0
                    self.log("Authenticated successfully. Syncing state...")
                    requestState()
                    requestSmsHistory(afterId: 0)
                } else {
                    self.isUserDisconnected = true
                    self.state = .failed
                    self.log("Authentication failed: \(authRes.message ?? "Invalid token")")
                    self.webSocketTask?.cancel(with: .goingAway, reason: nil)
                    self.webSocketTask = nil
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
                if ack.action == "dial" {
                    if !ack.success {
                        self.lastCallError = "Arama Redmi'de başlatılamadı. Numarayı ve hattı kontrol et."
                        CallKitManager.shared.handleDialFailed()
                    }
                } else if ack.action == "answer" && !ack.success {
                    self.lastCallError = "Arama Redmi'de yanıtlanamadı."
                }
            }

        case "audio_ready":
            if let ready = try? JSONDecoder().decode(AudioReadyMessage.self, from: data) {
                CallKitManager.shared.handleAudioReady(session: ready.session, port: ready.port)
            }

        case "audio_state":
            if let audioState = try? JSONDecoder().decode(AudioStateMessage.self, from: data) {
                self.audioStatus = audioState.running ? "Görüşme sesi iPhone'da" : "Görüşme sesi Redmi'de"
                self.log("Audio relay: \(audioState.detail)")
                if audioState.detail == "request_iphone_audio" {
                    if self.currentCall?.state == .active { self.sendAudioStart() }
                } else if !audioState.running {
                    AudioEngine.shared.stop()
                }
            }
            
        case "error":
            if let err = try? JSONDecoder().decode(ErrorMessage.self, from: data) {
                self.log("Gateway Error: \(err.message)")
            }
            
        case "sms_received":
            if let sms = try? JSONDecoder().decode(SmsReceivedMessage.self, from: data) {
                appendIncomingSms(id: sms.id, sender: sms.sender, message: sms.message, timestamp: sms.timestamp)
                self.log("SMS received (\(sms.message.count) characters)")
            }

        case "sms_history":
            if let history = try? JSONDecoder().decode(SmsHistoryResponse.self, from: data) {
                for sms in history.messages {
                    appendIncomingSms(id: sms.id, sender: sms.sender, message: sms.message, timestamp: sms.timestamp)
                }
                if history.hasMore && history.lastId > 0 {
                    requestSmsHistory(afterId: history.lastId)
                }
            }
            
        case "sms_send_result":
            if let res = try? JSONDecoder().decode(SmsSendResultMessage.self, from: data) {
                if let index = pendingSms.firstIndex(where: { $0.recipient == res.recipient }) {
                    let pending = pendingSms.remove(at: index)
                    if res.success {
                        smsMessages.append(SmsMessageItem(senderOrRecipient: pending.recipient, body: pending.message, isIncoming: false, timestamp: Date()))
                    }
                }
                if res.success {
                    self.log("SMS send request accepted; delivery unconfirmed")
                } else {
                    self.log("SMS send failed: \(res.error ?? "Unknown error")")
                }
            }
            
        default:
            self.log("Unhandled message type: \(type)")
        }
    }

    private func appendIncomingSms(id: Int64?, sender: String, message: String, timestamp: Int64) {
        if let id = id, !seenSmsIds.insert(id).inserted { return }
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000.0)
        smsMessages.append(SmsMessageItem(gatewayId: id, senderOrRecipient: sender, body: message, isIncoming: true, timestamp: date))
        smsMessages.sort { $0.timestamp < $1.timestamp }
    }
    
    private func applyCallEvent(_ callMsg: CallEventMessage) {
        let callState = GSMCallState(rawValue: callMsg.state) ?? .idle
        let rawId = callMsg.callId ?? ""
        let number = callMsg.number ?? ""
        
        self.log("Call Event: state=\(callMsg.state)")
        
        if callState == .idle || callState == .disconnected {
            self.currentCall = nil
            self.lastCallError = nil
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
            
            self.log("State Sync: Active call found (\(call.state))")
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

    public func requestSmsHistory(afterId: Int64) {
        sendPayload(GetSmsHistoryPayload(afterId: afterId))
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
        guard state == .authenticated else {
            lastCallError = "Redmi bağlantısı yok. Arama başlatılamadı."
            return
        }
        lastCallError = nil
        log("Sending DIAL")
        let payload = DialPayload(number: number)
        sendPayload(payload)
    }

    public func sendAudioStart() {
        guard state == .authenticated else { return }
        sendPayload(AudioStartPayload())
    }

    public func sendAudioStop() {
        guard state == .authenticated else { return }
        sendPayload(AudioStopPayload())
    }

    public func sendDtmf(digit: String, callId: String? = nil) {
        guard state == .authenticated else { return }
        log("Sending DTMF tone")
        let payload = DtmfPayload(digit: digit, callId: callId)
        sendPayload(payload)
    }
    
    public func sendSms(recipient: String, message: String) {
        log("Sending SMS (\(message.count) characters)")
        let payload = SendSmsPayload(recipient: recipient, message: message)
        pendingSms.append((recipient: recipient, message: message))
        sendPayload(payload)
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

/// Resolves the Redmi's current hotspot address when Android assigns a new one.
private final class GatewayDiscovery: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    var onAddress: ((String) -> Void)?

    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var isSearching = false

    override init() {
        super.init()
        browser.delegate = self
    }

    func start() {
        guard !isSearching else { return }
        isSearching = true
        browser.searchForServices(ofType: "_redmigateway._tcp.", inDomain: "local.")
    }

    func stop() {
        guard isSearching else { return }
        isSearching = false
        browser.stop()
        services.forEach { $0.stop() }
        services.removeAll()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard isSearching, service.name.hasPrefix("Telefon Gecidi") else { return }
        services.append(service)
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        services.removeAll { $0.name == service.name }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard isSearching, sender.port == 8787 else { return }
        for address in sender.addresses ?? [] {
            guard let host = Self.ipv4Address(from: address) else { continue }
            onAddress?(host)
            return
        }
    }

    private static func ipv4Address(from address: Data) -> String? {
        address.withUnsafeBytes { bytes in
            guard let sockaddrPointer = bytes.baseAddress?.assumingMemoryBound(to: sockaddr.self),
                  sockaddrPointer.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                sockaddrPointer, socklen_t(bytes.count),
                &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST
            )
            return result == 0 ? String(cString: host) : nil
        }
    }
}
