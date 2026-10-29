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
    /// The command an `unknown_command` error answers.
    let command: String?
}

struct AckMessage: Decodable {
    let action: String
    let success: Bool
    /// `client_diag` acks name the payload and the part they confirm, and why a part was refused.
    let id: String?
    let part: Int?
    let error: String?
}

/// The Redmi's health (`health`, Redmi -> iPhone, docs/PROTOCOL.md): the problems it found, its
/// ROM, battery and system updater. Sent right after authentication and again on every change.
///
/// Read leniently: every field is optional and a field of an unexpected type is skipped, so a
/// newer Redmi that adds or reshapes something never costs the whole snapshot.
struct RedmiHealth: Decodable, Equatable {
    struct Problem: Decodable, Equatable {
        let code: String?
        /// Call audio on the iPhone does not work while this problem exists.
        let audioCritical: Bool
        let title: String?
        let detail: String?
        let fix: String?

        private enum CodingKeys: String, CodingKey {
            case code, audioCritical, title, detail, fix
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            code = try? container.decodeIfPresent(String.self, forKey: .code)
            audioCritical = (try? container.decodeIfPresent(Bool.self, forKey: .audioCritical)) ?? false
            title = try? container.decodeIfPresent(String.self, forKey: .title)
            detail = try? container.decodeIfPresent(String.self, forKey: .detail)
            fix = try? container.decodeIfPresent(String.self, forKey: .fix)
        }
    }

    struct Rom: Decodable, Equatable {
        /// `Build.VERSION.INCREMENTAL|Build.DISPLAY`.
        let build: String?
        let previous: String?
        /// When the Redmi first saw this build (ms since 1970).
        let changedAt: Int64?

        private enum CodingKeys: String, CodingKey {
            case build, previous, changedAt
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            build = try? container.decodeIfPresent(String.self, forKey: .build)
            previous = try? container.decodeIfPresent(String.self, forKey: .previous)
            changedAt = try? container.decodeIfPresent(Int64.self, forKey: .changedAt)
        }
    }

    struct Battery: Decodable, Equatable {
        let level: Int?
        let charging: Bool?

        private enum CodingKeys: String, CodingKey {
            case level, charging
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            level = try? container.decodeIfPresent(Int.self, forKey: .level)
            charging = try? container.decodeIfPresent(Bool.self, forKey: .charging)
        }
    }

    /// The ROM's own updater (an OTA wipes the Redmi's audio fix).
    struct Updater: Decodable, Equatable {
        let present: Bool?
        let enabled: Bool?

        private enum CodingKeys: String, CodingKey {
            case present, enabled
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            present = try? container.decodeIfPresent(Bool.self, forKey: .present)
            enabled = try? container.decodeIfPresent(Bool.self, forKey: .enabled)
        }
    }

    /// When the Redmi evaluated this snapshot (ms since 1970).
    let at: Int64?
    let problems: [Problem]
    let rom: Rom?
    let battery: Battery?
    let updater: Updater?
    let adbWifi: Bool?

    private enum CodingKeys: String, CodingKey {
        case at, problems, rom, battery, updater, adbWifi
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        at = try? container.decodeIfPresent(Int64.self, forKey: .at)
        let listed: [LenientElement<Problem>]? = try? container.decodeIfPresent([LenientElement<Problem>].self, forKey: .problems)
        problems = (listed ?? []).compactMap { $0.value }
        rom = try? container.decodeIfPresent(Rom.self, forKey: .rom)
        battery = try? container.decodeIfPresent(Battery.self, forKey: .battery)
        updater = try? container.decodeIfPresent(Updater.self, forKey: .updater)
        adbWifi = try? container.decodeIfPresent(Bool.self, forKey: .adbWifi)
    }

    /// The audio-critical problem codes, sorted and without repeats: one notification per new set.
    var audioCriticalCodes: [String] {
        Array(Set(problems.filter { $0.audioCritical }.map { $0.code ?? "unknown" })).sorted()
    }

    var hasAudioCriticalProblem: Bool { problems.contains { $0.audioCritical } }

    /// The band the Redmi warns about: 20 % or less and not charging.
    var batteryNeedsCharging: Bool {
        guard let level = battery?.level else { return false }
        return level <= 20 && battery?.charging != true
    }

    /// The readable half of the ROM build (`Build.DISPLAY`), or the whole string.
    var romDisplay: String? {
        guard let build = rom?.build, !build.isEmpty else { return nil }
        let parts = build.split(separator: "|", maxSplits: 1).map(String.init)
        if parts.count == 2, !parts[1].isEmpty { return parts[1] }
        return build
    }

    var evaluatedAt: Date? { at.map { Date(timeIntervalSince1970: Double($0) / 1000) } }
    var romChangedAt: Date? { rom?.changedAt.map { Date(timeIntervalSince1970: Double($0) / 1000) } }

    /// One log line; it changes only when something the owner would care about changes.
    var summary: String {
        let codes = problems.map { ($0.code ?? "unknown") + ($0.audioCritical ? "!" : "") }
        var parts = ["problems=" + (codes.isEmpty ? "none" : codes.joined(separator: ","))]
        if let level = battery?.level {
            parts.append("battery=\(level)%" + (battery?.charging == true ? " charging" : ""))
        }
        if let updater {
            let present = updater.present.map { $0 ? "present" : "absent" } ?? "?"
            let enabled = updater.enabled.map { $0 ? "enabled" : "disabled" } ?? "?"
            parts.append("updater=\(present)/\(enabled)")
        }
        if let adbWifi { parts.append("adbWifi=\(adbWifi ? 1 : 0)") }
        if let build = rom?.build { parts.append("rom=\(build)") }
        return parts.joined(separator: " ")
    }
}

/// Decodes one element of a list, or nothing, so one malformed entry never drops the whole list.
struct LenientElement<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: Decoder) throws {
        value = try? Value(from: decoder)
    }
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
