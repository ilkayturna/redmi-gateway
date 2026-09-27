import CommonCrypto
import CryptoKit
import Foundation
import Security

/// Primitives shared with the Redmi (docs/PROTOCOL.md, "Security"): HMAC-SHA256 for proofs and
/// key derivation, AES-256-GCM for sealed payloads, and PBKDF2 only to protect the one-time key
/// transfer with the short pairing code.
enum BridgeCrypto {
    static let keyBytes = 32
    static let nonceBytes = 12
    static let tagBytes = 16
    static let pairingRounds: UInt32 = 100_000

    static func random(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }

    static func hmac(_ key: Data, _ parts: Data...) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key))
        for part in parts { mac.update(data: part) }
        return Data(mac.finalize())
    }

    /// Domain-separated sub key: HMAC(key, label).
    static func derive(_ key: Data, _ label: String) -> Data {
        hmac(key, Data(label.utf8))
    }

    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        let x = [UInt8](a), y = [UInt8](b)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for index in 0..<x.count { diff |= x[index] ^ y[index] }
        return diff == 0
    }

    static func pbkdf2(_ secret: String, salt: Data, rounds: UInt32 = pairingRounds) -> Data? {
        var derived = [UInt8](repeating: 0, count: keyBytes)
        let saltBytes = [UInt8](salt)
        let status = CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2),
            secret, secret.utf8.count,
            saltBytes, saltBytes.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
            rounds,
            &derived, derived.count
        )
        return status == Int32(kCCSuccess) ? Data(derived) : nil
    }

    /// Returns nonce || ciphertext || tag.
    static func seal(_ key: Data, _ plaintext: Data, aad: Data = Data(), nonce: Data? = nil) -> Data? {
        let nonceData = nonce ?? random(nonceBytes)
        guard let gcmNonce = try? AES.GCM.Nonce(data: nonceData),
              let box = try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: gcmNonce, authenticating: aad)
        else { return nil }
        var out = Data(nonceData)
        out.append(box.ciphertext)
        out.append(box.tag)
        return out
    }

    /// Opens nonce || ciphertext || tag; nil when authentication fails.
    static func open(_ key: Data, _ sealed: Data, aad: Data = Data()) -> Data? {
        let bytes = [UInt8](sealed)
        guard bytes.count >= nonceBytes + tagBytes else { return nil }
        let nonce = Data(bytes[0..<nonceBytes])
        let ciphertext = Data(bytes[nonceBytes..<(bytes.count - tagBytes)])
        let tag = Data(bytes[(bytes.count - tagBytes)..<bytes.count])
        guard let gcmNonce = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext, tag: tag)
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    static func bigEndian(_ value: UInt32) -> Data {
        Data([UInt8(truncatingIfNeeded: value >> 24), UInt8(truncatingIfNeeded: value >> 16),
              UInt8(truncatingIfNeeded: value >> 8), UInt8(truncatingIfNeeded: value)])
    }

    static func bigEndian(_ value: UInt64) -> Data {
        bigEndian(UInt32(truncatingIfNeeded: value >> 32)) + bigEndian(UInt32(truncatingIfNeeded: value))
    }
}

/// Sealed WebSocket frames after protocol 2 authentication: {"t":"s","c":counter,"d":base64}.
/// The nonce is a direction tag plus the counter; each side only accepts increasing counters.
final class SecureChannel {
    private static let aad = Data("phonebridge-ws-2".utf8)
    private static let clientToGateway = Data([0x43, 0x32, 0x53, 0x00]) // "C2S\0"
    private static let gatewayToClient = Data([0x53, 0x32, 0x43, 0x00]) // "S2C\0"

    private let key: Data
    private var sendCounter: UInt64 = 0
    private var lastReceived: UInt64 = 0

    init(key: Data) {
        self.key = key
    }

    func seal(_ json: Data) -> String? {
        sendCounter += 1
        let nonce = Self.clientToGateway + BridgeCrypto.bigEndian(sendCounter)
        guard let sealed = BridgeCrypto.seal(key, json, aad: Self.aad, nonce: nonce) else { return nil }
        let body = Data([UInt8](sealed)[BridgeCrypto.nonceBytes...])
        return "{\"t\":\"s\",\"c\":\(sendCounter),\"d\":\"\(body.base64EncodedString())\"}"
    }

    /// Plaintext JSON, or nil for anything forged, replayed or malformed.
    func open(counter: UInt64, data: String) -> Data? {
        guard counter > lastReceived, let body = Data(base64Encoded: data) else { return nil }
        let nonce = Self.gatewayToClient + BridgeCrypto.bigEndian(counter)
        guard let plain = BridgeCrypto.open(key, nonce + body, aad: Self.aad) else { return nil }
        lastReceived = counter
        return plain
    }
}

/// UDP media framing for protocol 2 sessions (see CallAudioRelay on the Redmi):
/// [0..3] session  [4..7] counter  [8] type  [9] flags  [10..] ciphertext || tag, header as AAD.
struct MediaSealer {
    static let headerBytes = 10
    static let flagHearing: UInt8 = 0x01
    private static let uplinkTag = Data("UPLK".utf8)
    private static let downlinkTag = Data("DNLK".utf8)

    let key: Data
    let session: UInt32

    func seal(type: UInt8, flags: UInt8, counter: UInt32, payload: Data) -> Data? {
        let header = BridgeCrypto.bigEndian(session) + BridgeCrypto.bigEndian(counter) + Data([type, flags])
        let nonce = Self.uplinkTag + BridgeCrypto.bigEndian(counter) + BridgeCrypto.bigEndian(session)
        guard let sealed = BridgeCrypto.seal(key, payload, aad: header, nonce: nonce) else { return nil }
        return header + Data([UInt8](sealed)[BridgeCrypto.nonceBytes...])
    }

    /// (type, counter, payload) for an authentic downlink packet of this session.
    func open(_ packet: Data) -> (type: UInt8, counter: UInt32, payload: Data)? {
        let bytes = [UInt8](packet)
        guard bytes.count >= Self.headerBytes + BridgeCrypto.tagBytes else { return nil }
        let packetSession = UInt32(bytes[0]) << 24 | UInt32(bytes[1]) << 16 | UInt32(bytes[2]) << 8 | UInt32(bytes[3])
        guard packetSession == session else { return nil }
        let counter = UInt32(bytes[4]) << 24 | UInt32(bytes[5]) << 16 | UInt32(bytes[6]) << 8 | UInt32(bytes[7])
        let header = Data(bytes[0..<Self.headerBytes])
        let nonce = Self.downlinkTag + BridgeCrypto.bigEndian(counter) + BridgeCrypto.bigEndian(session)
        guard let payload = BridgeCrypto.open(key, nonce + Data(bytes[Self.headerBytes...]), aad: header) else { return nil }
        return (bytes[8], counter, payload)
    }
}

/// The paired Redmi and the 256-bit key it issued. Kept in the Keychain, readable after the
/// first unlock so a locked iPhone woken by Bluetooth can still open the Redmi's call state.
struct GatewayPairing: Codable, Equatable {
    let clientId: String
    let key: Data
    let gatewayId: String
    let pairedAt: Date

    var bleKey: Data { BridgeCrypto.derive(key, "ble") }
}

enum PairingKeychain {
    private static let service = "com.ilkay.redmigateway.pairing"
    private static let account = "gateway"

    private static var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    static func load() -> GatewayPairing? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(GatewayPairing.self, from: data)
    }

    @discardableResult
    static func save(_ pairing: GatewayPairing) -> Bool {
        guard let data = try? JSONEncoder().encode(pairing) else { return false }
        delete()
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    static func delete() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}
