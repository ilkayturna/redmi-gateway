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
        var dropReason: String?
        if let sealedText = payload.dictionaryPayload["p"] as? String {
            if let sealed = Data(base64Encoded: sealedText) {
                if let pairing = GatewayConnection.shared.pairing {
                    if let plain = BridgeCrypto.open(BridgeCrypto.derive(pairing.key, "push"), sealed, aad: Self.aad) {
                        if let object = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] {
                            let sentAt = (object["t"] as? NSNumber)?.doubleValue ?? 0
                            // A push that arrives long after the call started is no longer ringing.
                            let ageMs = Date().timeIntervalSince1970 * 1000 - sentAt
                            if ageMs < 60_000 {
                                callId = object["id"] as? String
                                number = object["n"] as? String
                            } else {
                                dropReason = "push is \(Int(ageMs / 1000)) s old"
                            }
                        } else {
                            dropReason = "push payload is not json"
                        }
                    } else {
                        dropReason = "push could not be opened"
                    }
                } else {
                    dropReason = "no pairing"
                }
            } else {
                dropReason = "push body is not base64"
            }
        } else {
            dropReason = "push has no sealed body"
        }
        // iOS requires a CallKit report for every VoIP push, so a push that cannot be read still
        // shows a placeholder call and is closed again. That path used to leave no trace at all,
        // which is why a failed ring could not be explained from either phone.
        GatewayConnection.shared.log(dropReason.map { "VoIP push dropped: \($0)" } ?? "VoIP push for a live call")
        CallKitManager.shared.reportPushedCall(callId: callId, number: number, completion: completion)
    }
}
