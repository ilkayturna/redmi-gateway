import Foundation

// MARK: - Incoming Messages (From Redmi Android Gateway)

public struct HelloMessage: Codable {
    public let type: String
    public let device: String
    public let protocolVersion: Int?
    
    enum CodingKeys: String, CodingKey {
        case type, device
        case protocolVersion = "protocol"
    }
}

public struct AuthResultMessage: Codable {
    public let type: String
    public let success: Bool
    public let message: String?
}

public struct CallEventMessage: Codable {
    public let type: String
    public let state: String
    public let number: String?
    public let callId: String?
}

public struct ErrorMessage: Codable {
    public let type: String
    public let message: String
}

public struct AckMessage: Codable {
    public let type: String
    public let action: String
    public let success: Bool
}

public struct StateCallInfo: Codable {
    public let callId: String?
    public let number: String?
    public let state: String
}

public struct StateResponseMessage: Codable {
    public let type: String
    public let call: StateCallInfo?
}

// MARK: - Outgoing Messages (From iPhone Client to Redmi Gateway)

public struct AuthPayload: Codable {
    public let type: String = "auth"
    public let token: String
    
    public init(token: String) {
        self.token = token
    }
}

public struct GetStatePayload: Codable {
    public let type: String = "get_state"
    
    public init() {}
}

public struct AnswerPayload: Codable {
    public let type: String = "answer"
    public let callId: String
    
    public init(callId: String) {
        self.callId = callId
    }
}

public struct RejectPayload: Codable {
    public let type: String = "reject"
    public let callId: String
    
    public init(callId: String) {
        self.callId = callId
    }
}

public struct HangupPayload: Codable {
    public let type: String = "hangup"
    public let callId: String
    
    public init(callId: String) {
        self.callId = callId
    }
}

public struct DialPayload: Codable {
    public let type: String = "dial"
    public let number: String
    
    public init(number: String) {
        self.number = number
    }
}

