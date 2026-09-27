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

struct BLEDeviceInfoPayload: Codable {
    let id: String
    let ip: String?
    let port: Int?
    let v: Int?
    let ble: Int?
}

struct BLEGatewayStatePayload: Codable {
    let state: String
    let callId: String?
    let number: String?
    let seq: Int64?
    let ts: Int64?
}

struct BLECallControlPayload: Codable {
    let action: String
    let callId: String
    let seq: Int64
}

struct BLECallControlAckPayload: Codable {
    let ack: String?
    let callId: String?
    let seq: Int64?
    let success: Bool
    let error: String?
    let duplicate: Bool?
}

struct BLEHeartbeatPayload: Codable {
    let timestamp: Int64
}
