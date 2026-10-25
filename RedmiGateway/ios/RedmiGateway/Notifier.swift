import Foundation
import UIKit
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
final class Notifier: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    /// Whether iOS lets this app show notifications; Settings warns when it does not.
    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined

    private let center = UNUserNotificationCenter.current()

    override private init() {
        super.init()
        center.delegate = self
        refreshAuthorization()
        // The person may have changed the permission in iOS Settings while the app was away.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in Notifier.shared.refreshAuthorization() }
        }
    }

    func requestPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            Task { @MainActor in Notifier.shared.refreshAuthorization() }
        }
    }

    func refreshAuthorization() {
        center.getNotificationSettings { settings in
            let status = settings.authorizationStatus
            Task { @MainActor in Notifier.shared.authorization = status }
        }
    }

    func post(sms records: [SmsRecord]) {
        guard !records.isEmpty else { return }
        center.getDeliveredNotifications { delivered in
            let shown = Set(delivered.map { $0.request.identifier })
            Task { @MainActor in Notifier.shared.add(sms: records, alreadyShown: shown) }
        }
    }

    func post(missed calls: [CallRecord]) {
        guard !calls.isEmpty else { return }
        center.getDeliveredNotifications { delivered in
            let shown = Set(delivered.map { $0.request.identifier })
            Task { @MainActor in Notifier.shared.add(missed: calls, alreadyShown: shown) }
        }
    }

    /// Away from home the Redmi's Apple push for a message usually arrives before this iPhone has
    /// fetched the journal, and the copy posted here used to stand next to it as a second banner.
    /// Both now carry the same identifier ("sms-<Redmi id>", the push's apns-collapse-id), so iOS
    /// replaces the pushed banner; when that banner is already on screen the copy comes in
    /// quietly, so the same message does not sound twice.
    private func add(sms records: [SmsRecord], alreadyShown: Set<String>) {
        for record in records {
            let identifier = record.gatewayId.map { "sms-\($0)" } ?? record.id
            let content = UNMutableNotificationContent()
            content.title = PhoneContactDirectory.shared.displayName(for: record.address)
            content.body = record.body
            content.sound = .default
            content.threadIdentifier = "sms-\(PhoneIdentity.key(record.address))"
            content.userInfo = ["open": "sms", "address": record.address]
            content.badge = NSNumber(value: badgeCount)
            if alreadyShown.contains(identifier) { quiet(content) }
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
    }

    /// Missed calls already share "call-<log id>" with the Redmi's push; only the second sound goes.
    private func add(missed calls: [CallRecord], alreadyShown: Set<String>) {
        for call in calls {
            let identifier = "call-\(call.logId)"
            let content = UNMutableNotificationContent()
            content.title = "Cevapsız arama"
            content.body = PhoneContactDirectory.shared.displayName(for: call.number)
            content.sound = .default
            content.threadIdentifier = "missed"
            content.userInfo = ["open": "recents"]
            content.badge = NSNumber(value: badgeCount)
            if alreadyShown.contains(identifier) { quiet(content) }
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
    }

    /// Replaces a notification that is already on screen without sound or a new banner.
    private func quiet(_ content: UNMutableNotificationContent) {
        content.sound = nil
        content.interruptionLevel = .passive
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
