import Combine
import Foundation
import Network
import UIKit

public enum ConnectionState: String {
    case disconnected, connecting, connected, authenticated, failed
}

/// What the person sees about the link to the Redmi.
enum LinkStatus: Equatable {
    case unpaired
    case pairing
    case searching
    case connecting
    case ready
    case offline(String)
    case incompatible(String)
}

/// Which phone carries the conversation right now (the Redmi decides and reports it).
enum AudioLocation: Equatable {
    case redmi
    case movingToIPhone
    case iphone
}

/// The authenticated link to the Redmi (protocol 2, docs/PROTOCOL.md).
///
/// The Redmi is found on the local network with Bonjour, with its Bluetooth hint, or at the
/// last address that worked; a manual address is only used when set under Advanced. Every
/// frame after authentication is sealed, and the Redmi's call and SMS journals are replayed
/// from the last sequence this iPhone has seen.
@MainActor
public final class GatewayConnection: ObservableObject {
    public static let shared = GatewayConnection()

    @Published public private(set) var state: ConnectionState = .disconnected
    @Published private(set) var link: LinkStatus = .searching
    @Published public private(set) var currentCall: GSMCall?
    @Published private(set) var audioLocation: AudioLocation = .redmi
    /// Short, human explanation when the call audio went back to the Redmi on its own.
    @Published var audioNotice: String?
    @Published public var lastCallError: String?
    @Published var pairingError: String?
    @Published private(set) var compatibility: ProtocolCompatibility = .supported
    @Published private(set) var gatewayDevice: String?
    @Published private(set) var gatewayAppVersion: String?
    @Published private(set) var remoteDiagnostics: String?
    @Published private(set) var logs: [String] = []
    @Published private(set) var isWiFiConnected = false
    @Published private(set) var isNetworkViable = false
    @Published private(set) var pairing: GatewayPairing?
    /// A second call ringing during the current one.
    @Published private(set) var waitingCall: GSMCall?

    public var onCallEvent: ((CallEventMessage) -> Void)?
    public var connectedHost: String { currentHost }
    /// Key for this connection's sealed call audio; nil until authenticated.
    private(set) var mediaKey: Data?

    public var isPaired: Bool { pairing != nil }
    public var isAuthenticated: Bool { state == .authenticated }
    var isIPhoneAudioConfirmed: Bool { audioLocation == .iphone }

    private let kLastHost = "gateway_ip"
    private let kLastPort = "gateway_port"
    private let kManualHost = "manual_gateway_ip"
    private let kManualPort = "manual_gateway_port"
    private let kLegacyToken = "pairing_token"
    private let kKnownAddresses = "gateway_addresses"

    /// A Redmi address to try. The Redmi can have several at once (Wi-Fi, its own hotspot, VPN).
    private struct Endpoint: Hashable {
        let host: String
        let port: Int
    }

    private let urlSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 8
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()
    private var webSocketTask: URLSessionWebSocketTask?
    /// Addresses the Redmi was seen at, newest first; all are tried together on every connect.
    private var candidates: [Endpoint] = []
    /// Connection attempts still waiting for the Redmi's hello; the first to answer wins.
    private var attempts: [(task: URLSessionWebSocketTask, endpoint: Endpoint)] = []
    private var channel: SecureChannel?
    private var serverNonce: Data?
    private var clientNonce: Data?
    private var currentHost = ""
    private var currentPort = 8787
    private var pendingPairCode: String?
    private var pairClientId = ""
    private var migratingLegacyCode = false
    private var ignoredHosts = Set<String>()
    private var started = false
    private var reconnectAttempt = 0
    private var reconnectTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var syncCallsHasMore = false
    private var smsTimeouts: [String: Task<Void, Never>] = [:]
    private var lastPathSignature = ""
    private var catchUpTask: UIBackgroundTaskIdentifier = .invalid
    private var closeAfterCatchUp = false
    /// VoIP push token (PushKit); sent to the Redmi for calls while this iPhone is away.
    private var pushToken: String?
    private var alertPushToken: String?

    let discovery = NWGatewayDiscovery()
    private let pathMonitor = NWPathMonitor()
    private var history: HistoryStore { HistoryStore.shared }

    private init() {
        let defaults = UserDefaults.standard
        currentHost = defaults.string(forKey: kLastHost) ?? ""
        let port = defaults.integer(forKey: kLastPort)
        currentPort = port > 0 ? port : 8787
        for entry in defaults.stringArray(forKey: kKnownAddresses) ?? [] {
            let parts = entry.split(separator: "|", maxSplits: 1).map(String.init)
            if parts.count == 2, let entryPort = Int(parts[1]) {
                candidates.append(Endpoint(host: parts[0], port: entryPort))
            }
        }
        if !currentHost.isEmpty, !candidates.contains(Endpoint(host: currentHost, port: currentPort)) {
            candidates.insert(Endpoint(host: currentHost, port: currentPort), at: 0)
        }
        pairing = PairingKeychain.load()
        link = pairing == nil ? .unpaired : .searching

        discovery.onEndpointFound = { [weak self] host, port, deviceId in
            Task { @MainActor [weak self] in self?.endpointsFound([host], port: port, deviceId: deviceId, source: "Bonjour") }
        }
        let ble = BLECentralManager.shared
        ble.onEndpointHint = { [weak self] hosts, port, deviceId in
            Task { @MainActor [weak self] in self?.endpointsFound(hosts, port: port, deviceId: deviceId, source: "Bluetooth") }
        }
        ble.onStateUpdate = { [weak self] update in
            Task { @MainActor [weak self] in self?.applyBluetoothCallState(update) }
        }
        setupPathMonitoring()
        setupLifecycleObservers()
    }

    // MARK: - Start, pairing and reset

    /// Called at launch. Carries a protocol 1 pairing code into a keyed pairing automatically.
    func start() {
        guard !started else { return }
        started = true
        if pairing == nil, let legacy = UserDefaults.standard.string(forKey: kLegacyToken),
           !legacy.trimmingCharacters(in: .whitespaces).isEmpty {
            // Builds up to 1.0 (33) stored the Redmi's code; it is still valid until the
            // first keyed pairing, so the update needs no action from the person.
            pendingPairCode = legacy.uppercased()
            migratingLegacyCode = true
            link = .pairing
            log("Moving the existing pairing to protocol 2")
        }
        if let pairing { history.bind(gatewayId: pairing.gatewayId) }
        discovery.start()
        BLECentralManager.shared.start()
        guard pairing != nil || pendingPairCode != nil else { return }
        connectToBestEndpoint()
    }

    /// Pair with the six-character code shown on the Redmi.
    func pair(code: String) {
        let clean = code.uppercased().filter { $0.isLetter || $0.isNumber }
        guard clean.count == 6 else {
            pairingError = "Kod 6 karakterdir."
            return
        }
        pairingError = nil
        pendingPairCode = clean
        migratingLegacyCode = false
        ignoredHosts.removeAll()
        link = .pairing
        discovery.start()
        BLECentralManager.shared.start()
        connectToBestEndpoint()
    }

    /// Forget this Redmi on both phones. History from it is removed from the iPhone.
    func unpair() {
        if state == .authenticated { send(["type": "unpair"]) }
        PairingKeychain.delete()
        UserDefaults.standard.removeObject(forKey: kLegacyToken)
        pairing = nil
        pendingPairCode = nil
        history.reset()
        BLECentralManager.shared.forgetGateway()
        CallWakeMonitor.shared.refresh()
        cancelReconnect()
        closeSocket()
        link = .unpaired
        log("Pairing removed")
    }

    func setManualAddress(host: String, port: Int) {
        let clean = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let defaults = UserDefaults.standard
        if clean.isEmpty {
            defaults.removeObject(forKey: kManualHost)
            defaults.removeObject(forKey: kManualPort)
        } else {
            defaults.set(clean, forKey: kManualHost)
            defaults.set(port, forKey: kManualPort)
        }
        reconnectNow(reason: "manual address")
    }

    var manualAddress: (host: String, port: Int)? {
        let defaults = UserDefaults.standard
        guard let host = defaults.string(forKey: kManualHost), !host.isEmpty else { return nil }
        let port = defaults.integer(forKey: kManualPort)
        return (host, port > 0 ? port : 8787)
    }

    /// Reconnect immediately (app foregrounded, Bluetooth woke the app, network changed).
    func reconnectNow(reason: String) {
        guard pairing != nil || pendingPairCode != nil else { return }
        guard state != .authenticated, webSocketTask == nil else { return }
        log("Reconnecting: \(reason)")
        reconnectAttempt = 0
        connectToBestEndpoint()
    }

    /// Tries every known address of the Redmi at once (manual address first); the first one
    /// that answers wins. A stale address can never hold up a fresh one.
    private func connectToBestEndpoint() {
        var list: [Endpoint] = []
        if let manual = manualAddress { list.append(Endpoint(host: manual.host, port: manual.port)) }
        list += candidates
        race(list)
    }

    private func endpointsFound(_ hosts: [String], port: Int, deviceId: String?, source: String) {
        if let pairing, let deviceId, !deviceId.isEmpty, deviceId != pairing.gatewayId { return }
        let found = hosts.filter { !$0.isEmpty && !ignoredHosts.contains($0) }.map { Endpoint(host: $0, port: port) }
        guard !found.isEmpty else { return }
        let fresh = found.contains { !candidates.contains($0) }
        remember(found)
        guard pairing != nil || pendingPairCode != nil, state != .authenticated, webSocketTask == nil else { return }
        if fresh { log("Redmi address found via \(source)") }
        // Joins a race in progress, or starts one.
        race(found + candidates)
    }

    private func remember(_ endpoints: [Endpoint]) {
        for endpoint in endpoints.reversed() {
            candidates.removeAll { $0 == endpoint }
            candidates.insert(endpoint, at: 0)
        }
        if candidates.count > 10 { candidates.removeLast(candidates.count - 10) }
        UserDefaults.standard.set(candidates.map { "\($0.host)|\($0.port)" }, forKey: kKnownAddresses)
    }

    // MARK: - Socket

    private func race(_ list: [Endpoint]) {
        guard webSocketTask == nil else { return }
        cancelReconnect()
        var seen = Set<Endpoint>()
        let unique = list.filter { seen.insert($0).inserted && !ignoredHosts.contains($0.host) }
        let local = unique.filter { !Self.isVpnAddress($0.host) }
        let vpn = unique.filter { Self.isVpnAddress($0.host) }
        for endpoint in local.prefix(6) where !attempts.contains(where: { $0.endpoint == endpoint }) {
            startAttempt(endpoint)
        }
        // Away from home the Redmi is reached through the VPN (Tailscale); at home the direct
        // path should win, so VPN addresses start a moment later.
        for endpoint in vpn.prefix(2) where !attempts.contains(where: { $0.endpoint == endpoint }) {
            if attempts.isEmpty {
                startAttempt(endpoint)
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    Task { @MainActor in
                        let connection = GatewayConnection.shared
                        guard connection.webSocketTask == nil, connection.state != .authenticated,
                              !connection.attempts.contains(where: { $0.endpoint == endpoint }) else { return }
                        connection.startAttempt(endpoint)
                    }
                }
            }
        }
        state = .connecting
        if attempts.isEmpty {
            if link != .pairing { link = .searching }
            scheduleReconnect()
            return
        }
        if link != .pairing { link = .connecting }
        // An address that does not answer can keep "connecting" for a long time. Say what is
        // wrong while the attempts go on; the first one that answers still wins.
        connectHint += 1
        let hint = connectHint
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
            Task { @MainActor in
                let connection = GatewayConnection.shared
                guard connection.connectHint == hint, connection.link == .connecting,
                      connection.state != .authenticated else { return }
                connection.link = .offline(connection.unreachableReason)
            }
        }
    }

    private var connectHint = 0

    /// Why the Redmi cannot be reached, in terms of what to do. The iPhone has no mobile service of
    /// its own, so without Wi-Fi it is offline; with Wi-Fi and the Redmi still in Bluetooth range,
    /// the two phones are almost always on different networks.
    var unreachableReason: String {
        guard isNetworkViable else { return "Wi-Fi yok · Redmi'nin hotspot'una bağlan" }
        if BLECentralManager.shared.isConnected {
            return "Redmi başka bir ağda · iPhone'u Redmi'nin Wi-Fi'ına ya da hotspot'una bağla"
        }
        return "Redmi'ye ulaşılamıyor"
    }

    /// Tailscale and similar VPNs use the shared address space 100.64.0.0/10.
    /// The link runs through the VPN (away from home); the audio side is more patient there.
    var isRemoteLink: Bool { Self.isVpnAddress(currentHost) }

    static func isVpnAddress(_ host: String) -> Bool {
        let parts = host.split(separator: ".").compactMap { Int($0) }
        return parts.count == 4 && parts[0] == 100 && (64...127).contains(parts[1])
    }

    private func startAttempt(_ endpoint: Endpoint) {
        let host = endpoint.host.contains(":") && !endpoint.host.hasPrefix("[") ? "[\(endpoint.host)]" : endpoint.host
        guard let url = URL(string: "ws://\(host):\(endpoint.port)") else { return }
        let task = urlSession.webSocketTask(with: url)
        task.maximumMessageSize = 1 << 20
        attempts.append((task, endpoint))
        task.resume()
        task.receive { [weak self] result in
            Task { @MainActor [weak self] in self?.attemptAnswered(task, endpoint: endpoint, result: result) }
        }
    }

    private func attemptAnswered(_ task: URLSessionWebSocketTask, endpoint: Endpoint,
                                 result: Result<URLSessionWebSocketTask.Message, Error>) {
        guard let index = attempts.firstIndex(where: { $0.task === task }) else { return }
        attempts.remove(at: index)
        switch result {
        case .success(let message):
            guard webSocketTask == nil else {
                task.cancel(with: .goingAway, reason: nil)
                return
            }
            // The first Redmi address to answer wins; the other attempts end here.
            for other in attempts { other.task.cancel(with: .goingAway, reason: nil) }
            attempts.removeAll()
            webSocketTask = task
            currentHost = endpoint.host
            currentPort = endpoint.port
            remember([endpoint])
            switch message {
            case .string(let text): handleFrame(text)
            case .data(let data): handleFrame(String(decoding: data, as: UTF8.self))
            @unknown default: break
            }
            if webSocketTask === task { receive(on: task) }
        case .failure:
            guard attempts.isEmpty, webSocketTask == nil else { return }
            state = .failed
            switch link {
            case .unpaired, .incompatible, .pairing: break
            default: link = .offline(unreachableReason)
            }
            scheduleReconnect()
        }
    }

    private func closeSocket() {
        for attempt in attempts { attempt.task.cancel(with: .goingAway, reason: nil) }
        attempts.removeAll()
        pingTask?.cancel()
        pingTask = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        channel = nil
        mediaKey = nil
        serverNonce = nil
        clientNonce = nil
        if state != .disconnected { state = .disconnected }
    }

    /// After a network change an authenticated socket may be dead without knowing it yet.
    private func validateLink() {
        guard let task = webSocketTask, state == .authenticated else { return }
        task.sendPing { error in
            guard error != nil else { return }
            Task { @MainActor in GatewayConnection.shared.handleDrop(task) }
        }
    }

    private func receive(on task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.webSocketTask === task else { return }
                switch result {
                case .success(let message):
                    switch message {
                    case .string(let text): self.handleFrame(text)
                    case .data(let data): self.handleFrame(String(decoding: data, as: UTF8.self))
                    @unknown default: break
                    }
                    if self.webSocketTask === task { self.receive(on: task) }
                case .failure:
                    self.handleDrop(task)
                }
            }
        }
    }

    private func handleDrop(_ task: URLSessionWebSocketTask) {
        guard webSocketTask === task else { return }
        let wasAuthenticated = state == .authenticated
        closeSocket()
        state = .failed
        if wasAuthenticated {
            log("Connection to Redmi lost")
            CallKitManager.shared.handleGatewayLinkLost()
        }
        audioLocation = .redmi
        switch link {
        case .unpaired, .incompatible: return
        case .pairing: break
        default: link = .offline(unreachableReason)
        }
        scheduleReconnect()
    }

    private func scheduleReconnect(delay custom: Double? = nil) {
        guard pairing != nil || pendingPairCode != nil else { return }
        // In the background only a call justifies reconnecting; Bluetooth covers the rest.
        guard UIApplication.shared.applicationState != .background || currentCall != nil else { return }
        cancelReconnect()
        let delay = custom ?? min(Double(1 << min(reconnectAttempt + 1, 5)), 30)
        reconnectTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled, self.state != .authenticated else { return }
            self.reconnectAttempt += 1
            self.discovery.start()
            BLECentralManager.shared.start()
            BLECentralManager.shared.refreshDeviceInfo()
            self.connectToBestEndpoint()
        }
    }

    private func cancelReconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
    }

    private func startPing() {
        pingTask?.cancel()
        pingTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard let self, let task = self.webSocketTask, self.state == .authenticated else { return }
                task.sendPing { error in
                    guard error != nil else { return }
                    Task { @MainActor in GatewayConnection.shared.handleDrop(task) }
                }
            }
        }
    }

    // MARK: - Frames

    private func handleFrame(_ text: String) {
        guard let object = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
        if let channel {
            guard object["t"] as? String == "s",
                  let counter = (object["c"] as? NSNumber)?.uint64Value,
                  let body = object["d"] as? String,
                  let plain = channel.open(counter: counter, data: body),
                  let inner = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any],
                  let type = inner["type"] as? String else {
                log("Dropped a frame that failed authentication")
                return
            }
            dispatch(type: type, data: plain)
        } else if let type = object["type"] as? String {
            // Before authentication only the handshake travels in clear text.
            guard ["hello", "pair_result", "auth_result", "error"].contains(type) else { return }
            dispatch(type: type, data: Data(text.utf8))
        }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) -> T? {
        try? JSONDecoder().decode(type, from: data)
    }

    private func dispatch(type: String, data: Data) {
        switch type {
        case "hello":
            if let hello = decode(HelloMessage.self, data) { handleHello(hello) }
        case "pair_result":
            if let result = decode(PairResultMessage.self, data) { handlePairResult(result) }
        case "auth_result":
            if let result = decode(AuthResultMessage.self, data) { handleAuthResult(result) }
        case "call":
            if let event = decode(CallEventMessage.self, data) { applyCallEvent(event) }
        case "state":
            if let response = decode(StateResponseMessage.self, data) { handleStateResponse(response) }
        case "ack":
            if let ack = decode(AckMessage.self, data) { handleAck(ack) }
        case "audio_ready":
            if let ready = decode(AudioReadyMessage.self, data) {
                CallKitManager.shared.handleAudioReady(session: ready.session, port: ready.port,
                                                      key: ready.sealed == true ? mediaKey : nil)
            }
        case "audio_state":
            if let audio = decode(AudioStateMessage.self, data) { handleAudioState(audio) }
        case "sync_calls":
            if let sync = decode(SyncCallsMessage.self, data) {
                Notifier.shared.post(missed: history.applyCallSync(sync.items, lastSeq: sync.lastSeq))
                syncCallsHasMore = sync.hasMore
            }
        case "sync_sms":
            if let sync = decode(SyncSmsMessage.self, data) {
                Notifier.shared.post(sms: history.applySmsSync(sync.items, lastSeq: sync.lastSeq))
                if sync.hasMore || syncCallsHasMore {
                    requestSync()
                } else {
                    finishCatchUp()
                }
            }
        case "call_log":
            if let push = decode(CallLogPush.self, data) { Notifier.shared.post(missed: history.applyCallPush(push.item)) }
        case "sms":
            if let push = decode(SmsPush.self, data) { Notifier.shared.post(sms: history.applySmsPush(push.item)) }
        case "call_waiting":
            if let waiting = decode(CallWaitingMessage.self, data) { handleWaiting(waiting) }
        case "sms_send_result":
            if let result = decode(SmsSendResultMessage.self, data), let requestId = result.requestId {
                smsTimeouts.removeValue(forKey: requestId)?.cancel()
                history.completePending(requestId: requestId, success: result.success)
                if !result.success { log("SMS send failed: \(result.error ?? "unknown")") }
            }
        case "diagnostics_result":
            if let object = try? JSONSerialization.jsonObject(with: data),
               let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]) {
                remoteDiagnostics = String(decoding: pretty, as: UTF8.self)
            }
        case "resolve_contact":
            if let request = decode(ResolveContactMessage.self, data) {
                PhoneContactDirectory.shared.ensureLoaded()
                let name = PhoneContactDirectory.shared.name(for: request.number)
                var reply: [String: Any] = [
                    "type": "contact_info",
                    "number": request.number
                ]
                if let name { reply["name"] = name }
                if let requestId = request.requestId { reply["requestId"] = requestId }
                send(reply)
            }
        case "error":
            if let error = decode(ErrorMessage.self, data) { log("Redmi error: \(error.code ?? error.message ?? "unknown")") }
        default:
            break
        }
    }

    // MARK: - Handshake

    private func handleHello(_ hello: HelloMessage) {
        state = .connected
        gatewayDevice = hello.device
        gatewayAppVersion = hello.appVersion
        let serverProtocol = hello.protocolVersion ?? 1
        let minProtocol = hello.minProtocol ?? 1
        if minProtocol > BridgeProtocol.version {
            setIncompatible("Redmi'deki uygulama bu iPhone sürümünden yeni. iPhone uygulamasını TestFlight'tan güncelle.")
            return
        }
        if serverProtocol < BridgeProtocol.version {
            setIncompatible("Redmi'deki Telefon Geçidi eski. Redmi uygulamasını güncelle.")
            return
        }
        compatibility = serverProtocol > BridgeProtocol.version ? .degraded : .supported
        if let addresses = hello.addresses, pairing == nil || hello.gatewayId == pairing?.gatewayId {
            remember(addresses.map { Endpoint(host: $0, port: currentPort) } + [Endpoint(host: currentHost, port: currentPort)])
        }
        guard let nonceText = hello.nonce, let nonce = Data(base64Encoded: nonceText) else { return }
        serverNonce = nonce

        if let code = pendingPairCode {
            let clientNonce = BridgeCrypto.random(16)
            self.clientNonce = clientNonce
            pairClientId = pairing?.clientId ?? UUID().uuidString
            let proof = BridgeCrypto.hmac(Data(code.utf8), Data("pair".utf8), nonce, clientNonce)
            sendPlain([
                "type": "pair",
                "clientId": pairClientId,
                "clientName": UIDevice.current.name,
                "nonce": clientNonce.base64EncodedString(),
                "proof": proof.base64EncodedString()
            ])
            return
        }
        guard let pairing else {
            closeSocket()
            link = .unpaired
            return
        }
        if let gatewayId = hello.gatewayId, gatewayId != pairing.gatewayId {
            log("Ignoring a different Redmi on this network")
            ignoredHosts.insert(currentHost)
            closeSocket()
            scheduleReconnect(delay: 3)
            return
        }
        authenticate(pairing: pairing, serverNonce: nonce)
    }

    private func setIncompatible(_ message: String) {
        compatibility = .incompatible(message)
        link = .incompatible(message)
        cancelReconnect()
        closeSocket()
        log("Incompatible protocol")
    }

    private func handlePairResult(_ result: PairResultMessage) {
        guard let code = pendingPairCode, let serverNonce, let clientNonce else { return }
        let socket = webSocketTask
        guard result.success,
              let sealedKey = result.key.flatMap({ Data(base64Encoded: $0) }),
              let gatewayProof = result.gatewayProof.flatMap({ Data(base64Encoded: $0) }),
              let gatewayId = result.gatewayId else {
            pairingFailed(code: result.code)
            return
        }
        Task { @MainActor in
            let wrapKey = await Task.detached(priority: .userInitiated) {
                BridgeCrypto.pbkdf2(code, salt: serverNonce + clientNonce)
            }.value
            guard let wrapKey,
                  let key = BridgeCrypto.open(wrapKey, sealedKey, aad: Data("pair".utf8)),
                  key.count == BridgeCrypto.keyBytes,
                  BridgeCrypto.constantTimeEquals(
                    BridgeCrypto.hmac(key, Data("gw".utf8), clientNonce, serverNonce), gatewayProof) else {
                self.pairingFailed(code: "bad_gateway")
                return
            }
            let newPairing = GatewayPairing(clientId: self.pairClientId, key: key, gatewayId: gatewayId, pairedAt: Date())
            PairingKeychain.save(newPairing)
            UserDefaults.standard.removeObject(forKey: self.kLegacyToken)
            self.pairing = newPairing
            self.pendingPairCode = nil
            self.migratingLegacyCode = false
            self.pairingError = nil
            self.history.bind(gatewayId: gatewayId)
            BLECentralManager.shared.setGateway(id: gatewayId)
            CallWakeMonitor.shared.refresh()
            self.log("Paired with the Redmi")
            self.link = .connecting
            if let socket, self.webSocketTask === socket, self.serverNonce == serverNonce {
                // The same connection continues with keyed authentication.
                self.authenticate(pairing: newPairing, serverNonce: serverNonce)
            } else {
                // The connection changed while the key was being unwrapped; start a fresh one.
                self.reconnectNow(reason: "paired")
            }
        }
    }

    private func pairingFailed(code: String?) {
        if pairing != nil {
            // An earlier attempt on another connection already paired; just connect with it.
            pendingPairCode = nil
            migratingLegacyCode = false
            closeSocket()
            reconnectNow(reason: "already paired")
            return
        }
        let wasMigration = migratingLegacyCode
        pendingPairCode = nil
        migratingLegacyCode = false
        closeSocket()
        cancelReconnect()
        if wasMigration {
            // The stored code is no longer the Redmi's current code; ask once.
            UserDefaults.standard.removeObject(forKey: kLegacyToken)
            pairingError = "Güncellemeden sonra bir kez eşleştirme gerekiyor."
        } else {
            switch code {
            case "wrong_code": pairingError = "Kod eşleşmedi. Redmi'deki güncel kodu gir."
            case "locked_out": pairingError = "Çok fazla hatalı deneme. Bir dakika sonra tekrar dene."
            default: pairingError = "Eşleştirme tamamlanamadı. Tekrar dene."
            }
        }
        link = pairing == nil ? .unpaired : .offline("Eşleştirme başarısız")
        log("Pairing failed: \(code ?? "unknown")")
    }

    private func authenticate(pairing: GatewayPairing, serverNonce: Data) {
        let clientNonce = BridgeCrypto.random(16)
        self.clientNonce = clientNonce
        let proof = BridgeCrypto.hmac(pairing.key, Data("auth".utf8), serverNonce, clientNonce)
        sendPlain([
            "type": "auth",
            "protocol": BridgeProtocol.version,
            "clientId": pairing.clientId,
            "nonce": clientNonce.base64EncodedString(),
            "proof": proof.base64EncodedString()
        ])
    }

    private func handleAuthResult(_ result: AuthResultMessage) {
        guard let pairing, let serverNonce, let clientNonce else { return }
        guard result.success else {
            switch result.code {
            case "not_paired", "bad_proof":
                // The Redmi no longer has this iPhone's key (pairing removed there).
                PairingKeychain.delete()
                self.pairing = nil
                CallWakeMonitor.shared.refresh()
                history.reset()
                closeSocket()
                cancelReconnect()
                pairingError = "Redmi bu iPhone'u artık tanımıyor. Redmi'deki kodla yeniden eşleştir."
                link = .unpaired
            case "locked_out":
                closeSocket()
                link = .offline("Çok fazla deneme; bir dakika bekleniyor")
                scheduleReconnect(delay: 61)
            default:
                closeSocket()
                scheduleReconnect()
            }
            log("Authentication refused: \(result.code ?? "unknown")")
            return
        }
        let expected = BridgeCrypto.hmac(pairing.key, Data("gw".utf8), clientNonce, serverNonce)
        guard let proofText = result.gatewayProof, let proof = Data(base64Encoded: proofText),
              BridgeCrypto.constantTimeEquals(expected, proof) else {
            // Something answered that does not hold the key: never trust it.
            log("Gateway proof mismatch; connection closed")
            ignoredHosts.insert(currentHost)
            closeSocket()
            scheduleReconnect()
            return
        }
        let sessionKey = BridgeCrypto.hmac(pairing.key, Data("session".utf8), serverNonce, clientNonce)
        channel = SecureChannel(key: BridgeCrypto.derive(sessionKey, "ws"))
        mediaKey = BridgeCrypto.derive(sessionKey, "media")
        state = .authenticated
        link = .ready
        reconnectAttempt = 0
        let defaults = UserDefaults.standard
        defaults.set(currentHost, forKey: kLastHost)
        defaults.set(currentPort, forKey: kLastPort)
        log("Connected to the Redmi")
        CallKitManager.shared.handleGatewayLinkRestored()
        sendPushToken()
        startPing()
        send(["type": "get_state"])
        requestSync()
    }

    func requestSync() {
        guard state == .authenticated else { return }
        syncCallsHasMore = false
        send(["type": "sync", "callsAfter": history.callsSeq, "smsAfter": history.smsSeq])
    }

    // MARK: - Calls

    private func applyCallEvent(_ event: CallEventMessage) {
        let callState = GSMCallState(rawValue: event.state) ?? .idle
        if callState == .idle || callState == .disconnected {
            currentCall = nil
            lastCallError = nil
            audioLocation = .redmi
            audioNotice = nil
        } else {
            let existing = currentCall
            currentCall = GSMCall(
                id: existing?.id ?? UUID(),
                rawCallId: event.callId ?? existing?.rawCallId ?? "",
                number: event.number ?? existing?.number ?? "",
                state: callState,
                isIncoming: event.direction.map { $0 == "incoming" } ?? existing?.isIncoming ?? (callState == .ringing),
                connectedAt: callState == .active ? (existing?.connectedAt ?? Date()) : existing?.connectedAt
            )
        }
        onCallEvent?(event)
    }

    private func handleStateResponse(_ response: StateResponseMessage) {
        if let call = response.call {
            applyCallEvent(CallEventMessage(state: call.state, number: call.number, callId: call.callId, direction: call.direction))
        } else if let previous = currentCall {
            applyCallEvent(CallEventMessage(state: "disconnected", number: nil, callId: previous.rawCallId))
        }
    }

    /// Call state from Bluetooth. In the foreground the network link is authoritative; in the
    /// background (network link closed, see didEnterBackground) Bluetooth is how a locked iPhone
    /// learns about calls.
    private func applyBluetoothCallState(_ update: BLECallState) {
        log("Bluetooth call state: \(update.state)")
        guard !(state == .authenticated && UIApplication.shared.applicationState == .active) else { return }
        if (update.smsSeq ?? 0) > history.smsSeq || (update.callsSeq ?? 0) > history.callsSeq {
            catchUp(reason: "new messages or calls")
        }
        if update.state == "idle" {
            // Notifications can coalesce; "idle" alone still means the call is over.
            if let previous = currentCall {
                applyCallEvent(CallEventMessage(state: "disconnected", number: nil, callId: previous.rawCallId))
            }
            return
        }
        if let wid = update.waitingCallId, !wid.isEmpty {
            waitingCall = GSMCall(rawCallId: wid, number: update.waitingNumber ?? "", state: .ringing, isIncoming: true)
            CallKitManager.shared.handleWaitingCall(callId: wid, number: update.waitingNumber, ringing: true)
        } else if let previousWaiting = waitingCall {
            waitingCall = nil
            CallKitManager.shared.handleWaitingCall(callId: previousWaiting.rawCallId, number: nil, ringing: false)
        }
        applyCallEvent(CallEventMessage(state: update.state, number: update.number, callId: update.callId,
                                        direction: update.direction))
        if update.state == "ringing" || update.state == "active" { reconnectNow(reason: "Bluetooth call state") }
    }

    private func handleAck(_ ack: AckMessage) {
        guard !ack.success else { return }
        switch ack.action {
        case "dial":
            lastCallError = "Arama Redmi'de başlatılamadı. Numarayı ve hattı kontrol et."
            CallKitManager.shared.handleDialFailed()
        case "answer":
            lastCallError = "Arama Redmi'de yanıtlanamadı."
        default:
            break
        }
    }

    private func handleAudioState(_ audio: AudioStateMessage) {
        switch audio.owner ?? (audio.running ? "iphone" : "redmi") {
        case "iphone":
            audioLocation = .iphone
            audioNotice = nil
        case "preparing_iphone":
            audioLocation = .movingToIPhone
        default:
            let wasOnIPhone = audioLocation != .redmi
            audioLocation = .redmi
            CallKitManager.shared.handleAudioReturnedToRedmi(reason: audio.detail)
            if wasOnIPhone, currentCall?.state.isLive == true {
                audioNotice = Self.fallbackText(audio.detail)
            }
        }
    }

    private static func fallbackText(_ reason: String) -> String? {
        switch reason {
        case "manual_redmi", "iphone_stopped", "call_ended", "idle": return nil
        case "iphone_audio_lost", "iphone_not_receiving", "iphone_disconnected", "iphone_reconnected":
            return "Bağlantı zayıfladı; görüşme Redmi'ye döndü."
        default:
            return "Görüşme sesi Redmi'ye döndü."
        }
    }

    // MARK: - Commands

    func sendAnswer(callId: String) { control("answer", callId: callId) }
    func sendReject(callId: String) { control("reject", callId: callId) }
    func sendHangup(callId: String) { control("hangup", callId: callId) }

    /// Tells the Redmi this iPhone shows the incoming call, so it can ring quietly.
    func sendRingingAck(callId: String) { control("ringing_ack", callId: callId) }

    private func control(_ action: String, callId: String) {
        // An answer or decline must reach the Redmi, or the other phone keeps ringing. A sealed
        // frame is dropped silently when the socket or its key is not ready yet, so the Bluetooth
        // link carries the command in that case instead of the command being lost.
        if state == .authenticated, sendOverSealedLink(["type": action, "callId": callId]) { return }
        if BLECentralManager.shared.isReady {
            BLECentralManager.shared.sendCallControl(action: action, callId: callId) { [weak self] success in
                self?.log("Bluetooth \(action): \(success ? "ok" : "failed")")
            }
        } else {
            log("\(action) not sent: Redmi unreachable")
        }
    }

    /// Sends one command over the authenticated socket; false when nothing could carry it.
    private func sendOverSealedLink(_ payload: [String: Any]) -> Bool {
        guard let task = webSocketTask, let channel,
              let json = try? JSONSerialization.data(withJSONObject: payload),
              let frame = channel.seal(json) else { return false }
        task.send(.string(frame)) { _ in }
        return true
    }

    func sendDial(number: String) {
        guard state == .authenticated else {
            lastCallError = "Redmi bağlantısı yok. Arama başlatılamadı."
            return
        }
        lastCallError = nil
        send(["type": "dial", "number": number])
    }

    func sendAudioStart() {
        guard state == .authenticated else { return }
        audioNotice = nil
        send(["type": "audio_start"])
    }

    func sendAudioStop() {
        guard state == .authenticated else { return }
        send(["type": "audio_stop"])
    }

    func sendAudioToRedmi() {
        guard state == .authenticated else { return }
        send(["type": "audio_to_redmi"])
    }

    func sendDtmf(digit: String, callId: String?) {
        guard state == .authenticated else { return }
        var payload: [String: Any] = ["type": "dtmf", "digit": digit]
        if let callId { payload["callId"] = callId }
        send(payload)
    }

    /// Queues an SMS; its status in HistoryStore follows the Redmi's real send result.
    func sendSms(recipient: String, message: String) {
        let requestId = UUID().uuidString
        history.addPending(requestId: requestId, address: recipient, body: message)
        guard state == .authenticated else {
            history.completePending(requestId: requestId, success: false)
            return
        }
        send(["type": "send_sms", "recipient": recipient, "message": message, "requestId": requestId])
        smsTimeouts[requestId] = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 90_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.smsTimeouts.removeValue(forKey: requestId)
            self.history.completePending(requestId: requestId, success: false)
        }
    }

    func retrySms(_ message: SmsRecord) {
        history.removeMessage(id: message.id)
        sendSms(recipient: message.address, message: message.body)
    }

    func requestDiagnostics() {
        remoteDiagnostics = nil
        send(["type": "diagnostics"])
    }

    func send(_ payload: [String: Any]) {
        guard let task = webSocketTask, let channel,
              let json = try? JSONSerialization.data(withJSONObject: payload),
              let frame = channel.seal(json) else { return }
        task.send(.string(frame)) { _ in }
    }

    private func sendPlain(_ payload: [String: Any]) {
        guard let task = webSocketTask,
              let json = try? JSONSerialization.data(withJSONObject: payload) else { return }
        task.send(.string(String(decoding: json, as: UTF8.self))) { _ in }
    }

    // MARK: - Environment

    private func setupPathMonitoring() {
        pathMonitor.pathUpdateHandler = { path in
            Task { @MainActor in
                let connection = GatewayConnection.shared
                connection.isWiFiConnected = path.usesInterfaceType(.wifi)
                connection.isNetworkViable = path.status == .satisfied
                let signature = "\(path.status)|" + path.availableInterfaces.map { $0.name }.joined(separator: ",")
                guard signature != connection.lastPathSignature else { return }
                connection.lastPathSignature = signature
                if path.status == .satisfied {
                    // New network: find the Redmi's addresses again and check the current link.
                    connection.ignoredHosts.removeAll()
                    connection.discovery.stop()
                    connection.discovery.start()
                    BLECentralManager.shared.refreshDeviceInfo()
                    connection.validateLink()
                    connection.reconnectNow(reason: "network changed")
                } else if connection.state != .authenticated, connection.link != .unpaired {
                    connection.link = .offline("Ağ bağlantısı yok")
                }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "com.ilkay.redmigateway.path", qos: .utility))
    }

    private func setupLifecycleObservers() {
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in GatewayConnection.shared.enterBackground() }
        }
        NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in
                let connection = GatewayConnection.shared
                connection.discovery.start()
                connection.reconnectNow(reason: "app opened")
                connection.requestSync()
            }
        }
    }

    /// iOS suspends the app shortly after it leaves the screen, and a suspended socket looks
    /// alive while nothing reads it. Without a call, close it deliberately: Bluetooth wakes the
    /// app for calls, and the journals catch up when the app returns.
    private func enterBackground() {
        guard currentCall == nil, !CallKitManager.shared.hasCalls else { return }
        cancelReconnect()
        if webSocketTask != nil {
            closeSocket()
            log("Network link closed in the background; Bluetooth stays connected")
        }
        if pairing != nil { link = .searching }
    }

    /// The Redmi's beacon woke the app: a call rings, or the app had lost its links (for example
    /// after being swiped away). Fetch state and journals; in the background the network link
    /// closes again afterwards unless a call turned up, and Bluetooth stays connected.
    func wakeFromBeacon() {
        catchUp(reason: "beacon wake")
    }

    /// Bluetooth announced new SMS or calls while the app sleeps: connect, fetch, notify, sleep.
    private func catchUp(reason: String) {
        guard pairing != nil else { return }
        if catchUpTask == .invalid {
            catchUpTask = UIApplication.shared.beginBackgroundTask(withName: "catch-up") {
                Task { @MainActor in GatewayConnection.shared.endCatchUpTask() }
            }
        }
        closeAfterCatchUp = UIApplication.shared.applicationState == .background && currentCall == nil
        if state == .authenticated {
            if UIApplication.shared.applicationState == .active {
                requestSync()
                return
            }
            // A socket kept from before suspension may be dead; start clean.
            closeSocket()
        }
        reconnectNow(reason: reason)
    }

    private func finishCatchUp() {
        Notifier.shared.updateBadge()
        guard catchUpTask != .invalid else { return }
        if closeAfterCatchUp, UIApplication.shared.applicationState == .background, currentCall == nil {
            closeSocket()
            if pairing != nil { link = .searching }
        }
        closeAfterCatchUp = false
        endCatchUpTask()
    }

    private func endCatchUpTask() {
        guard catchUpTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(catchUpTask)
        catchUpTask = .invalid
    }

    // MARK: - Call waiting

    private func handleWaiting(_ message: CallWaitingMessage) {
        if message.state == "ringing", let callId = message.callId, !callId.isEmpty {
            waitingCall = GSMCall(rawCallId: callId, number: message.number ?? "", state: .ringing, isIncoming: true)
        } else {
            waitingCall = nil
        }
        CallKitManager.shared.handleWaitingCall(callId: message.callId ?? "", number: message.number,
                                                ringing: message.state == "ringing")
    }

    // MARK: - Away from home

    /// PushKit token for this iPhone; the Redmi uses it to ring the iPhone through Apple.
    func setPushToken(_ token: String) {
        pushToken = token
        sendPushToken()
    }

    /// Ordinary notification token; the Redmi sends SMS and missed-call alerts through Apple with it.
    func setAlertPushToken(_ token: String) {
        alertPushToken = token
        sendPushToken()
    }

    private func sendPushToken() {
        guard state == .authenticated else { return }
        if let pushToken {
            send(["type": "push_token", "token": pushToken, "environment": "production"])
        }
        if let alertPushToken {
            send(["type": "push_alert_token", "token": alertPushToken, "environment": "production"])
        }
    }

    /// Technical log for Advanced > Diagnostics. Never contains numbers, message text or keys.
    func log(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        logs.append("[\(formatter.string(from: Date()))] \(message)")
        if logs.count > 200 { logs.removeFirst(logs.count - 200) }
    }
}

/// Discovers the Redmi on the local network with Bonjour (_redmigateway._tcp).
public final class NWGatewayDiscovery {
    public var onEndpointFound: ((_ host: String, _ port: Int, _ deviceId: String?) -> Void)?

    private var browser: NWBrowser?
    private var activeResolvers: [NWEndpoint: NWConnection] = [:]
    private let queue = DispatchQueue(label: "com.ilkay.redmigateway.discovery", qos: .utility)
    public private(set) var isSearching = false

    public init() {}

    public func start() {
        queue.async { [weak self] in
            guard let self, !self.isSearching else { return }
            self.isSearching = true
            let parameters = NWParameters()
            parameters.includePeerToPeer = true
            let browser = NWBrowser(for: .bonjour(type: "_redmigateway._tcp", domain: "local."), using: parameters)
            self.browser = browser
            browser.stateUpdateHandler = { [weak self] state in
                switch state {
                case .failed, .cancelled:
                    self?.queue.async { self?.isSearching = false }
                default:
                    break
                }
            }
            browser.browseResultsChangedHandler = { [weak self] _, changes in
                guard let self else { return }
                for change in changes {
                    switch change {
                    case .added(let result): self.resolve(result: result)
                    case .changed(_, let result, _): self.resolve(result: result)
                    default: break
                    }
                }
            }
            browser.start(queue: self.queue)
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self, self.isSearching else { return }
            self.isSearching = false
            self.browser?.cancel()
            self.browser = nil
            for (_, connection) in self.activeResolvers { connection.cancel() }
            self.activeResolvers.removeAll()
        }
    }

    private func resolve(result: NWBrowser.Result) {
        var deviceId: String?
        if case let .bonjour(txtRecord) = result.metadata { deviceId = txtRecord["id"] }
        if deviceId == nil, case let .service(name, _, _, _) = result.endpoint, name.hasPrefix("RedmiGateway-") {
            deviceId = String(name.dropFirst("RedmiGateway-".count))
        }
        activeResolvers.removeValue(forKey: result.endpoint)?.cancel()

        let connection = NWConnection(to: result.endpoint, using: .tcp)
        activeResolvers[result.endpoint] = connection
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 3.0)
        timer.setEventHandler { [weak self, weak connection] in
            connection?.cancel()
            self?.activeResolvers.removeValue(forKey: result.endpoint)
        }
        timer.resume()

        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                timer.cancel()
                if let remote = connection.currentPath?.remoteEndpoint, case let .hostPort(host, port) = remote {
                    var hostString: String
                    switch host {
                    case .ipv4(let ip): hostString = "\(ip)"
                    case .ipv6(let ip): hostString = "\(ip)"
                    case .name(let name, _): hostString = name
                    @unknown default: hostString = "\(host)"
                    }
                    if let percent = hostString.firstIndex(of: "%") { hostString = String(hostString[..<percent]) }
                    let portNumber = Int(port.rawValue)
                    if !hostString.isEmpty, portNumber > 0 {
                        DispatchQueue.main.async { self.onEndpointFound?(hostString, portNumber, deviceId) }
                    }
                }
                connection.cancel()
                self.activeResolvers.removeValue(forKey: result.endpoint)
            case .failed, .cancelled:
                timer.cancel()
                self.activeResolvers.removeValue(forKey: result.endpoint)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}
