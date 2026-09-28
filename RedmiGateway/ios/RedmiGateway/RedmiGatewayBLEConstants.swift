import Foundation
import CoreBluetooth

enum RedmiGatewayBLEConstants {
    static let serviceUUID = CBUUID(string: "A67D0001-2056-427E-971D-3C9A6F4E8D10")
    static let charDeviceInfoUUID = CBUUID(string: "A67D0002-2056-427E-971D-3C9A6F4E8D10")
    static let charStateUUID = CBUUID(string: "A67D0003-2056-427E-971D-3C9A6F4E8D10")
    static let charCallControlUUID = CBUUID(string: "A67D0004-2056-427E-971D-3C9A6F4E8D10")
    static let charHeartbeatUUID = CBUUID(string: "A67D0005-2056-427E-971D-3C9A6F4E8D10")
    static let cccdUUID = CBUUID(string: "00002902-0000-1000-8000-00805F9B34FB")

    /// Invariant: BLE is strictly presence/bootstrap/control side-channel. NEVER audio media.
    static let invariantNote = "BLE is strictly presence/bootstrap/control side-channel. NEVER audio media."
}

/// Plain device-info characteristic: identity and current address only, no call data.
struct BLEDeviceInfoPayload: Codable {
    let id: String
    let ip: String?
    /// All local addresses; Wi-Fi and the Redmi's own hotspot can be up together.
    let ips: [String]?
    let port: Int?
    let v: Int?
    let ble: Int?
}
