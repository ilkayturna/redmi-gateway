import AVFoundation
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
    /// True while the Keychain could not be opened because this iPhone has not been unlocked since
    /// it restarted. The pairing is still there; it is read again as soon as the iPhone is unlocked,
    /// and until then nothing treats this iPhone as unpaired.
    @Published private(set) var pairingLocked = false
    /// A second call ringing during the current one.
    @Published private(set) var waitingCall: GSMCall?

    public var onCallEvent: ((CallEventMessage) -> Void)?
    public var connectedHost: String { currentHost }
    /// Key for this connection's sealed call audio; nil until authenticated.
    private(set) var mediaKey: Data?

    /// A locked Keychain counts as paired: showing the pairing screen then would ask for a code
    /// the person does not need, for a pairing that is only waiting for the first unlock.
    public var isPaired: Bool { pairing != nil || pairingLocked }
    public var isAuthenticated: Bool { state == .authenticated }
    /// The healthy resting state: the network link sleeps on purpose while the app is off screen,
    /// and the paired Redmi is still in touch over Bluetooth, which wakes this iPhone for calls and
    /// messages. Views that show it also observe BLECentralManager so they follow the Bluetooth link.
    var restsOnBluetooth: Bool {
        link == .searching && pairing != nil && BLECentralManager.shared.isConnected
    }
    var isIPhoneAudioConfirmed: Bool { audioLocation == .iphone }

    private let kLastHost = "gateway_ip"
    private let kLastPort = "gateway_port"
    private let kManualHost = "manual_gateway_ip"
    private let kManualPort = "manual_gateway_port"
    private let kLegacyToken = "pairing_token"
    private let kKnownAddresses = "gateway_addresses"

    /// How long the socket is held after the app leaves the screen. iOS grants about half a minute
    /// of background execution, and this stays inside it: long enough to press "iPhone'a aktar" on
    /// the Redmi after putting the iPhone down, short enough that nothing runs while it sleeps.
    private static let backgroundGrace: TimeInterval = 25

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
    /// Retried sends waiting for the next journal sync before they may go out again; see retrySms.
    private var smsRetriesAfterSync = Set<String>()
    /// A decline or hang-up that reached neither link; see keepCallCommand.
    private var pendingCallCommand: (action: String, callId: String, at: Date)?
    /// When this iPhone last ran a "carry the call audio" request.
    ///
    /// The Redmi broadcasts the request on every authenticated socket, and this iPhone genuinely
    /// holds more than one for a moment while it reconnects. Measured on 17.10.2026 at 14:10: the
    /// single request the Redmi sent reached the app three times, and every copy ran the whole
    /// transfer again - audio_start, engine start, an audio route change and dropped downlink
    /// frames - so one 1.9 s handover was really three competing starts. An identical request that
    /// arrives within two seconds of the last one is now ignored; the Redmi re-sends a kept
    /// request only after a real reconnect, which takes longer than this window.
    private var lastTransferRequestAt: Date?
    /// Call commands waiting for their ack, oldest first; see startControlWatchdog.
    private var controlWatchdogs: [(key: String, action: String, task: Task<Void, Never>)] = []
    private var lastPathSignature = ""
    private var catchUpTask: UIBackgroundTaskIdentifier = .invalid
    private var closeAfterCatchUp = false
    /// The bounded grace that keeps the socket alive just after the app leaves the screen.
    private var idleLinkTask: UIBackgroundTaskIdentifier = .invalid
    /// What woke the app, and when, for the wake latency line.
    private var wakeAt: Date?
    private var wakeReason: String?
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
        switch PairingKeychain.read() {
        case .found(let stored): pairing = stored
        case .absent: break
        case .locked: pairingLocked = true
        }
        link = (pairing == nil && !pairingLocked) ? .unpaired : .searching
        installTelemetryObservers()

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
        if pairingLocked { log("Pairing locked until the first unlock after a restart; waiting for it") }
        // Sends saved as pending by an earlier run lost their result window with that run.
        for pending in history.expirePendingSends(timeout: Self.smsResultTimeout) {
            startSmsTimeout(requestId: pending.requestId, after: pending.remaining)
        }
        if pairing == nil, !pairingLocked, let legacy = UserDefaults.standard.string(forKey: kLegacyToken),
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
        pairingLocked = false
        pendingPairCode = nil
        history.reset()
        BLECentralManager.shared.forgetGateway()
        CallWakeMonitor.shared.refresh()
        cancelReconnect()
        closeSocket()
        link = .unpaired
        log("Pairing removed")
    }

    /// Reads the pairing again when the Keychain was locked at the last read.
    ///
    /// Called when iOS reports that protected data is available, and before anything that needs
    /// the key (a Bluetooth state, a VoIP push, a beacon wake, the app opening): a suspended app
    /// does not receive the unlock notification, so it would otherwise stay without its pairing
    /// until the next launch. Cheap when nothing was locked.
    func reloadPairingIfLocked(reason: String) {
        guard pairingLocked else { return }
        switch PairingKeychain.read() {
        case .locked:
            return
        case .absent:
            pairingLocked = false
            if pendingPairCode == nil { link = .unpaired }
            log("Keychain readable after the unlock (\(reason)); no pairing stored")
        case .found(let stored):
            pairingLocked = false
            pairing = stored
            log("Pairing read after the unlock (\(reason))")
            history.bind(gatewayId: stored.gatewayId)
            CallWakeMonitor.shared.refresh()
            guard started else { return }
            // Exactly what a launch with a readable pairing does, through the wake path: Bluetooth
            // reads the call state again, now with the key, and the link connects, fetches what
            // was missed and goes back to sleep in the background.
            discovery.start()
            BLECentralManager.shared.start()
            catchUp(reason: "pairing unlocked")
        }
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
        stopTelemetry()
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
        // In the background only a call justifies reconnecting; Bluetooth covers the rest. A call
        // CallKit shows counts even before the link knows it: a ring from an Apple push has no
        // current call yet, and giving up after one failed attempt left it ringing with no way to
        // learn that the caller had hung up.
        let callInProgress = currentCall != nil || CallKitManager.shared.hasCalls
        guard UIApplication.shared.applicationState != .background || callInProgress else {
            log("Reconnect not scheduled: in the background without a call")
            return
        }
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
                                                      key: ready.sealed == true ? mediaKey : nil,
                                                      redundancy: ready.redundancy ?? 0)
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
                    resendRetriedSms()
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
                if !result.success, smsRetriesAfterSync.contains(requestId) {
                    // The first attempt is reported failed while its retry waits for the journal
                    // check: nothing went out, so the retry goes ahead as planned.
                    log("SMS send failed: \(result.error ?? "unknown"); its retry goes ahead")
                } else {
                    smsTimeouts.removeValue(forKey: requestId)?.cancel()
                    // A success for a retry still waiting for its journal check settles it, and
                    // the message is not sent a second time.
                    smsRetriesAfterSync.remove(requestId)
                    history.completePending(requestId: requestId, success: result.success)
                    if !result.success { log("SMS send failed: \(result.error ?? "unknown")") }
                }
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
            if let error = decode(ErrorMessage.self, data) {
                log("Redmi error: \(error.code ?? error.message ?? "unknown")")
                // These two codes answer audio_start only (docs/PROTOCOL.md).
                if let code = error.code, code == "no_call" || code == "audio_busy" {
                    CallKitManager.shared.audioStartRefused(code: code)
                }
            }
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
            "proof": proof.base64EncodedString(),
            // The Redmi records this. Without it a ring that failed cannot be tied to a build, and
            // "the new one is installed" stays a guess made from the TestFlight screen.
            "appVersion": appVersionNumber,
            "appBuild": appBuildNumber
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
        reportWakeLatency()
        // The wake is over: stop spending radio time on beacon ranging.
        CallWakeMonitor.shared.stopRanging()
        CallKitManager.shared.handleGatewayLinkRestored()
        sendPushToken()
        startPing()
        startTelemetry()
        send(["type": "get_state"])
        requestSync()
    }

    func requestSync() {
        guard state == .authenticated else { return }
        syncCallsHasMore = false
        send(["type": "sync", "callsAfter": history.callsSeq, "smsAfter": history.smsSeq])
    }

    /// Remembers what woke the app, so the link-up log can say how long the wake took.
    ///
    /// The person's complaint was "the iPhone never hears about it until I open the app"; the only
    /// way to tell a slow wake from a missed one is to measure region entry (or a Bluetooth
    /// notification) against the moment the socket authenticates.
    func noteWake(reason: String) {
        wakeAt = Date()
        wakeReason = reason
    }

    private func reportWakeLatency() {
        guard let started = wakeAt, let reason = wakeReason else { return }
        wakeAt = nil
        wakeReason = nil
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        log("Wake latency: \(reason) -> authenticated in \(ms) ms")
    }

    // MARK: - Calls

    private func applyCallEvent(_ event: CallEventMessage) {
        let callState = GSMCallState(rawValue: event.state) ?? .idle
        if callState == .idle || callState == .disconnected {
            currentCall = nil
            lastCallError = nil
            // The call a kept decline or hang-up was meant for is over.
            pendingCallCommand = nil
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
            // A failure from an earlier step must not stay on screen once the call is up: after one
            // late duplicate answer command the person saw "could not be answered" for the whole
            // call even though it was connected.
            if callState == .active { lastCallError = nil }
        }
        onCallEvent?(event)
    }

    private func handleStateResponse(_ response: StateResponseMessage) {
        if let call = response.call {
            applyCallEvent(CallEventMessage(state: call.state, number: call.number, callId: call.callId, direction: call.direction))
        } else {
            if let previous = currentCall {
                applyCallEvent(CallEventMessage(state: "disconnected", number: nil, callId: previous.rawCallId))
            }
            // A ring that came only from an Apple push is not the current call, so the line above
            // never reaches it.
            CallKitManager.shared.endRingingCallsWithoutRedmiCall(source: "state")
        }
        // The state answers the get_state sent right after authentication, so this is the first
        // moment a kept decline or hang-up can be checked against the Redmi's real call.
        retryPendingCallCommand(liveCall: response.call)
    }

    /// Call state from Bluetooth. In the foreground the network link is authoritative; in the
    /// background (network link closed, see didEnterBackground) Bluetooth is how a locked iPhone
    /// learns about calls.
    private func applyBluetoothCallState(_ update: BLECallState) {
        log("Bluetooth call state: \(update.state)")
        // The network link is authoritative in the foreground, but only while it is really carrying
        // messages. A socket that merely looks authenticated after the network changed would hide a
        // new incoming call, so a ringing call is applied from Bluetooth as well; the call id
        // deduplicates the second copy when both transports deliver it.
        let networkIsAuthoritative = state == .authenticated && UIApplication.shared.applicationState == .active
        if networkIsAuthoritative && update.state != "ringing" { return }
        if (update.smsSeq ?? 0) > history.smsSeq || (update.callsSeq ?? 0) > history.callsSeq {
            catchUp(reason: "new messages or calls")
        }
        if update.state == "idle" {
            // Notifications can coalesce; "idle" alone still means the call is over.
            if let previous = currentCall {
                applyCallEvent(CallEventMessage(state: "disconnected", number: nil, callId: previous.rawCallId))
            }
            // Including a ring that came only from an Apple push and never became the current call.
            CallKitManager.shared.endRingingCallsWithoutRedmiCall(source: "Bluetooth")
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
        // Every live call brings the socket up, not just a ringing or active one: a call dialed on
        // the Redmi reaches this iPhone while it is still dialing, and waiting for "active" kept
        // the person from hearing about it - and from moving the audio over - for 18.5 s.
        if ["ringing", "dialing", "connecting", "active"].contains(update.state) {
            reconnectNow(reason: "Bluetooth call state")
        }
    }

    private func handleAck(_ ack: AckMessage) {
        // The command reached the Redmi; the watchdog must not send it over Bluetooth as well.
        settleControlWatchdog(action: ack.action)
        // A kept decline or hang-up has arrived too; the Redmi's answer to it stands.
        if let kept = pendingCallCommand, kept.action == ack.action { pendingCallCommand = nil }
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
        // "iPhone'a aktar" on the Redmi's call screen asks this iPhone to carry the conversation.
        // The request already rides on the audio_state message the Redmi sends for every owner
        // change, so no new type, no new command and no protocol bump is needed: an older iPhone
        // reads it as "the audio stays on the Redmi" and behaves exactly as before.
        if audio.detail == "request_iphone_audio" {
            let now = Date()
            if let previous = lastTransferRequestAt, now.timeIntervalSince(previous) < 2.0 {
                log("Ignoring a repeated take-the-audio request (\(Int(now.timeIntervalSince(previous) * 1000)) ms after the last one)")
                return
            }
            lastTransferRequestAt = now
            log("The Redmi asked this iPhone to take the call audio")
            CallKitManager.shared.takeCallOnIPhone()
            return
        }
        switch audio.owner ?? (audio.running ? "iphone" : "redmi") {
        case "iphone":
            audioLocation = .iphone
            audioNotice = nil
            // The audio is here: the post-answer retry loop can stop asking.
            CallKitManager.shared.audioMovedToIPhone()
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
        // An answer or decline must reach the Redmi, or the other phone keeps ringing. The sealed
        // frame is lost both when the socket or its key is not ready (checked here) and when the
        // socket has died without the handshake noticing, which shows up only in the send
        // completion; the Bluetooth link carries the command in either case.
        if state == .authenticated, sendOverSealedLink(["type": action, "callId": callId]) {
            startControlWatchdog(action: action, callId: callId)
            return
        }
        sendOverBluetooth(action: action, callId: callId, reportFailure: Self.isEndCommand(action))
    }

    /// A decline or hang-up that cannot reach the Redmi leaves its call going on there, so the
    /// person is told and the command is kept for the link; see keepCallCommand.
    private static func isEndCommand(_ action: String) -> Bool {
        action == "reject" || action == "hangup"
    }

    /// Sends one command over the authenticated socket; false when nothing could carry it.
    private func sendOverSealedLink(_ payload: [String: Any]) -> Bool {
        guard let task = webSocketTask, let channel,
              let json = try? JSONSerialization.data(withJSONObject: payload),
              let frame = channel.seal(json) else { return false }
        let action = payload["type"] as? String ?? ""
        let callId = payload["callId"] as? String ?? ""
        task.send(.string(frame)) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in
                self?.log("\(action) frame not sent; trying Bluetooth")
                self?.sendOverBluetooth(action: action, callId: callId,
                                        reportFailure: GatewayConnection.isEndCommand(action))
            }
        }
        return true
    }

    /// The Redmi answers every command with an ack. No ack means the frame died on the way even
    /// though the socket accepted it, so the command goes over Bluetooth once: without this a lost
    /// answer left the other phone ringing while this iPhone showed the call as answered.
    private func startControlWatchdog(action: String, callId: String) {
        let key = "\(action)|\(callId)"
        cancelControlWatchdog(key: key)
        let task = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard let self, !Task.isCancelled else { return }
            self.cancelControlWatchdog(key: key)
            self.log("No ack for \(action); sending it over Bluetooth")
            self.sendOverBluetooth(action: action, callId: callId, reportFailure: true)
        }
        controlWatchdogs.append((key: key, action: action, task: task))
    }

    /// The ack names the action only and acks arrive in the order the commands were sent, so the
    /// oldest command still waiting for that action is the one it answers.
    private func settleControlWatchdog(action: String) {
        guard let index = controlWatchdogs.firstIndex(where: { $0.action == action }) else { return }
        controlWatchdogs.remove(at: index).task.cancel()
    }

    private func cancelControlWatchdog(key: String) {
        guard let index = controlWatchdogs.firstIndex(where: { $0.key == key }) else { return }
        controlWatchdogs.remove(at: index).task.cancel()
    }

    /// Bluetooth carries a control command when the sealed link cannot.
    private func sendOverBluetooth(action: String, callId: String, reportFailure: Bool = false) {
        // This command's own watchdog is done with, so it is never delivered twice; other commands
        // waiting for an ack of the same action keep their watchdog.
        cancelControlWatchdog(key: "\(action)|\(callId)")
        guard BLECentralManager.shared.isReady else {
            log("\(action) not sent: Redmi unreachable")
            if reportFailure { reportControlFailure(action, callId: callId) }
            return
        }
        BLECentralManager.shared.sendCallControl(action: action, callId: callId) { [weak self] success in
            Task { @MainActor in
                guard let self else { return }
                self.log("Bluetooth \(action): \(success ? "ok" : "failed")")
                if !success, reportFailure { self.reportControlFailure(action, callId: callId) }
            }
        }
    }

    /// The person has to know when a call command never reached the Redmi.
    private func reportControlFailure(_ action: String, callId: String) {
        guard ["answer", "reject", "hangup"].contains(action) else { return }
        guard Self.isEndCommand(action), !callId.isEmpty else {
            lastCallError = "Redmi'ye ulaşılamadı; komut iletilemedi."
            return
        }
        keepCallCommand(action, callId: callId)
    }

    /// Keeps a decline or hang-up that reached neither link and brings the link back for it.
    ///
    /// "Reddet" and "Bitir" on the call screen ended silently when the socket was asleep and
    /// Bluetooth could not carry them either: the call went on ringing or talking on the Redmi
    /// while this screen gave no sign. Now the screen says so, and the command goes out once the
    /// link is up again, if the Redmi still shows that same call (see retryPendingCallCommand).
    private func keepCallCommand(_ action: String, callId: String) {
        // A retry that fails again keeps the time of the first failure, so the 20 s limit in
        // retryPendingCallCommand bounds the whole attempt and nothing repeats for the length of a call.
        var firstFailure = Date()
        if let kept = pendingCallCommand, kept.action == action, kept.callId == callId { firstFailure = kept.at }
        pendingCallCommand = (action: action, callId: callId, at: firstFailure)
        lastCallError = "Redmi'ye ulaşılamadı; bağlantı gelince yeniden denenecek."
        log("\(action) kept until the link is back")
        if state == .authenticated {
            // Only the ack was missing: a fresh state says whether the call is still there. A socket
            // that merely looks authenticated is found dead by the ping and reconnects.
            validateLink()
            send(["type": "get_state"])
        } else {
            reconnectNow(reason: "call command waiting")
        }
    }

    /// Sends a kept decline or hang-up again, but only to the very call it was meant for.
    ///
    /// The Redmi's hang-up falls back to whatever call is live when it does not know the id, so a
    /// command kept from an earlier call must never reach a new one: it goes out only while it is
    /// recent and the Redmi's fresh state shows the same call, still ringing for a decline or still
    /// live for a hang-up.
    private func retryPendingCallCommand(liveCall: StateCallInfo?) {
        guard let pending = pendingCallCommand else { return }
        let callState = liveCall.flatMap { GSMCallState(rawValue: $0.state) } ?? .idle
        let stillApplies = (pending.action == "reject") ? (callState == .ringing) : callState.isLive
        guard Date().timeIntervalSince(pending.at) < 20, liveCall?.callId == pending.callId, stillApplies else {
            pendingCallCommand = nil
            log("Kept \(pending.action) dropped: its call is over or has changed")
            return
        }
        // The command stays kept until its ack arrives (handleAck), so a second failure counts from
        // the first one instead of starting a new 20 s.
        log("Sending the kept \(pending.action) now that the link is back")
        control(pending.action, callId: pending.callId)
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
        // rxRedundancy asks the Redmi to repeat the previous downlink frame in the next packet.
        // Without this field an older Redmi keeps sending type 1; a new one must never send type 2
        // to a build that would ignore the whole packet.
        send(["type": "audio_start", "rxRedundancy": 1])
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

    /// How long a send waits for the Redmi's result before it shows as failed.
    private static let smsResultTimeout: TimeInterval = 90
    /// How long a retried send waits after the journal check before it goes out again; see
    /// resendRetriedSms.
    private static let smsRetryGrace: TimeInterval = 3

    /// Queues an SMS; its status in HistoryStore follows the Redmi's real send result.
    func sendSms(recipient: String, message: String) {
        let requestId = UUID().uuidString
        history.addPending(requestId: requestId, address: recipient, body: message)
        guard state == .authenticated else {
            history.completePending(requestId: requestId, success: false)
            return
        }
        transmitSms(requestId: requestId, recipient: recipient, message: message)
    }

    private func transmitSms(requestId: String, recipient: String, message: String) {
        send(["type": "send_sms", "recipient": recipient, "message": message, "requestId": requestId])
        startSmsTimeout(requestId: requestId, after: Self.smsResultTimeout)
    }

    /// Marks the send failed when no result came in time. A late result for the same requestId
    /// still settles it: completePending turns a failed copy into a sent one.
    private func startSmsTimeout(requestId: String, after delay: TimeInterval) {
        smsTimeouts.removeValue(forKey: requestId)?.cancel()
        let nanoseconds = UInt64(max(1, delay) * 1_000_000_000)
        smsTimeouts[requestId] = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard let self, !Task.isCancelled else { return }
            self.smsTimeouts.removeValue(forKey: requestId)
            self.smsRetriesAfterSync.remove(requestId)
            self.history.completePending(requestId: requestId, success: false)
        }
    }

    /// "Tekrar dene".
    ///
    /// A send that timed out may still have gone out: the Redmi had it and only its result was
    /// lost with the link. Sending it again at once could send the same text twice, so the copy
    /// waits ("Gönderiliyor…") for the next journal sync first. The Redmi records every message it
    /// sends and that entry replaces this copy (HistoryStore.upsertSms), and a result the Redmi kept
    /// for this requestId settles it. Only a copy that is still waiting after both is sent again,
    /// under the same requestId, so whichever result arrives last still lands on the one bubble.
    func retrySms(_ message: SmsRecord) {
        guard message.gatewayId == nil, let requestId = message.requestId else {
            // A failure the Redmi's own journal recorded: nothing went out, so a new send is safe.
            history.removeMessage(id: message.id)
            sendSms(recipient: message.address, message: message.body)
            return
        }
        history.restartPending(requestId: requestId, resent: false)
        smsRetriesAfterSync.insert(requestId)
        startSmsTimeout(requestId: requestId, after: Self.smsResultTimeout)
        log("SMS retry: checking the Redmi's journal before sending again")
        if state == .authenticated {
            requestSync()
        } else {
            // Authentication always starts a sync, which is the check this retry waits for.
            reconnectNow(reason: "SMS retry")
        }
    }

    /// Sends again the retried messages the journal sync did not account for.
    ///
    /// The Redmi delivers a send result it could not hand over earlier on the next authenticated
    /// link, so a short grace after the sync lets such a result arrive first; a retry that a late
    /// success settled is never sent a second time.
    private func resendRetriedSms() {
        guard !smsRetriesAfterSync.isEmpty else { return }
        let waiting = smsRetriesAfterSync
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.smsRetryGrace * 1_000_000_000))
            guard let self else { return }
            for requestId in waiting where self.smsRetriesAfterSync.contains(requestId) {
                guard self.state == .authenticated else { return }
                self.smsRetriesAfterSync.remove(requestId)
                guard let pending = self.history.pendingMessage(requestId: requestId),
                      pending.status == .sending else { continue }
                self.log("SMS retry: not in the Redmi's journal; sending again")
                self.history.restartPending(requestId: requestId, resent: true)
                self.transmitSms(requestId: requestId, recipient: pending.address, message: pending.body)
            }
        }
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
                // Back on screen: the grace has done its job and the link stays.
                connection.endIdleLinkKeepOpen()
                connection.reloadPairingIfLocked(reason: "app opened")
                connection.discovery.start()
                connection.reconnectNow(reason: "app opened")
                connection.requestSync()
            }
        }
        // The first unlock after a restart makes the Keychain readable; a pairing that was locked at
        // launch is picked up here while the app is still running.
        NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in GatewayConnection.shared.reloadPairingIfLocked(reason: "device unlocked") }
        }
    }

    /// iOS suspends the app shortly after it leaves the screen, and a suspended socket looks
    /// alive while nothing reads it. Without a call, hold it for a short grace and then close it
    /// deliberately: a person who puts the iPhone down to press "iPhone'a aktar" on the Redmi still
    /// finds a connected iPhone, and after the grace Bluetooth wakes the app for calls anyway.
    private func enterBackground() {
        guard currentCall == nil, !CallKitManager.shared.hasCalls else { return }
        cancelReconnect()
        guard webSocketTask != nil else {
            if pairing != nil { link = .searching }
            return
        }
        if idleLinkTask == .invalid {
            idleLinkTask = UIApplication.shared.beginBackgroundTask(withName: "idle-link") {
                Task { @MainActor in GatewayConnection.shared.endIdleLink(reason: "iOS ended the grace") }
            }
        }
        log("Network link held for \(Int(Self.backgroundGrace)) s after leaving the screen")
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.backgroundGrace * 1_000_000_000))
            guard let self else { return }
            self.endIdleLink(reason: "grace elapsed")
        }
    }

    /// Closes the idle link once the grace is over, or the moment the app is back on screen.
    private func endIdleLink(reason: String) {
        if idleLinkTask != .invalid {
            UIApplication.shared.endBackgroundTask(idleLinkTask)
            idleLinkTask = .invalid
        }
        guard UIApplication.shared.applicationState != .active else { return }
        guard currentCall == nil, !CallKitManager.shared.hasCalls else { return }
        if webSocketTask != nil {
            closeSocket()
            log("Network link closed after the grace (\(reason)); Bluetooth stays connected")
        }
        if pairing != nil { link = .searching }
    }

    /// The app is on screen again: retire the grace without touching the link.
    private func endIdleLinkKeepOpen() {
        guard idleLinkTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(idleLinkTask)
        idleLinkTask = .invalid
    }

    /// The Redmi's beacon woke the app: a call rings, or the app had lost its links (for example
    /// after being swiped away). Fetch state and journals; in the background the network link
    /// closes again afterwards unless a call turned up, and Bluetooth stays connected.
    func wakeFromBeacon() {
        if pairingLocked {
            // Reading the pairing starts the same catch-up when it succeeds; still locked, there is
            // no key to connect with yet.
            reloadPairingIfLocked(reason: "beacon wake")
            return
        }
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
    ///
    /// Every line also goes to a file. The failure worth explaining happens while the app is in the
    /// background, and iOS can kill it before anyone copies the memory ring: on 22.10.2026 a whole
    /// failed incoming call left nothing readable behind.
    func log(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let line = "[\(formatter.string(from: Date()))] \(message)"
        logs.append(line)
        if logs.count > 200 { logs.removeFirst(logs.count - 200) }
        LogFile.append(line + "\n")
        appendTelemetryLine(message)
    }

    // MARK: - Telemetry to the Redmi

    /// The iPhone has no USB debugging, so its log has to travel to the Redmi or a failed ring leaves
    /// no evidence at all: the in-memory ring dies with the app and nobody can cable the phone to a
    /// computer. These lines are pushed over the same sealed socket and written to a file on the
    /// Redmi, which adb can read. Bounded on purpose - the phone is a communication device first.
    private var clientLogRing: [String] = []
    /// How many entries of the ring have already reached the Redmi.
    private var clientLogDelivered = 0
    /// Lines dropped because the ring wrapped before they were sent.
    private var clientLogDropped = 0
    private var clientLogTimer: Timer?
    private var clientStatusTimer: Timer?
    private var lastClientLogSentAt = Date.distantPast
    private var telemetryObserversInstalled = false
    private static let clientLogRingLimit = 300
    private static let clientLogBatchLimit = 40

    private var appVersionNumber: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    private var appBuildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    /// Removes anything that could identify a person before a line leaves the phone.
    static func sanitizeTelemetry(_ line: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: "\\+?\\d[\\d\\s]{6,}\\d") else { return line }
        let range = NSRange(line.startIndex..., in: line)
        return regex.stringByReplacingMatches(in: line, range: range, withTemplate: "<numara>")
    }

    private func appendTelemetryLine(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        clientLogRing.append("[\(formatter.string(from: Date()))] \(GatewayConnection.sanitizeTelemetry(message))")
        let overflow = clientLogRing.count - GatewayConnection.clientLogRingLimit
        if overflow > 0 {
            clientLogRing.removeFirst(overflow)
            clientLogDelivered = max(0, clientLogDelivered - overflow)
            clientLogDropped += overflow
        }
    }

    /// Sends the part of the ring the Redmi has not seen yet.
    private func flushTelemetry(force: Bool = false) {
        guard state == .authenticated else { return }
        let pending = clientLogRing.count - clientLogDelivered
        guard pending > 0 else { return }
        let now = Date()
        if !force, now.timeIntervalSince(lastClientLogSentAt) < 2 { return }
        let start = clientLogDelivered
        let end = min(clientLogRing.count, start + GatewayConnection.clientLogBatchLimit)
        let batch = Array(clientLogRing[start..<end])
        send([
            "type": "client_log",
            "appVersion": appVersionNumber,
            "appBuild": appBuildNumber,
            "dropped": clientLogDropped,
            "lines": batch
        ])
        clientLogDelivered = end
        clientLogDropped = 0
        lastClientLogSentAt = now
    }

    private func sendClientStatus() {
        guard state == .authenticated else { return }
        send([
            "type": "client_status",
            "appVersion": appVersionNumber,
            "appBuild": appBuildNumber,
            "appState": GatewayConnection.appStateName(UIApplication.shared.applicationState),
            "mic": GatewayConnection.microphonePermission,
            "battery": GatewayConnection.batteryText,
            "thermal": GatewayConnection.thermalText,
            "lowPower": ProcessInfo.processInfo.isLowPowerModeEnabled
        ])
    }

    private func startTelemetry() {
        installTelemetryObservers()
        // The first batch is the point of the whole channel: after a background wake it carries the
        // lines written while the socket was down, including a ring the Redmi never heard about.
        flushTelemetry(force: true)
        sendClientStatus()
        if clientLogTimer == nil {
            clientLogTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in self?.flushTelemetry() }
            }
        }
        if clientStatusTimer == nil {
            clientStatusTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in self?.sendClientStatus() }
            }
        }
    }

    private func stopTelemetry() {
        clientLogTimer?.invalidate()
        clientLogTimer = nil
        clientStatusTimer?.invalidate()
        clientStatusTimer = nil
    }

    private func installTelemetryObservers() {
        guard !telemetryObserversInstalled else { return }
        telemetryObserversInstalled = true
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.flushTelemetry(force: true)
                self?.sendClientStatus()
            }
        }
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                // Last chance before iOS freezes the app and the socket closes: the ring may hold the
                // only copy of the lines that explain a ring.
                self?.flushTelemetry(force: true)
                self?.sendClientStatus()
            }
        }
    }

    private static func appStateName(_ state: UIApplication.State) -> String {
        switch state {
        case .active: return "active"
        case .inactive: return "inactive"
        case .background: return "background"
        @unknown default: return "unknown"
        }
    }

    private static var microphonePermission: String {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return "granted"
        case .denied: return "denied"
        case .undetermined: return "undetermined"
        @unknown default: return "unknown"
        }
    }

    private static var batteryText: String {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        guard level >= 0 else { return "unknown" }
        let percent = Int((level * 100).rounded())
        switch UIDevice.current.batteryState {
        case .charging, .full: return "\(percent)% charging"
        case .unplugged: return "\(percent)%"
        default: return "\(percent)% unknown"
        }
    }

    private static var thermalText: String {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: return "nominal"
        case .fair: return "fair"
        case .serious: return "serious"
        case .critical: return "critical"
        @unknown default: return "unknown"
        }
    }
}

/// The diagnostics log on disk, written off the main actor.
///
/// Kept outside the connection type on purpose: that type is main-actor isolated, and a file write
/// from there would either block the interface or need an isolation hop per line. The file is
/// capped and rotated once, so it cannot grow without bound on a phone that runs for weeks.
enum LogFile {
    private static let queue = DispatchQueue(label: "com.ilkay.redmigateway.logfile", qos: .utility)
    private static let maxBytes = 512 * 1024

    static var url: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("app-log.txt")
    }

    static func append(_ text: String) {
        queue.async { write(text) }
    }

    private static func write(_ text: String) {
        guard let url else { return }
        if let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber,
           size.intValue > maxBytes {
            let previous = url.deletingLastPathComponent().appendingPathComponent("app-log.1.txt")
            try? FileManager.default.removeItem(at: previous)
            try? FileManager.default.moveItem(at: url, to: previous)
        }
        let data = Data(text.utf8)
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: url, options: .atomic)
        }
    }

    /// Puts the end of the previous run's file back into the memory ring at launch.
    @MainActor
    static func replayTail(_ lines: Int = 40) {
        guard let url, let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        let tail = text.split(separator: "\n").suffix(lines)
        guard !tail.isEmpty else { return }
        GatewayConnection.shared.log("Previous run (last \(tail.count) log lines)")
        for line in tail { GatewayConnection.shared.log("  " + line) }
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
