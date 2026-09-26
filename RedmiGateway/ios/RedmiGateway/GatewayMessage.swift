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

public struct AudioReadyMessage: Codable {
    public let type: String
    public let session: UInt32
    public let port: UInt16
}

public struct AudioStateMessage: Codable {
    public let type: String
    public let running: Bool
    public let detail: String
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

public struct SmsReceivedMessage: Codable {
    public let type: String
    public let id: Int64?
    public let sender: String
    public let message: String
    public let timestamp: Int64
}

public struct SmsHistoryItem: Codable {
    public let id: Int64
    public let sender: String
    public let message: String
    public let timestamp: Int64
}

public struct SmsHistoryResponse: Codable {
    public let type: String
    public let messages: [SmsHistoryItem]
    public let hasMore: Bool
    public let lastId: Int64
}

public struct SmsSendResultMessage: Codable {
    public let type: String
    public let recipient: String
    public let success: Bool
    public let error: String?
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

public struct GetSmsHistoryPayload: Codable {
    public let type: String = "get_sms_history"
    public let afterId: Int64

    public init(afterId: Int64) {
        self.afterId = afterId
    }
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

public struct AudioStartPayload: Codable {
    public let type = "audio_start"
}

public struct AudioStopPayload: Codable {
    public let type = "audio_stop"
}

public struct SendSmsPayload: Codable {
    public let type: String = "send_sms"
    public let recipient: String
    public let message: String
    
    public init(recipient: String, message: String) {
        self.recipient = recipient
        self.message = message
    }
}

public struct DtmfPayload: Codable {
    public let type: String = "dtmf"
    public let digit: String
    public let callId: String?

    public init(digit: String, callId: String? = nil) {
        self.digit = digit
        self.callId = callId
    }
}
