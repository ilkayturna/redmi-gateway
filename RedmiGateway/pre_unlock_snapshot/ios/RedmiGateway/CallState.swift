import Foundation

public enum GSMCallState: String, Codable {
    case idle = "idle"
    case ringing = "ringing"
    case dialing = "dialing"
    case connecting = "connecting"
    case active = "active"
    case holding = "holding"
    case disconnected = "disconnected"
    
    public var isLive: Bool {
        switch self {
        case .idle, .disconnected:
            return false
        default:
            return true
        }
    }
}

public struct GSMCall: Identifiable, Equatable {
    public let id: UUID
    public var rawCallId: String
    public var number: String
    public var state: GSMCallState
    
    public init(id: UUID = UUID(), rawCallId: String, number: String, state: GSMCallState) {
        self.id = id
        self.rawCallId = rawCallId
        self.number = number
        self.state = state
    }
}
