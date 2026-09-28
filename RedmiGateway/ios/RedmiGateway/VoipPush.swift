import Foundation
import PushKit

/// Incoming calls while this iPhone is away from the Redmi (for example Redmi at home, iPhone
/// at work over Tailscale). The Redmi sends an Apple VoIP push; iOS launches the app even when
/// it was swiped away, and the app must present the call with CallKit right away.
///
/// The push carries the call id and number sealed with a key derived from the pairing, so Apple
/// only relays ciphertext. Without the push entitlement iOS issues no token and nothing happens.
/// The registry delivers on the main queue, so the delegate runs on the main actor and reports
/// the call synchronously, as iOS requires.
@MainActor
final class VoipPush: NSObject, PKPushRegistryDelegate {
    static let shared = VoipPush()

    private static let aad = Data("phonebridge-push".utf8)
    private var registry: PKPushRegistry?

    func start() {
        guard registry == nil else { return }
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        self.registry = registry
    }

    func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {
        let token = pushCredentials.token.map { String(format: "%02x", $0) }.joined()
        GatewayConnection.shared.setPushToken(token)
    }

    func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                      for type: PKPushType, completion: @escaping () -> Void) {
        var callId: String?
        var number: String?
        if let sealedText = payload.dictionaryPayload["p"] as? String,
           let sealed = Data(base64Encoded: sealedText),
           let pairing = GatewayConnection.shared.pairing,
           let plain = BridgeCrypto.open(BridgeCrypto.derive(pairing.key, "push"), sealed, aad: Self.aad),
           let object = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] {
            let sentAt = (object["t"] as? NSNumber)?.doubleValue ?? 0
            // A push that arrives long after the call started is no longer ringing.
            if Date().timeIntervalSince1970 * 1000 - sentAt < 60_000 {
                callId = object["id"] as? String
                number = object["n"] as? String
            }
        }
        CallKitManager.shared.reportPushedCall(callId: callId, number: number, completion: completion)
    }
}
