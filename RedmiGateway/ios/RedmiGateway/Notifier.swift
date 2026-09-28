import Foundation
import UserNotifications

/// Where a tapped notification should lead inside the app.
enum AppRoute: Equatable {
    case messages(String)
    case recents
}

@MainActor
final class AppRouter: ObservableObject {
    static let shared = AppRouter()
    @Published var pending: AppRoute?
}

/// Notifications for new SMS and missed calls from the Redmi's line.
///
/// They are posted on this iPhone after it fetched the Redmi's journals: while connected, or
/// after Bluetooth woke it for a new entry (and, away from home, after an Apple push).
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    private let center = UNUserNotificationCenter.current()

    override private init() {
        super.init()
        center.delegate = self
    }

    func requestPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func post(sms records: [SmsRecord]) {
        for record in records {
            let content = UNMutableNotificationContent()
            content.title = PhoneContactDirectory.shared.displayName(for: record.address)
            content.body = record.body
            content.sound = .default
            content.threadIdentifier = "sms-\(PhoneIdentity.key(record.address))"
            content.userInfo = ["open": "sms", "address": record.address]
            content.badge = NSNumber(value: badgeCount)
            center.add(UNNotificationRequest(identifier: record.id, content: content, trigger: nil))
        }
    }

    func post(missed calls: [CallRecord]) {
        for call in calls {
            let content = UNMutableNotificationContent()
            content.title = "Cevapsız arama"
            content.body = PhoneContactDirectory.shared.displayName(for: call.number)
            content.sound = .default
            content.threadIdentifier = "missed"
            content.userInfo = ["open": "recents"]
            content.badge = NSNumber(value: badgeCount)
            center.add(UNNotificationRequest(identifier: "call-\(call.logId)", content: content, trigger: nil))
        }
    }

    /// Clears delivered notifications of a conversation once it is on screen.
    func clearConversation(_ address: String) {
        let thread = "sms-\(PhoneIdentity.key(address))"
        center.getDeliveredNotifications { delivered in
            let ids = delivered.filter { $0.request.content.threadIdentifier == thread }.map { $0.request.identifier }
            Task { @MainActor in
                Notifier.shared.center.removeDeliveredNotifications(withIdentifiers: ids)
                Notifier.shared.updateBadge()
            }
        }
    }

    func updateBadge() {
        let count = badgeCount
        if #available(iOS 16.0, *) {
            center.setBadgeCount(count) { _ in }
        }
    }

    private var badgeCount: Int {
        HistoryStore.shared.unreadCount + HistoryStore.shared.unseenMissedCount
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let thread = notification.request.content.threadIdentifier
        Task { @MainActor in
            // No banner for the conversation that is already on screen.
            if let open = HistoryStore.shared.openThreadKey, thread == "sms-\(open)" {
                completionHandler([])
            } else {
                completionHandler([.banner, .list, .sound])
            }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let open = info["open"] as? String
        let address = info["address"] as? String
        Task { @MainActor in
            if open == "sms", let address {
                AppRouter.shared.pending = .messages(address)
            } else if open == "recents" {
                AppRouter.shared.pending = .recents
            }
            completionHandler()
        }
    }
}
