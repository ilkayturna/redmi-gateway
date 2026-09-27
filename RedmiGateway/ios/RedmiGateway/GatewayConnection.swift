import Foundation
import Combine
import UIKit
import Network

public enum ConnectionState: String {
    case disconnected = "DISCONNECTED"
    case connecting = "CONNECTING"
    case connected = "CONNECTED"
    case authenticated = "AUTHENTICATED"
    case failed = "FAILED"
}

public enum GatewayUIStatus: Equatable {
    case searching
    case discovered(host: String, port: Int)
    case connecting
    case connected
    case disconnected
    case error(String)

    public var displayText: String {
        switch self {
        case .searching:
            return "Redmi Aranıyor..."
        case .discovered(let host, _):
            return "Redmi Bulundu (\(host))"
        case .connecting:
            return "Bağlanıyor..."
        case .connected:
            return "Bağlandı (Hazır)"
        case .disconnected:
            return "Bağlı Değil"
        case .error(let msg):
            return "Hata: \(msg)"
        }
    }
}

public enum GatewayDiagnosticFailure: Equatable, Identifiable {
    case noWiFi
    case mDnsNotFound
    case bleUnavailable
    case authRejected(String)
    case connectionTimedOut
    case socketDropped(String)
    case custom(reason: String, suggestion: String)

    public var id: String {
        switch self {
        case .noWiFi: return "noWiFi"
        case .mDnsNotFound: return "mDnsNotFound"
        case .bleUnavailable: return "bleUnavailable"
        case .authRejected(let reason): return "authRejected_\(reason)"
        case .connectionTimedOut: return "connectionTimedOut"
        case .socketDropped(let err): return "socketDropped_\(err)"
        case .custom(let r, _): return "custom_\(r)"
        }
    }

    public var title: String {
        switch self {
        case .noWiFi:
            return "Wi-Fi Bağlantısı Yok"
        case .mDnsNotFound:
            return "Redmi Ağda Bulunamadı"
        case .bleUnavailable:
            return "Bluetooth Kapalı veya Kullanılamıyor"
        case .authRejected:
            return "Eşleştirme Başarısız"
        case .connectionTimedOut:
            return "Bağlantı Zaman Aşımına Uğradı"
        case .socketDropped:
            return "Soket Bağlantısı Koptu"
        case .custom(let reason, _):
            return reason
        }
    }

    public var suggestion: String {
        switch self {
        case .noWiFi:
            return "iPhone'un Redmi Hotspot veya aynı Wi-Fi ağına bağlı olduğundan emin olun."
        case .mDnsNotFound:
            return "Redmi Gateway servisinin çalıştığından ve aynı yerel ağda olduğunuzdan emin olun."
        case .bleUnavailable:
            return "Bluetooth'u açarak cihaz keşfini ve acil durum kontrolünü hızlandırın."
        case .authRejected(let msg):
            return "Eşleştirme kodunu kontrol edin: \(msg)"
        case .connectionTimedOut:
            return "IP ve port erişilebilirliğini kontrol edin veya Gateway'i yeniden başlatın."
        case .socketDropped(let err):
            return "Ağ geçişi veya sinyal kaybı: \(err)"
        case .custom(_, let suggestion):
            return suggestion
        }
    }
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
    @Published public var uiStatus: GatewayUIStatus = .disconnected
    @Published public var currentCall: GSMCall? = nil
    @Published public var smsMessages: [SmsMessageItem] = []
    @Published public var logs: [String] = []
    @Published public var audioStatus = "Görüşme sesi Redmi'de"
    @Published public private(set) var isIPhoneAudioConfirmed = false
    @Published public var lastCallError: String? = nil
    @Published public var lastDiagnosticFailure: GatewayDiagnosticFailure? = nil
    @Published public var diagnosticHistory: [GatewayDiagnosticFailure] = []
    @Published public private(set) var isWiFiConnected: Bool = false
    @Published public private(set) var isNetworkViable: Bool = false
    
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
    
    public let discovery = NWGatewayDiscovery()
    private let pathMonitor = NWPathMonitor()
    private let pathMonitorQueue = DispatchQueue(label: "com.ilkay.redmigateway.pathmonitor", qos: .utility)
    
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

        // mDNS Bonjour discovery callback
        discovery.onEndpointFound = { [weak self] host, port, deviceId in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                self.uiStatus = .discovered(host: host, port: port)
                self.log("mDNS: Discovered Redmi at \(host):\(port) (id: \(deviceId ?? "unknown"))")
                
                guard !self.isUserDisconnected,
                      !self.pairingToken.isEmpty,
                      self.state != .authenticated else { return }
                
                if self.state != .connecting || host != self.currentHost || port != self.currentPort {
                    self.log("Auto-connecting to discovered Redmi gateway at \(host):\(port)...")
                    self.connect(host: host, port: port, token: self.pairingToken)
                }
            }
        }

        // BLE companion endpoint hint callback
        BLECentralManager.shared.onEndpointHint = { [weak self] host, port, deviceId in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                self.log("BLE endpoint hint: \(host):\(port) (id: \(deviceId))")
                if self.state != .authenticated && self.state != .connected {
                    self.currentHost = host
                    self.currentPort = port
                    self.uiStatus = .discovered(host: host, port: port)
                    if !self.isUserDisconnected && !self.pairingToken.isEmpty {
                        self.log("Auto-connecting via BLE endpoint hint...")
                        self.connect(host: host, port: port, token: self.pairingToken)
                    }
                }
            }
        }

        // BLE side-channel call state update callback
        BLECentralManager.shared.onStateUpdate = { [weak self] statePayload in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                if self.state != .authenticated {
                    self.log("BLE side-channel call state: \(statePayload.state) (callId: \(statePayload.callId ?? "none"))")
                    let event = CallEventMessage(
                        type: "call",
                        state: statePayload.state,
                        number: statePayload.number,
                        callId: statePayload.callId
                    )
                    self.applyCallEvent(event)
                }
            }
        }

        // BLE companion proximity presence callback
        BLECentralManager.shared.onPresenceChanged = { [weak self] isPresent in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                if isPresent {
                    self.log("BLE companion present in proximity")
                } else {
                    self.log("BLE companion disconnected")
                }
            }
        }
        
        setupPathMonitoring()
        setupLifecycleObservers()
    }

    private func setupPathMonitoring() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                let hasWiFi = path.usesInterfaceType(.wifi)
                let isSatisfied = path.status == .satisfied
                self.isWiFiConnected = hasWiFi
                self.isNetworkViable = isSatisfied

                if !isSatisfied {
                    self.recordDiagnosticFailure(.noWiFi)
                    self.log("Network status: No network connection")
                } else if !hasWiFi {
                    self.log("Network status: Wi-Fi inactive (cellular active). mDNS may not reach Redmi.")
                } else {
                    self.log("Network status: Wi-Fi active")
                    if (self.state == .disconnected || self.state == .failed) && !self.isUserDisconnected {
                        self.log("Network: Wi-Fi restored, restarting discovery & reconnect...")
                        self.discovery.start()
                        BLECentralManager.shared.startScanning()
                        self.scheduleReconnect(delaySeconds: 1.0)
                    }
                }
            }
        }
        pathMonitor.start(queue: pathMonitorQueue)
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
                    self.log("App foregrounded: discovering & reconnecting...")
                    self.discovery.start()
                    BLECentralManager.shared.startScanning()
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

    public func recordDiagnosticFailure(_ failure: GatewayDiagnosticFailure) {
        self.lastDiagnosticFailure = failure
        self.diagnosticHistory.append(failure)
        if self.diagnosticHistory.count > 20 {
            self.diagnosticHistory.removeFirst()
        }
        self.log("Diagnostics: [\(failure.title)] \(failure.suggestion)")
    }

    public func clearDiagnosticFailure() {
        self.lastDiagnosticFailure = nil
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

        discovery.start()
        BLECentralManager.shared.startScanning()

        guard !pairingToken.isEmpty else { return }
        if !currentHost.isEmpty {
            connect(host: currentHost, port: currentPort, token: pairingToken)
        } else {
            isUserDisconnected = false
            self.state = .connecting
            self.uiStatus = .searching
            log("Searching for Redmi gateway on local network (mDNS / BLE)...")
        }
    }
    
    public func connect(host: String, port: Int = 8787, token: String) {
        cancelReconnect()
        disconnectInternal()
        
        self.isUserDisconnected = false
        saveConfiguration(host: host, port: port, token: token)
        discovery.start()
        BLECentralManager.shared.startScanning()
        
        self.state = .connecting
        if host.isEmpty {
            self.uiStatus = .searching
            log("Searching for Redmi on local network (mDNS / BLE)...")
            return
        }
        let formattedHost = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        self.uiStatus = .connecting
        log("Connecting to ws://\(formattedHost):\(port)...")
        
        guard let url = URL(string: "ws://\(formattedHost):\(port)") else {
            self.state = .failed
            self.uiStatus = .error("Invalid URL")
            self.recordDiagnosticFailure(.custom(reason: "Geçersiz URL", suggestion: "IP ve port değerlerini kontrol edin."))
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
        BLECentralManager.shared.stopScanning()
        cancelReconnect()
        disconnectInternal()
        uiStatus = .disconnected
        log("Disconnected by user")
    }
    
    private func disconnectInternal() {
        AudioEngine.shared.stop()
        isIPhoneAudioConfirmed = false
        audioStatus = "Görüşme sesi Redmi'de"
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
                    self.uiStatus = .error(error.localizedDescription)
                    self.recordDiagnosticFailure(.socketDropped(error.localizedDescription))
                    self.log("WebSocket dropped: \(error.localizedDescription)")
                    if !self.isUserDisconnected {
                        self.discovery.start()
                        BLECentralManager.shared.startScanning()
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
                self.uiStatus = .connecting
                self.log("Gateway Hello: \(hello.device). Authenticating...")
                authenticate()
            }
            
        case "auth_result":
            if let authRes = try? JSONDecoder().decode(AuthResultMessage.self, from: data) {
                if authRes.success {
                    self.state = .authenticated
                    self.uiStatus = .connected
                    self.lastDiagnosticFailure = nil
                    self.discovery.stop()
                    self.reconnectAttempt = 0
                    self.log("Authenticated successfully. Syncing state...")
                    requestState()
                    requestSmsHistory(afterId: 0)
                } else {
                    self.isUserDisconnected = true
                    self.state = .failed
                    self.uiStatus = .error(authRes.message ?? "Invalid token")
                    self.recordDiagnosticFailure(.authRejected(authRes.message ?? "Geçersiz eşleştirme kodu"))
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
                self.log("Audio relay: \(audioState.detail)")
                if audioState.detail == "request_iphone_audio" {
                    self.audioStatus = "iPhone'a aktarılıyor"
                    if self.currentCall?.state == .active { self.sendAudioStart() }
                } else {
                    self.isIPhoneAudioConfirmed = audioState.running
                    self.audioStatus = audioState.running ? "Görüşme sesi iPhone'da" : "Görüşme sesi Redmi'de"
                    if !audioState.running {
                        if audioState.detail.hasPrefix("Packet timeout") {
                            CallKitManager.shared.recoverCallAudio()
                        } else {
                            AudioEngine.shared.stop()
                        }
                    }
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
            self.isIPhoneAudioConfirmed = false
            self.audioStatus = "Görüşme sesi Redmi'de"
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
            self.isIPhoneAudioConfirmed = false
            self.audioStatus = "Görüşme sesi Redmi'de"
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
        if state == .authenticated {
            sendPayload(payload)
        } else if BLECentralManager.shared.isConnected {
            log("WebSocket offline: sending ANSWER via BLE fallback...")
            BLECentralManager.shared.sendCallControl(action: "answer", callId: callId) { [weak self] success in
                self?.log("BLE ANSWER result: \(success ? "SUCCESS" : "FAILED")")
            }
        } else {
            sendPayload(payload)
        }
    }
    
    public func sendReject(callId: String) {
        log("Sending REJECT for callId: \(callId)")
        let payload = RejectPayload(callId: callId)
        if state == .authenticated {
            sendPayload(payload)
        } else if BLECentralManager.shared.isConnected {
            log("WebSocket offline: sending REJECT via BLE fallback...")
            BLECentralManager.shared.sendCallControl(action: "reject", callId: callId) { [weak self] success in
                self?.log("BLE REJECT result: \(success ? "SUCCESS" : "FAILED")")
            }
        } else {
            sendPayload(payload)
        }
    }
    
    public func sendHangup(callId: String) {
        log("Sending HANGUP for callId: \(callId)")
        let payload = HangupPayload(callId: callId)
        if state == .authenticated {
            sendPayload(payload)
        } else if BLECentralManager.shared.isConnected {
            log("WebSocket offline: sending HANGUP via BLE fallback...")
            BLECentralManager.shared.sendCallControl(action: "hangup", callId: callId) { [weak self] success in
                self?.log("BLE HANGUP result: \(success ? "SUCCESS" : "FAILED")")
            }
        } else {
            sendPayload(payload)
        }
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

/// Discovers the Redmi Gateway on the local network using Bonjour (mDNS: _redmigateway._tcp).
public final class NWGatewayDiscovery {
    public var onEndpointFound: ((_ host: String, _ port: Int, _ deviceId: String?) -> Void)?
    public var onEndpointLost: ((_ endpoint: NWEndpoint) -> Void)?

    private var browser: NWBrowser?
    private var activeResolvers: [NWEndpoint: NWConnection] = [:]
    private let queue = DispatchQueue(label: "com.ilkay.redmigateway.discovery", qos: .utility)
    public private(set) var isSearching = false

    public init() {}

    public func start() {
        queue.async { [weak self] in
            guard let self = self, !self.isSearching else { return }
            self.isSearching = true

            let descriptor = NWBrowser.Descriptor.bonjour(type: "_redmigateway._tcp", domain: "local.")
            let parameters = NWParameters()
            parameters.includePeerToPeer = true

            let browser = NWBrowser(for: descriptor, using: parameters)
            self.browser = browser

            browser.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    break
                case .failed, .cancelled:
                    self?.queue.async {
                        self?.isSearching = false
                    }
                default:
                    break
                }
            }

            browser.browseResultsChangedHandler = { [weak self] results, changes in
                guard let self = self else { return }
                for change in changes {
                    switch change {
                    case .added(let result):
                        self.resolve(result: result)
                    case .removed(let result):
                        self.onEndpointLost?(result.endpoint)
                    case .changed(_, let newResult, _):
                        self.resolve(result: newResult)
                    @unknown default:
                        break
                    }
                }
            }

            browser.start(queue: self.queue)
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self = self, self.isSearching else { return }
            self.isSearching = false
            self.browser?.cancel()
            self.browser = nil
            for (_, conn) in self.activeResolvers {
                conn.cancel()
            }
            self.activeResolvers.removeAll()
        }
    }

    private func resolve(result: NWBrowser.Result) {
        var deviceId: String? = nil

        // Check Bonjour TXT record metadata
        if let metadata = result.metadata {
            if case let .bonjour(txtRecord) = metadata {
                deviceId = txtRecord["id"]
            }
        }

        // Also check service name fallback (e.g. RedmiGateway-a1b2c3d4)
        if deviceId == nil, case let .service(name, _, _, _) = result.endpoint {
            if name.hasPrefix("RedmiGateway-") {
                deviceId = String(name.dropFirst("RedmiGateway-".count))
            }
        }

        if let existing = activeResolvers.removeValue(forKey: result.endpoint) {
            existing.cancel()
        }

        let conn = NWConnection(to: result.endpoint, using: .tcp)
        activeResolvers[result.endpoint] = conn

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 3.0)
        timer.setEventHandler { [weak self, weak conn] in
            guard let conn = conn else { return }
            conn.cancel()
            self?.activeResolvers.removeValue(forKey: result.endpoint)
        }
        timer.resume()

        conn.stateUpdateHandler = { [weak self, weak conn, timer] state in
            guard let self = self, let conn = conn else { return }
            switch state {
            case .ready:
                timer.cancel()
                if let currentPath = conn.currentPath,
                   let remoteEndpoint = currentPath.remoteEndpoint,
                   case let .hostPort(host, port) = remoteEndpoint {
                    var hostString: String
                    switch host {
                    case .ipv4(let ip):
                        hostString = "\(ip)"
                    case .ipv6(let ip):
                        hostString = "\(ip)"
                    case .name(let name, _):
                        hostString = name
                    @unknown default:
                        hostString = "\(host)"
                    }

                    if hostString.contains("%") {
                        hostString = String(hostString.split(separator: "%")[0])
                    }

                    let portNumber = Int(port.rawValue)
                    if !hostString.isEmpty && portNumber > 0 {
                        DispatchQueue.main.async {
                            self.onEndpointFound?(hostString, portNumber, deviceId)
                        }
                    }
                }
                conn.cancel()
                self.activeResolvers.removeValue(forKey: result.endpoint)

            case .failed, .cancelled:
                timer.cancel()
                self.activeResolvers.removeValue(forKey: result.endpoint)

            default:
                break
            }
        }

        conn.start(queue: queue)
    }
}
