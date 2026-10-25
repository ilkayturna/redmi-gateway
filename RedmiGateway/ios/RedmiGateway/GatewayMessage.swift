import Foundation

// Messages from the Redmi (protocol 2, docs/PROTOCOL.md). Outgoing commands are small
// dictionaries built in GatewayConnection.

/// This iPhone build speaks protocol 2 and nothing older.
enum BridgeProtocol {
    static let version = 2
}

enum ProtocolCompatibility: Equatable {
    case supported
    /// The Redmi is newer but still accepts this iPhone build.
    case degraded
    case incompatible(String)
}

struct HelloMessage: Decodable {
    let device: String?
    let protocolVersion: Int?
    let minProtocol: Int?
    let gatewayId: String?
    let nonce: String?
    let paired: Bool?
    let appVersion: String?
    /// Every local address of the Redmi (Wi-Fi, its hotspot, VPN).
    let addresses: [String]?

    enum CodingKeys: String, CodingKey {
        case device, minProtocol, gatewayId, nonce, paired, appVersion, addresses
        case protocolVersion = "protocol"
    }
}

struct PairResultMessage: Decodable {
    let success: Bool
    let code: String?
    let key: String?
    let gatewayId: String?
    let gatewayProof: String?
}

struct AuthResultMessage: Decodable {
    let success: Bool
    let code: String?
    let message: String?
    let gatewayProof: String?
}

public struct CallEventMessage: Codable {
    public let type: String
    public let state: String
    public let number: String?
    public let callId: String?
    public let direction: String?

    public init(type: String = "call", state: String, number: String?, callId: String?, direction: String? = nil) {
        self.type = type
        self.state = state
        self.number = number
        self.callId = callId
        self.direction = direction
    }
}

struct ErrorMessage: Decodable {
    let code: String?
    let message: String?
}

struct AckMessage: Decodable {
    let action: String
    let success: Bool
}

struct AudioReadyMessage: Decodable {
    let session: UInt32
    let port: UInt16
    let sealed: Bool?
    /// The Redmi can rebuild an uplink frame lost in transit from the copy the next packet carries.
    /// Absent on older Redmis, which is exactly how the iPhone knows to keep sending plain PCM.
    let redundancy: Int?
}

struct AudioStateMessage: Decodable {
    let running: Bool
    let owner: String?
    let detail: String
}

struct StateCallInfo: Decodable {
    let callId: String?
    let number: String?
    let state: String
    let direction: String?
}

struct StateResponseMessage: Decodable {
    let call: StateCallInfo?
}

/// One row of the Redmi's call journal (a mirror of its CallLog).
struct CallLogEntry: Decodable {
    let seq: Int64
    let id: Int64
    let number: String
    let type: String
    let date: Int64
    let duration: Int64
    let answeredOn: String?
}

/// One row of the Redmi's SMS journal; a message changing box or read state arrives again.
struct SmsEntry: Decodable {
    let seq: Int64
    let id: Int64
    let address: String
    let body: String
    let date: Int64
    let box: String
    let read: Bool
}

struct SyncCallsMessage: Decodable {
    let items: [CallLogEntry]
    let hasMore: Bool
    let lastSeq: Int64
}

struct SyncSmsMessage: Decodable {
    let items: [SmsEntry]
    let hasMore: Bool
    let lastSeq: Int64
}

struct CallLogPush: Decodable {
    let item: CallLogEntry
}

struct SmsPush: Decodable {
    let item: SmsEntry
}

/// One call the Redmi's Telecom holds, from the `calls` event (Redmi 2.0.12, docs/PROTOCOL.md).
///
/// Only callId and state are required; the rest is read leniently so a missing field never drops
/// the whole list.
struct GatewayCallInfo: Decodable, Equatable, Identifiable {
    let callId: String
    /// ringing, dialing, connecting, active, holding or disconnecting.
    let state: String
    let number: String?
    let direction: String?
    let conference: Bool?
    /// The conference this call belongs to; the Redmi sends "" when there is none.
    let parentId: String?
    /// A second incoming call during the current one; the call_waiting flow handles it.
    let waiting: Bool?

    var id: String { callId }
    var isConference: Bool { conference == true }
    var isWaiting: Bool { waiting == true }
    /// A participant inside a conference: the conference call stands for it.
    var isConferenceChild: Bool { !(parentId ?? "").isEmpty }
}

/// Every call the Redmi holds. Sent after authentication and before every `call` event; an empty
/// list means no call is left.
struct CallsMessage: Decodable {
    let primaryId: String?
    let merging: Bool?
    let items: [GatewayCallInfo]
}

/// A second incoming call during the current one (call waiting).
struct CallWaitingMessage: Decodable {
    let state: String
    let callId: String?
    let number: String?
}

struct SmsSendResultMessage: Decodable {
    let recipient: String?
    let success: Bool
    let requestId: String?
    let error: String?
}

struct ResolveContactMessage: Decodable {
    let number: String
    let requestId: String?
}

struct ContactInfoMessage: Encodable {
    let type: String
    let number: String
    let name: String?
    let requestId: String?

    init(number: String, name: String?, requestId: String? = nil) {
        self.type = "contact_info"
        self.number = number
        self.name = name
        self.requestId = requestId
    }
}
