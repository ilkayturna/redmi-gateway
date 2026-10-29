import SwiftUI
import UserNotifications

/// A line at the top of the app when the Redmi reports something the owner has to act on: a problem
/// that breaks call audio on this iPhone (a ROM update that removed the audio fix, for example), or
/// a Redmi battery that is low and not charging.
///
/// Nobody looks at the Redmi in the pocket, so this iPhone is where it has to show. ContentView puts
/// the banner under ConnectionBanner; a tap opens Settings, whose "Redmi durumu" section has the
/// detail and the fix.
struct RedmiHealthBanner: View {
    @ObservedObject private var connection = GatewayConnection.shared
    let openSettings: () -> Void

    var body: some View {
        if let warning {
            Button(action: openSettings) {
                HStack(spacing: 8) {
                    Image(systemName: warning.icon)
                    Text(warning.text).font(.footnote.weight(.medium)).lineLimit(2)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                }
                .foregroundColor(warning.tint)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(warning.tint.opacity(0.12))
            }
            .buttonStyle(.plain)
            .accessibilityHint("Redmi durumunu açar")
        }
    }

    private struct Warning {
        let text: String
        let icon: String
        let tint: Color
    }

    private var warning: Warning? {
        guard connection.isPaired, let health = connection.redmiHealth else { return nil }
        if health.hasAudioCriticalProblem {
            return Warning(text: "Redmi'de sorun: iPhone'da görüşme sesi çalışmaz",
                           icon: "exclamationmark.triangle.fill", tint: .red)
        }
        if health.batteryNeedsCharging, let level = health.battery?.level {
            return Warning(text: "Redmi'nin pili %\(level) · şarja tak", icon: "battery.25", tint: .orange)
        }
        return nil
    }
}

/// One local notification per new set of audio-critical problems, so the owner hears about a broken
/// Redmi on the phone in hand.
///
/// The Redmi sends the same alert through Apple when this iPhone has no socket, under the same
/// identifier ("health"), so whichever comes second replaces the first: quietly when the first is
/// still on screen. The last set notified is kept across launches, because every connection brings
/// the current health again and must not sound again for the same problem.
@MainActor
enum RedmiHealthAlert {
    static let identifier = "health"
    private static let notifiedKey = "redmi_health_notified_codes"

    static func evaluate(_ health: RedmiHealth) {
        let codes = health.audioCriticalCodes
        let key = codes.joined(separator: ",")
        let defaults = UserDefaults.standard
        guard key != (defaults.string(forKey: notifiedKey) ?? "") else { return }
        defaults.set(key, forKey: notifiedKey)
        let center = UNUserNotificationCenter.current()
        guard !codes.isEmpty else {
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
            GatewayConnection.shared.log("Redmi health: audio problems cleared; their notification removed")
            return
        }
        let titles = health.problems.filter { $0.audioCritical }.compactMap { $0.title }.filter { !$0.isEmpty }
        let body = (titles.isEmpty ? codes : titles).joined(separator: " · ")
        let id = identifier
        center.getDeliveredNotifications { delivered in
            let alreadyShown = delivered.contains { $0.request.identifier == id }
            Task { @MainActor in RedmiHealthAlert.post(body: body, codes: key, quietly: alreadyShown) }
        }
    }

    private static func post(body: String, codes: String, quietly: Bool) {
        let content = UNMutableNotificationContent()
        content.title = "Redmi'de sorun: iPhone'da görüşme sesi çalışmaz"
        content.body = body + "\nAyrıntı: Ayarlar › Redmi durumu"
        content.threadIdentifier = "health"
        content.userInfo = ["open": "health"]
        if quietly {
            // The Redmi's own alert for the same problem is already on screen.
            content.interruptionLevel = .passive
        } else {
            content.sound = .default
            content.interruptionLevel = .active
        }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { error in
            let code = error.map { ($0 as NSError).code }
            Task { @MainActor in
                if let code {
                    GatewayConnection.shared.log("Redmi health notification failed (\(code)) for \(codes)")
                } else {
                    GatewayConnection.shared.log("Redmi health notification posted for \(codes)\(quietly ? " (quietly)" : "")")
                }
            }
        }
    }
}
