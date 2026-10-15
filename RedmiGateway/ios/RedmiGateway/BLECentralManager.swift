import CoreBluetooth
import Foundation

/// Call state carried over Bluetooth, opened with the pairing's Bluetooth key.
struct BLECallState {
    let state: String
    let callId: String?
    let number: String?
    let direction: String?
    /// Newest entries of the Redmi's call and SMS journals; higher than seen means news.
    let callsSeq: Int64?
    let smsSeq: Int64?
    var waitingCallId: String? = nil
    var waitingNumber: String? = nil
    var isConference: Bool = false
}

/// Bluetooth LE link to the Redmi: presence, its current address, and the call-state wake-up.
///
/// iOS keeps this connection while the app is suspended and wakes the app for each state
/// notification, so a locked iPhone learns about an incoming call even when the Wi-Fi link has
/// gone to sleep. State restoration relaunches the app for these events after the system
/// ended it (not after the person force-quits it). State and control are sealed with a key
/// derived from the pairing; nothing unsealed is trusted. Bluetooth never carries call audio.
@MainActor
final class BLECentralManager: NSObject, ObservableObject {
    static let shared = BLECentralManager()

    private static let restoreIdentifier = "com.ilkay.redmigateway.ble-central"
    private static let sealedVersion: UInt8 = 2
    private static let stateAAD = Data("phonebridge-ble-state".utf8)
    private static let controlAAD = Data("phonebridge-ble-control".utf8)
    private static let maxStateAgeMs: Int64 = 120_000
    /// How often the call state may be read on purpose; wake-ups can arrive close together.
    private static let stateReadThrottle: TimeInterval = 2

    @Published private(set) var isBluetoothPoweredOn = false
    @Published private(set) var isConnected = false
    @Published private(set) var lastSeenDate: Date?

    var onEndpointHint: ((_ hosts: [String], _ port: Int, _ deviceId: String) -> Void)?
    var onStateUpdate: ((BLECallState) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var stateChar: CBCharacteristic?
    private var deviceInfoChar: CBCharacteristic?
    private var controlChar: CBCharacteristic?
    private var pendingControl: [Int64: (Bool) -> Void] = [:]
    private var lastControlSeq: Int64 = 0
    private var lastStateTime: Int64 = 0
    private var lastStateSeq: Int64 = -1
    private var lastStateReadAt: Date?

    private let kGatewayId = "ble_paired_device_id"
    private let kPeripheral = "ble_peripheral_uuid"

    var isReady: Bool { isConnected && controlChar != nil && GatewayConnection.shared.pairing != nil }

    private var gatewayId: String? {
        get { UserDefaults.standard.string(forKey: kGatewayId) }
        set { UserDefaults.standard.set(newValue, forKey: kGatewayId) }
    }

    override private init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionShowPowerAlertKey: false,
            CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier
        ])
    }

    /// Connects to the known Redmi (a pending connection that survives suspension) and scans.
    func start() {
        guard central.state == .poweredOn else { return }
        if peripheral == nil,
           let text = UserDefaults.standard.string(forKey: kPeripheral),
           let identifier = UUID(uuidString: text),
           let known = central.retrievePeripherals(withIdentifiers: [identifier]).first {
            adopt(known)
        }
        if let peripheral, peripheral.state == .disconnected {
            central.connect(peripheral, options: nil)
        }
        if peripheral?.state != .connected, !central.isScanning {
            central.scanForPeripherals(withServices: [RedmiGatewayBLEConstants.serviceUUID], options: nil)
        }
        // The call-wake beacon and every reconnect land here. Read at once for a link that is
        // already up, and once more shortly after for a state the Redmi publishes while the app is
        // still coming back; the throttle collapses repeated starts into single reads.
        refreshCallState(reason: "wake or reconnect")
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            self?.refreshCallState(reason: "after wake")
        }
    }

    /// Reads the Redmi's current addresses again (after this iPhone changed networks).
    func refreshDeviceInfo() {
        guard let peripheral, peripheral.state == .connected, let deviceInfoChar else { return }
        peripheral.readValue(for: deviceInfoChar)
    }

    /// Reads the call state now, at most once every [stateReadThrottle] seconds.
    ///
    /// The Redmi skips a state notification whose sealed payload does not fit the connection's
    /// MTU, so a call that changed while this iPhone was suspended is not pushed at all; the wake
    /// beacon brings the app back, and reading here refreshes the state at that moment instead of
    /// at the next notification. No new message type is involved: the Redmi already exposes the
    /// state characteristic as readable and serves the same sealed payload a notification carries,
    /// and a value that was already applied is ignored by [handleState]'s own time/sequence check.
    private func refreshCallState(reason: String) {
        guard let peripheral, peripheral.state == .connected, let stateChar else { return }
        let now = Date()
        if let last = lastStateReadAt, now.timeIntervalSince(last) < Self.stateReadThrottle { return }
        lastStateReadAt = now
        GatewayConnection.shared.log("Bluetooth call state read (\(reason))")
        peripheral.readValue(for: stateChar)
    }

    func setGateway(id: String) {
        if let current = gatewayId, current != id { forgetGateway() }
        gatewayId = id
    }

    func forgetGateway() {
        gatewayId = nil
        UserDefaults.standard.removeObject(forKey: kPeripheral)
        if let peripheral { central.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        stateChar = nil
        deviceInfoChar = nil
        controlChar = nil
        isConnected = false
    }

    /// Answer, reject, hang up or acknowledge ringing while the network link is down.
    func sendCallControl(action: String, callId: String, completion: ((Bool) -> Void)? = nil) {
        guard let peripheral, let controlChar, let key = GatewayConnection.shared.pairing?.bleKey else {
            completion?(false)
            return
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // Milliseconds keep the sequence increasing across restarts of either phone.
        let seq = max(now, lastControlSeq + 1)
        lastControlSeq = seq
        let payload: [String: Any] = ["a": action, "id": callId, "q": seq, "t": now]
        guard let json = try? JSONSerialization.data(withJSONObject: payload),
              let sealed = BridgeCrypto.seal(key, json, aad: Self.controlAAD) else {
            completion?(false)
            return
        }
        if let completion {
            pendingControl[seq] = completion
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
                self?.pendingControl.removeValue(forKey: seq)?(false)
            }
        }
        peripheral.writeValue(Data([Self.sealedVersion]) + sealed, for: controlChar, type: .withResponse)
    }

    private func adopt(_ candidate: CBPeripheral) {
        peripheral = candidate
        candidate.delegate = self
    }

    // MARK: - Incoming values

    private func handleDeviceInfo(_ data: Data) {
        guard let info = try? JSONDecoder().decode(BLEDeviceInfoPayload.self, from: data) else { return }
        if let expected = gatewayId, expected != info.id {
            // Another Redmi running the gateway; not ours.
            if let peripheral { central.cancelPeripheralConnection(peripheral) }
            peripheral = nil
            return
        }
        let hosts = ([info.ip].compactMap { $0 } + (info.ips ?? [])).filter { !$0.isEmpty }
        if !hosts.isEmpty, let port = info.port, port > 0 {
            onEndpointHint?(hosts, port, info.id)
        }
    }

    private func handleState(_ data: Data) {
        let bytes = [UInt8](data)
        guard bytes.first == Self.sealedVersion, bytes.count > 1 else {
            GatewayConnection.shared.log("Ignored unsealed Bluetooth state")
            return
        }
        guard let key = GatewayConnection.shared.pairing?.bleKey,
              let plain = BridgeCrypto.open(key, Data(bytes[1...]), aad: Self.stateAAD),
              let object = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any],
              let state = object["s"] as? String,
              let time = (object["t"] as? NSNumber)?.int64Value,
              let seq = (object["q"] as? NSNumber)?.int64Value else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard abs(now - time) < Self.maxStateAgeMs else { return }
        guard time > lastStateTime || (time == lastStateTime && seq > lastStateSeq) else { return }
        lastStateTime = time
        lastStateSeq = seq
        let id = (object["id"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let direction: String?
        switch object["d"] as? String {
        case "i": direction = "incoming"
        case "o": direction = "outgoing"
        default: direction = nil
        }
        let wid = (object["wid"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let wn = object["wn"] as? String
        let conf = object["conf"] as? Bool ?? false
        onStateUpdate?(BLECallState(state: state, callId: id, number: object["n"] as? String, direction: direction,
                                    callsSeq: (object["k"] as? NSNumber)?.int64Value,
                                    smsSeq: (object["m"] as? NSNumber)?.int64Value,
                                    waitingCallId: wid,
                                    waitingNumber: wn,
                                    isConference: conf))
    }

    private func handleControlReply(_ data: Data) {
        let bytes = [UInt8](data)
        var plain = data
        if bytes.first == Self.sealedVersion {
            guard let key = GatewayConnection.shared.pairing?.bleKey,
                  let opened = BridgeCrypto.open(key, Data(bytes[1...]), aad: Self.controlAAD) else { return }
            plain = opened
        }
        guard let object = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any],
              let seq = (object["seq"] as? NSNumber)?.int64Value else { return }
        let success = object["success"] as? Bool ?? false
        pendingControl.removeValue(forKey: seq)?(success)
    }
}

extension BLECentralManager: CBCentralManagerDelegate {
    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        Task { @MainActor in
            if let first = restored.first {
                self.adopt(first)
                self.isConnected = first.state == .connected
                if first.state == .connected { first.discoverServices([RedmiGatewayBLEConstants.serviceUUID]) }
            }
        }
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            self.isBluetoothPoweredOn = central.state == .poweredOn
            if central.state == .poweredOn {
                self.start()
            } else {
                self.isConnected = false
                self.stateChar = nil
                self.deviceInfoChar = nil
                self.controlChar = nil
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data]
        let advertisedId = serviceData?[RedmiGatewayBLEConstants.serviceUUID].flatMap { String(data: $0, encoding: .utf8) }
        Task { @MainActor in
            self.lastSeenDate = Date()
            if let expected = self.gatewayId, let advertisedId, advertisedId != expected { return }
            guard self.peripheral == nil || self.peripheral?.state == .disconnected else { return }
            self.adopt(peripheral)
            central.connect(peripheral, options: nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            guard self.peripheral === peripheral else { return }
            self.isConnected = true
            GatewayConnection.shared.log("Bluetooth connected to the Redmi")
            central.stopScan()
            UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: self.kPeripheral)
            peripheral.discoverServices([RedmiGatewayBLEConstants.serviceUUID])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            guard self.peripheral === peripheral else { return }
            self.isConnected = false
            self.stateChar = nil
            self.deviceInfoChar = nil
            self.controlChar = nil
            GatewayConnection.shared.log("Bluetooth disconnected: \((error as NSError?)?.code ?? 0)")
            // A pending connection completes whenever the Redmi is back in range, even suspended.
            if self.gatewayId != nil { central.connect(peripheral, options: nil) } else { self.peripheral = nil }
            self.start()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            guard self.peripheral === peripheral else { return }
            self.isConnected = false
            self.peripheral = nil
            self.start()
        }
    }
}

extension BLECentralManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else { return }
        for service in peripheral.services ?? [] where service.uuid == RedmiGatewayBLEConstants.serviceUUID {
            peripheral.discoverCharacteristics([
                RedmiGatewayBLEConstants.charDeviceInfoUUID,
                RedmiGatewayBLEConstants.charStateUUID,
                RedmiGatewayBLEConstants.charCallControlUUID
            ], for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                                error: Error?) {
        guard error == nil else { return }
        let characteristics = service.characteristics ?? []
        Task { @MainActor in
            for characteristic in characteristics {
                switch characteristic.uuid {
                case RedmiGatewayBLEConstants.charDeviceInfoUUID:
                    // Notified whenever the Redmi's addresses change (new Wi-Fi, hotspot on/off).
                    self.deviceInfoChar = characteristic
                    if characteristic.properties.contains(.notify) { peripheral.setNotifyValue(true, for: characteristic) }
                    peripheral.readValue(for: characteristic)
                case RedmiGatewayBLEConstants.charStateUUID:
                    self.stateChar = characteristic
                    peripheral.setNotifyValue(true, for: characteristic)
                    peripheral.readValue(for: characteristic)
                case RedmiGatewayBLEConstants.charCallControlUUID:
                    self.controlChar = characteristic
                    if characteristic.properties.contains(.notify) { peripheral.setNotifyValue(true, for: characteristic) }
                default:
                    break
                }
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                                error: Error?) {
        let uuid = characteristic.uuid
        let notifying = characteristic.isNotifying
        Task { @MainActor in
            if uuid == RedmiGatewayBLEConstants.charStateUUID {
                GatewayConnection.shared.log("Bluetooth call-state updates \(notifying ? "on" : "off")\(error == nil ? "" : " (error)")")
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        guard error == nil, let data = characteristic.value else { return }
        let uuid = characteristic.uuid
        Task { @MainActor in
            self.lastSeenDate = Date()
            switch uuid {
            case RedmiGatewayBLEConstants.charDeviceInfoUUID: self.handleDeviceInfo(data)
            case RedmiGatewayBLEConstants.charStateUUID: self.handleState(data)
            case RedmiGatewayBLEConstants.charCallControlUUID: self.handleControlReply(data)
            default: break
            }
        }
    }
}
