import Foundation
import CoreBluetooth

@MainActor
final class BLECentralManager: NSObject, ObservableObject {
    static let shared = BLECentralManager()

    @Published private(set) var isBluetoothPoweredOn = false
    @Published private(set) var isConnected = false
    @Published private(set) var discoveredDeviceId: String?
    @Published private(set) var lastRssi: Int?
    @Published private(set) var lastSeenDate: Date?

    /// Stored paired device ID for identity filtering. Set during initial pairing.
    var pairedDeviceId: String? {
        get { UserDefaults.standard.string(forKey: "ble_paired_device_id") }
        set { UserDefaults.standard.set(newValue, forKey: "ble_paired_device_id") }
    }

    var onEndpointHint: ((_ host: String, _ port: Int, _ deviceId: String) -> Void)?
    var onStateUpdate: ((BLEGatewayStatePayload) -> Void)?
    var onPresenceChanged: ((Bool) -> Void)?

    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?

    private var deviceInfoChar: CBCharacteristic?
    private var stateChar: CBCharacteristic?
    private var callControlChar: CBCharacteristic?
    private var heartbeatChar: CBCharacteristic?

    private var controlSeq: Int64 = 0
    private var pendingControlCompletions: [Int64: (Bool) -> Void] = [:]

    override private init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionShowPowerAlertKey: false
        ])
    }

    func startScanning() {
        guard centralManager.state == .poweredOn else { return }
        if !centralManager.isScanning {
            centralManager.scanForPeripherals(
                withServices: [RedmiGatewayBLEConstants.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
    }

    func stopScanning() {
        if centralManager.isScanning {
            centralManager.stopScan()
        }
    }

    /// Emergency / fallback call control when WebSocket is offline or degraded
    func sendCallControl(action: String, callId: String, completion: ((Bool) -> Void)? = nil) {
        guard let peripheral = connectedPeripheral,
              let controlChar = callControlChar else {
            completion?(false)
            return
        }

        controlSeq += 1
        let seq = controlSeq
        if let completion {
            pendingControlCompletions[seq] = completion
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { [weak self] in
                if let pending = self?.pendingControlCompletions.removeValue(forKey: seq) {
                    pending(false)
                }
            }
        }

        let payload = BLECallControlPayload(action: action, callId: callId, seq: seq)
        guard let data = try? JSONEncoder().encode(payload) else {
            completion?(false)
            return
        }

        let writeType: CBCharacteristicWriteType = controlChar.properties.contains(.write) ? .withResponse : .withoutResponse
        peripheral.writeValue(data, for: controlChar, type: writeType)

        if writeType == .withoutResponse {
            completion?(true)
        }
    }

    func clearPairing() {
        pairedDeviceId = nil
        discoveredDeviceId = nil
        if isConnected {
            if let p = connectedPeripheral {
                centralManager.cancelPeripheralConnection(p)
            }
        }
    }
}

extension BLECentralManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            isBluetoothPoweredOn = (central.state == .poweredOn)
            if central.state == .poweredOn {
                startScanning()
            } else {
                isConnected = false
                connectedPeripheral = nil
                onPresenceChanged?(false)
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            lastRssi = RSSI.intValue
            lastSeenDate = Date()

            var advDeviceId: String? = nil
            if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
               let deviceIdData = serviceData[RedmiGatewayBLEConstants.serviceUUID],
               let devId = String(data: deviceIdData, encoding: .utf8) {
                advDeviceId = devId
                discoveredDeviceId = devId
            }

            // ADV-03: Only connect if peripheral matches stored paired device ID
            if let requiredId = pairedDeviceId, !requiredId.isEmpty {
                guard let advId = advDeviceId, advId == requiredId else {
                    return // Skip this peripheral — not our paired device
                }
            }

            if connectedPeripheral == nil || connectedPeripheral?.state == .disconnected {
                connectedPeripheral = peripheral
                peripheral.delegate = self
                centralManager.connect(peripheral, options: nil)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            isConnected = true
            onPresenceChanged?(true)
            peripheral.discoverServices([RedmiGatewayBLEConstants.serviceUUID])
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            isConnected = false
            deviceInfoChar = nil
            stateChar = nil
            callControlChar = nil
            heartbeatChar = nil
            connectedPeripheral = nil
            onPresenceChanged?(false)
            startScanning()
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            isConnected = false
            connectedPeripheral = nil
            startScanning()
        }
    }
}

extension BLECentralManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services, error == nil else { return }
        for service in services where service.uuid == RedmiGatewayBLEConstants.serviceUUID {
            peripheral.discoverCharacteristics([
                RedmiGatewayBLEConstants.charDeviceInfoUUID,
                RedmiGatewayBLEConstants.charStateUUID,
                RedmiGatewayBLEConstants.charCallControlUUID,
                RedmiGatewayBLEConstants.charHeartbeatUUID
            ], for: service)
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard let characteristics = service.characteristics, error == nil else { return }
        Task { @MainActor in
            for char in characteristics {
                switch char.uuid {
                case RedmiGatewayBLEConstants.charDeviceInfoUUID:
                    deviceInfoChar = char
                    peripheral.setNotifyValue(true, for: char)
                    peripheral.readValue(for: char)

                case RedmiGatewayBLEConstants.charStateUUID:
                    stateChar = char
                    peripheral.setNotifyValue(true, for: char)
                    peripheral.readValue(for: char)

                case RedmiGatewayBLEConstants.charCallControlUUID:
                    callControlChar = char
                    if char.properties.contains(.notify) {
                        peripheral.setNotifyValue(true, for: char)
                    }

                case RedmiGatewayBLEConstants.charHeartbeatUUID:
                    heartbeatChar = char

                default:
                    break
                }
            }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard let data = characteristic.value, error == nil else { return }
        Task { @MainActor in
            switch characteristic.uuid {
            case RedmiGatewayBLEConstants.charDeviceInfoUUID:
                if let devInfo = try? JSONDecoder().decode(BLEDeviceInfoPayload.self, from: data) {
                    discoveredDeviceId = devInfo.id
                    // Auto-pair on first successful connection if no paired device set
                    if pairedDeviceId == nil || pairedDeviceId?.isEmpty == true {
                        pairedDeviceId = devInfo.id
                    }
                    if let ip = devInfo.ip, !ip.isEmpty, let port = devInfo.port, port > 0 {
                        onEndpointHint?(ip, port, devInfo.id)
                    }
                }

            case RedmiGatewayBLEConstants.charStateUUID:
                if let statePayload = try? JSONDecoder().decode(BLEGatewayStatePayload.self, from: data) {
                    onStateUpdate?(statePayload)
                }

            case RedmiGatewayBLEConstants.charCallControlUUID:
                if let ack = try? JSONDecoder().decode(BLECallControlAckPayload.self, from: data),
                   let seq = ack.seq,
                   let completion = pendingControlCompletions.removeValue(forKey: seq) {
                    completion(ack.success)
                }

            default:
                break
            }
        }
    }
}
