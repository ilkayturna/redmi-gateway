import Foundation

public enum GSMCallState: String, Codable {
    case idle
    case ringing
    case dialing
    case connecting
    case active
    case holding
    case disconnected
    case disconnecting

    public var isLive: Bool {
        switch self {
        case .idle, .disconnected, .disconnecting: return false
        default: return true
        }
    }
}

/// The Redmi's current cellular call as this iPhone knows it.
public struct GSMCall: Identifiable, Equatable {
    public let id: UUID
    public var rawCallId: String
    public var number: String
    public var state: GSMCallState
    public var isIncoming: Bool
    /// When the call became active, for the call timer.
    public var connectedAt: Date?

    public init(id: UUID = UUID(), rawCallId: String, number: String, state: GSMCallState,
                isIncoming: Bool = false, connectedAt: Date? = nil) {
        self.id = id
        self.rawCallId = rawCallId
        self.number = number
        self.state = state
        self.isIncoming = isIncoming
        self.connectedAt = connectedAt
    }
}
