import Foundation
import UIKit
import UserNotifications

/// Where a tapped notification should lead inside the app.
enum AppRoute: Equatable {
    case messages(String)
    /// The conversation list, when a pushed message could not be tied to its sender.
    case conversations
    case recents
    /// Settings, where the Redmi's health is shown (a HEALTH alert).
    case settings
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

    /// Notification categories. The Redmi puts the same identifiers into aps.category of its Apple
    /// pushes (wave 1 contract, section 7), so a pushed banner offers the same actions as a local one.
    enum Category {
        static let sms = "SMS"
        /// An SMS carrying a one-time code; local notifications only.
        static let smsCode = "SMS_CODE"
        static let missed = "MISSED"
        static let health = "HEALTH"
    }

    private enum Action {
        static let reply = "reply"
        static let markRead = "read"
        static let copyCode = "copy_code"
        static let callBack = "callback"
        static let message = "message"
    }

    /// The sealed part of the Redmi's alert pushes uses the VoIP push's key and AAD (VoipPush).
    private static let pushAad = Data("phonebridge-push".utf8)

    override private init() {
        super.init()
        center.delegate = self
        // Registered at every launch, background relaunches included, before any banner can show.
        center.setNotificationCategories(Self.categories())
        refreshAuthorization()
        // Installs the contacts observers, so names follow edits made in Apple's Contacts app.
        _ = PhoneContactDirectory.shared
        // The person may have changed the permission in iOS Settings while the app was away.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in Notifier.shared.refreshAuthorization() }
        }
        // The icon shows what the app shows: what was read or seen on screen no longer counts.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in Notifier.shared.updateBadge() }
        }
    }

    private static func categories() -> Set<UNNotificationCategory> {
        // Replying sends a paid SMS from the Redmi, so a locked iPhone asks for Face ID first.
        let reply = UNTextInputNotificationAction(identifier: Action.reply, title: "Yanıtla",
                                                  options: [.authenticationRequired],
                                                  textInputButtonTitle: "Gönder", textInputPlaceholder: "Mesaj")
        let markRead = UNNotificationAction(identifier: Action.markRead, title: "Okundu Olarak İşaretle", options: [])
        // Writing the pasteboard from the background is unreliable, so copying opens the app.
        let copyCode = UNNotificationAction(identifier: Action.copyCode, title: "Kodu Kopyala", options: [.foreground])
        // iOS refuses to start a CallKit call from the background, so both of these open the app.
        let callBack = UNNotificationAction(identifier: Action.callBack, title: "Geri Ara", options: [.foreground])
        let message = UNNotificationAction(identifier: Action.message, title: "Mesaj Gönder", options: [.foreground])
        return [
            UNNotificationCategory(identifier: Category.sms, actions: [reply, markRead], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Category.smsCode, actions: [copyCode, reply, markRead],
                                   intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Category.missed, actions: [callBack, message], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Category.health, actions: [], intentIdentifiers: [], options: [])
        ]
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

    /// `pushed` holds the Redmi journal ids that the Redmi already announced through an Apple push.
    func post(sms records: [SmsRecord], pushed: Set<Int64> = []) {
        guard !records.isEmpty else { return }
        let lookups = PhoneContactDirectory.shared.numbersNeedingLookup(records.map { $0.address })
        center.getDeliveredNotifications { delivered in
            let shown = Set(delivered.map { $0.request.identifier })
            // Off the main thread: after a background relaunch the contact list is not loaded, and
            // a bare number used to stand where the contact's name belongs.
            let names = PhoneContactDirectory.lookUpNames(lookups)
            Task { @MainActor in Notifier.shared.add(sms: records, pushed: pushed, alreadyShown: shown, names: names) }
        }
    }

    func post(missed calls: [CallRecord], pushed: Set<Int64> = []) {
        guard !calls.isEmpty else { return }
        let lookups = PhoneContactDirectory.shared.numbersNeedingLookup(calls.map { $0.number })
        center.getDeliveredNotifications { delivered in
            let shown = Set(delivered.map { $0.request.identifier })
            let names = PhoneContactDirectory.lookUpNames(lookups)
            Task { @MainActor in Notifier.shared.add(missed: calls, pushed: pushed, alreadyShown: shown, names: names) }
        }
    }

    /// Away from home the Redmi's Apple push for a message usually arrives before this iPhone has
    /// fetched the journal. Both carry the same identifier ("sms-<Redmi id>", the push's
    /// apns-collapse-id), so iOS replaces the pushed banner; while that banner is still delivered
    /// the copy comes in quietly. Once the person tapped or swiped the push away it is gone from
    /// the delivered list, and a message the Redmi marks as pushed is then not announced again:
    /// that second banner, with sound, came while the person was already reading the message.
    ///
    /// A batch (a catch-up after a link gap) is posted oldest first, so the newest message is on
    /// top of the stack, and only the newest one sounds.
    private func add(sms records: [SmsRecord], pushed: Set<Int64>, alreadyShown: Set<String>, names: [String: String]) {
        let ordered = records.sorted { $0.date < $1.date }
        var skipped = 0
        for (index, record) in ordered.enumerated() {
            let identifier = record.gatewayId.map { "sms-\($0)" } ?? record.id
            let wasPushed = record.gatewayId.map { pushed.contains($0) } ?? false
            if wasPushed && !alreadyShown.contains(identifier) {
                skipped += 1
                continue
            }
            let content = UNMutableNotificationContent()
            content.title = names[record.address] ?? PhoneContactDirectory.shared.displayName(for: record.address)
            content.body = record.body
            content.sound = index == ordered.count - 1 ? .default : nil
            content.threadIdentifier = "sms-\(PhoneIdentity.key(record.address))"
            var info: [AnyHashable: Any] = ["open": "sms", "address": record.address]
            if let code = OneTimeCode.find(in: record.body) {
                // The code is readable straight from the lock screen, and one tap copies it.
                content.subtitle = "Kod: \(code)"
                content.categoryIdentifier = Category.smsCode
                info["code"] = code
            } else {
                content.categoryIdentifier = Category.sms
            }
            content.userInfo = info
            content.badge = NSNumber(value: badgeCount)
            if alreadyShown.contains(identifier) { quiet(content) }
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
        if skipped > 0 {
            GatewayConnection.shared.log("No second SMS notification for \(skipped) message(s) the Redmi already pushed")
            updateBadge()
        }
    }

    /// Missed calls share "call-<log id>" with the Redmi's push; the same rules as for messages.
    private func add(missed calls: [CallRecord], pushed: Set<Int64>, alreadyShown: Set<String>, names: [String: String]) {
        let ordered = calls.sorted { $0.date < $1.date }
        var skipped = 0
        for (index, call) in ordered.enumerated() {
            let identifier = "call-\(call.logId)"
            if pushed.contains(call.logId) && !alreadyShown.contains(identifier) {
                skipped += 1
                continue
            }
            let content = UNMutableNotificationContent()
            content.title = "Cevapsız arama"
            content.body = names[call.number] ?? PhoneContactDirectory.shared.displayName(for: call.number)
            content.sound = index == ordered.count - 1 ? .default : nil
            content.threadIdentifier = "missed"
            if call.number.isEmpty {
                // A hidden number cannot be called back or texted.
                content.userInfo = ["open": "recents"]
            } else {
                content.userInfo = ["open": "recents", "number": call.number]
                content.categoryIdentifier = Category.missed
            }
            content.badge = NSNumber(value: badgeCount)
            if alreadyShown.contains(identifier) { quiet(content) }
            center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
        if skipped > 0 {
            GatewayConnection.shared.log("No second missed-call notification for \(skipped) call(s) the Redmi already pushed")
            updateBadge()
        }
    }

    /// Replaces a notification that is already on screen without sound or a new banner.
    private func quiet(_ content: UNMutableNotificationContent) {
        content.sound = nil
        content.interruptionLevel = .passive
    }

    /// A send from this iPhone failed while the app was off screen; inside the app the bubble
    /// already says "Gönderilemedi". Tapping it opens the conversation, where "Tekrar dene" is.
    func postSendFailed(_ record: SmsRecord) {
        // On screen the failed bubble is the message; a banner on top would repeat it.
        guard UIApplication.shared.applicationState != .active else { return }
        guard let requestId = record.requestId else {
            GatewayConnection.shared.log("SMS failure notification skipped: the send has no request id")
            return
        }
        let content = UNMutableNotificationContent()
        content.title = PhoneContactDirectory.shared.displayName(for: record.address)
        content.body = "Mesaj gönderilemedi: \(record.body)"
        content.sound = .default
        content.threadIdentifier = "sms-\(PhoneIdentity.key(record.address))"
        content.userInfo = ["open": "sms", "address": record.address]
        center.add(UNNotificationRequest(identifier: "fail-\(requestId)", content: content, trigger: nil))
        GatewayConnection.shared.log("SMS failure notification posted")
    }

    /// The failed send went out after all (a late result, or its journal entry).
    func clearSendFailure(requestId: String) {
        let identifier = "fail-\(requestId)"
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    /// An audio-critical problem on the Redmi, for the health package: one notification per code
    /// set, replaced rather than stacked when the same set is posted again.
    func postHealth(codes: [String], title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "health"
        content.categoryIdentifier = Category.health
        content.userInfo = ["open": "health"]
        let identifier = "health-" + codes.sorted().joined(separator: ",")
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    /// Clears delivered notifications of a conversation once it is on screen.
    func clearConversation(_ address: String, then done: (() -> Void)? = nil) {
        let thread = "sms-\(PhoneIdentity.key(address))"
        center.getDeliveredNotifications { delivered in
            let ids = delivered.filter { $0.request.content.threadIdentifier == thread }.map { $0.request.identifier }
            Task { @MainActor in
                Notifier.shared.center.removeDeliveredNotifications(withIdentifiers: ids)
                Notifier.shared.updateBadge()
                done?()
            }
        }
    }

    /// Clears the missed-call notifications once Recents is on screen. Local copies and the
    /// Redmi's pushes both use the "missed" thread, so pushed calls not synced yet go too.
    func clearMissedCalls() {
        center.getDeliveredNotifications { delivered in
            let ids = delivered.filter { $0.request.content.threadIdentifier == "missed" }.map { $0.request.identifier }
            Task { @MainActor in
                if !ids.isEmpty { Notifier.shared.center.removeDeliveredNotifications(withIdentifiers: ids) }
                Notifier.shared.updateBadge()
            }
        }
    }

    func updateBadge() {
        let count = badgeCount
        if #available(iOS 16.0, *) {
            center.setBadgeCount(count) { _ in }
        } else {
            UIApplication.shared.applicationIconBadgeNumber = count
        }
    }

    private var badgeCount: Int {
        HistoryStore.shared.unreadCount + HistoryStore.shared.unseenMissedCount
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let content = notification.request.content
        let thread = content.threadIdentifier
        let passive = content.interruptionLevel == .passive
        Task { @MainActor in
            let history = HistoryStore.shared
            if let open = history.openThreadKey, thread == "sms-\(open)" {
                // No banner for the conversation that is already on screen.
                completionHandler([])
            } else if thread == "missed", history.recentsVisible {
                // Nor for a missed call while Recents shows it; it is marked seen there.
                completionHandler([])
            } else if passive {
                // A quiet replacement of a notification already shown: the list only.
                completionHandler([.list, .badge])
            } else {
                completionHandler([.banner, .list, .sound, .badge])
            }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let request = response.notification.request
        let info = request.content.userInfo
        let tap = NotificationTap(
            identifier: request.identifier,
            action: response.actionIdentifier,
            open: info["open"] as? String,
            address: info["address"] as? String,
            number: info["number"] as? String,
            code: info["code"] as? String,
            sealed: info["p"] as? String,
            body: request.content.body,
            replyText: (response as? UNTextInputNotificationResponse)?.userText
        )
        Task { @MainActor in
            Notifier.shared.handle(tap, completion: completionHandler)
        }
    }

    // MARK: - Taps and actions

    private func handle(_ tap: NotificationTap, completion: @escaping () -> Void) {
        let connection = GatewayConnection.shared
        let target = self.target(of: tap)
        switch tap.action {
        case Action.reply:
            let text = (tap.replyText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard let address = target.address, !text.isEmpty else {
                connection.log("Notification reply not sent: \(target.address == nil ? "no address" : "empty text")")
                completion()
                return
            }
            sendReply(text, to: address, completion: completion)
        case Action.markRead:
            guard let address = target.address else {
                connection.log("Mark as read from a notification: no address")
                completion()
                return
            }
            HistoryStore.shared.markThreadSeen(PhoneIdentity.key(address))
            HistoryStore.shared.flush()
            connection.log("Conversation marked read from a notification")
            clearConversation(address) { completion() }
        case Action.copyCode:
            if let code = tap.code ?? OneTimeCode.find(in: tap.body) {
                UIPasteboard.general.string = code
                connection.log("One-time code copied from a notification")
            } else {
                connection.log("Copy code: the notification carries no code")
            }
            completion()
        case Action.callBack:
            if let number = target.number {
                InAppDial.start(number)
            } else {
                connection.log("Call back from a notification: no number")
                AppRouter.shared.pending = .recents
            }
            completion()
        case Action.message:
            if let number = target.number {
                AppRouter.shared.pending = .messages(number)
            } else {
                connection.log("Message from a notification: no number")
                AppRouter.shared.pending = .recents
            }
            completion()
        default:
            route(tap, target: target)
            completion()
        }
    }

    /// A plain tap: the conversation, Recents or the Redmi's health, as Messages and Phone do.
    private func route(_ tap: NotificationTap, target: (address: String?, number: String?)) {
        let router = AppRouter.shared
        switch tap.open ?? "" {
        case "sms":
            if let address = target.address {
                router.pending = .messages(address)
            } else {
                GatewayConnection.shared.log("Pushed SMS tapped before its sender was known; opening the list")
                router.pending = .conversations
            }
        case "recents":
            router.pending = .recents
        case "health":
            router.pending = .settings
        default:
            // An older Redmi's push carries no "open"; its identifier is its apns-collapse-id.
            if tap.identifier.hasPrefix("call-") {
                router.pending = .recents
            } else if let address = target.address {
                router.pending = .messages(address)
            } else if tap.identifier.hasPrefix("sms-") {
                router.pending = .conversations
            }
        }
    }

    /// The sender of an SMS notification or the caller of a missed-call one: in the userInfo of a
    /// local notification, sealed in "p" of the Redmi's push (contract, section 7), or, for a push
    /// without it, looked up by the journal id in the identifier once the journal has it.
    private func target(of tap: NotificationTap) -> (address: String?, number: String?) {
        var address = tap.address
        var number = tap.number
        if address == nil, number == nil, let sealed = tap.sealed, let object = openSealedPush(sealed) {
            address = object["address"] as? String
            number = object["number"] as? String
        }
        if address == nil, tap.identifier.hasPrefix("sms-"), let gatewayId = Int64(tap.identifier.dropFirst(4)) {
            address = HistoryStore.shared.address(ofGatewayId: gatewayId)
        }
        if number == nil, tap.identifier.hasPrefix("call-"), let logId = Int64(tap.identifier.dropFirst(5)) {
            number = HistoryStore.shared.number(ofLogId: logId)
        }
        return (address, number)
    }

    /// Opens the sealed part of the Redmi's alert push, the way VoipPush opens a call push.
    private func openSealedPush(_ text: String) -> [String: Any]? {
        let connection = GatewayConnection.shared
        guard let sealed = Data(base64Encoded: text) else {
            connection.log("Pushed notification: sealed part is not base64")
            return nil
        }
        // The app may have been suspended through the first unlock and missed iOS's notice of it.
        connection.reloadPairingIfLocked(reason: "notification")
        guard let pairing = connection.pairing else {
            connection.log("Pushed notification not opened: \(connection.pairingLocked ? "pairing locked until the first unlock" : "no pairing")")
            return nil
        }
        guard let plain = BridgeCrypto.open(BridgeCrypto.derive(pairing.key, "push"), sealed, aad: Self.pushAad),
              let object = (try? JSONSerialization.jsonObject(with: plain)) as? [String: Any] else {
            connection.log("Pushed notification: sealed part could not be opened")
            return nil
        }
        return object
    }

    /// A reply typed into an SMS notification. The link is usually asleep then: the reply becomes
    /// a pending bubble at once, the wake connects and syncs, and the message goes out right after
    /// the sync, with the link held until the Redmi's result. The action's completion waits for
    /// that result, within the background time iOS gives an action; a failure is announced by
    /// postSendFailed like any other.
    private func sendReply(_ text: String, to address: String, completion: @escaping () -> Void) {
        let connection = GatewayConnection.shared
        let work = BackgroundWork(name: "sms-reply", completion: completion)
        // Answering a message means it was read.
        HistoryStore.shared.markThreadSeen(PhoneIdentity.key(address))
        if UIApplication.shared.applicationState != .active {
            // The wake path holds background time and replaces a socket left from before suspension.
            connection.wakeFromBeacon()
        }
        let requestId = connection.sendSms(recipient: address, message: text, waitForLink: true)
        connection.log("SMS reply from a notification: \(connection.isAuthenticated ? "sending" : "waiting for the link")")
        Task { @MainActor in
            for _ in 0..<50 {
                if HistoryStore.shared.sendSettled(requestId: requestId) { break }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            if !HistoryStore.shared.sendSettled(requestId: requestId) {
                connection.log("SMS reply from a notification: no result within the action's time; it settles later")
            }
            HistoryStore.shared.flush()
            Notifier.shared.clearConversation(address) { work.finish() }
        }
    }
}

/// What didReceive needs from a notification response, read before leaving iOS's thread.
private struct NotificationTap {
    let identifier: String
    let action: String
    let open: String?
    let address: String?
    let number: String?
    let code: String?
    let sealed: String?
    let body: String
    let replyText: String?
}

/// Background time for a notification action; the completion runs exactly once.
@MainActor
private final class BackgroundWork {
    private var task: UIBackgroundTaskIdentifier = .invalid
    private var completion: (() -> Void)?

    init(name: String, completion: @escaping () -> Void) {
        self.completion = completion
        task = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            guard let work = self else { return }
            Task { @MainActor in work.finish() }
        }
    }

    func finish() {
        let done = completion
        completion = nil
        done?()
        if task != .invalid {
            UIApplication.shared.endBackgroundTask(task)
            task = .invalid
        }
    }
}

/// Finds a one-time code in an SMS, for "Kodu Kopyala" on its notification.
///
/// iOS fills security codes only from Apple's Messages and Mail, never from another app's
/// notifications; this is the closest a relayed message can get.
enum OneTimeCode {
    private static let keywords = [
        "kod", "şifre", "sifre", "parola", "doğrulama", "dogrulama", "code", "otp", "password", "passcode"
    ]

    /// A run of 4-8 digits that is not part of a date, time, amount or longer number, in a message
    /// that talks about a code. Six digits win (most banks and e-Devlet), then the first run.
    static func find(in text: String) -> String? {
        let lower = text.lowercased(with: Locale(identifier: "tr_TR"))
        guard keywords.contains(where: { lower.contains($0) }) else { return nil }
        guard let regex = try? NSRegularExpression(pattern: "(?<![0-9][.,:/-])(?<![0-9])[0-9]{4,8}(?![0-9])(?![.,:/-][0-9])") else {
            return nil
        }
        let runs = regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { match -> String? in
            guard let range = Range(match.range, in: text) else { return nil }
            return String(text[range])
        }
        return runs.first { $0.count == 6 } ?? runs.first
    }
}
