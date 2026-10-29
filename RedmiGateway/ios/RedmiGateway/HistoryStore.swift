import Combine
import Foundation
import UIKit

/// A call from the Redmi's journal. `answeredOn` says which phone carried the conversation.
struct CallRecord: Codable, Identifiable, Equatable {
    let logId: Int64
    var seq: Int64
    let number: String
    /// incoming, outgoing, missed, declined, blocked, voicemail, answered_elsewhere, other
    let type: String
    let date: Date
    let duration: Int64
    var answeredOn: String?
    /// When this iPhone first learned about the call. Optional so history saved by older builds
    /// still decodes; nil there means the Redmi's date.
    var arrivedAt: Date?

    var id: Int64 { logId }
    var isMissed: Bool { type == "missed" }
}

enum SmsStatus: String, Codable {
    case received, sending, sent, failed
}

struct SmsRecord: Codable, Identifiable, Equatable {
    /// "g<id>" for messages from the Redmi's journal, "p<requestId>" while a send is pending.
    var id: String
    var gatewayId: Int64?
    var requestId: String?
    var address: String
    var body: String
    var date: Date
    var status: SmsStatus
    var read: Bool
    /// When this iPhone first learned about the message; see CallRecord.arrivedAt.
    var arrivedAt: Date?

    var isIncoming: Bool { status == .received }
}

/// The Redmi's mark on journal items it already announced through an Apple push (wave 1 contract,
/// section 7). Read from the same frame as the items, so the shared message types stay as they
/// are; an older Redmi sends no mark and nothing changes.
struct JournalPushMarks: Decodable {
    struct Mark: Decodable {
        let id: Int64
        let pushed: Bool?
    }

    /// sync_sms / sync_calls
    let items: [Mark]?
    /// The live sms / call_log frames.
    let item: Mark?

    var ids: Set<Int64> {
        var all = items ?? []
        if let item { all.append(item) }
        return Set(all.filter { $0.pushed == true }.map { $0.id })
    }
}

/// Recents and messages mirrored from the Redmi, kept on the iPhone between launches.
///
/// Each list is replayed from the Redmi by sequence number: after a reconnect the iPhone asks
/// for everything after the last sequence it has seen, so missed calls and messages that
/// arrived while it was away are never lost. Live pushes only advance that position when they
/// are contiguous; anything else is refetched by the next sync and merged by id.
@MainActor
final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()

    @Published private(set) var calls: [CallRecord] = []
    @Published private(set) var messages: [SmsRecord] = []
    @Published private(set) var missedSeenAt: Date = Date()
    @Published private(set) var threadSeen: [String: Date] = [:]

    /// Conversation currently on screen; its new messages need no banner.
    var openThreadKey: String?
    /// Recents is on screen; a missed call needs no banner there, it is seen at once.
    var recentsVisible = false

    private(set) var callsSeq: Int64 = 0
    private(set) var smsSeq: Int64 = 0
    private var gatewayId: String?
    /// Messages older than the first sync count as read.
    private var readBaseline: Date = Date()
    private var saveWorkItem: DispatchWorkItem?
    private var savePending = false

    /// Whether history.json has been read, or found absent.
    ///
    /// The file is protected until the first unlock after a restart, and iOS launches this app
    /// before that unlock (Bluetooth, the beacon, a push). An unread store used to be taken for an
    /// empty one and saved over the file after the unlock: unread messages and unseen missed calls
    /// all became read, and pending or failed bubbles, which exist only here, were gone. Nothing is
    /// saved until the file has been read, and every entry point retries the read.
    private var loaded = false
    /// Why the file could not be read yet; logged when it changes.
    private var loadProblem: String?
    /// A bind that came before the file could be read; applied once it has been.
    private var deferredBind: String?
    private var lastSaveFailure: Int?

    private struct Snapshot: Codable {
        var gatewayId: String?
        var callsSeq: Int64
        var smsSeq: Int64
        var calls: [CallRecord]
        var messages: [SmsRecord]
        var missedSeenAt: Date
        var threadSeen: [String: Date]
        var readBaseline: Date
    }

    private static let maxCalls = 500
    private static let maxMessages = 3000
    /// One serial queue for every write, so an older snapshot can never land after a newer one.
    private static let writeQueue = DispatchQueue(label: "com.ilkay.redmigateway.history", qos: .utility)

    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("history.json")
    }

    private init() {
        loadIfNeeded()
        // The first unlock makes the file readable while the app runs. A suspended app does not
        // get this notice, which is why the entry points retry as well.
        NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in HistoryStore.shared.loadIfNeeded() }
        }
        // iOS may suspend and then end the app from here on; the half-second save delay must not
        // lose what is already on screen.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in HistoryStore.shared.flush() }
        }
    }

    // MARK: - Loading

    /// Reads history.json unless that already happened; false while it cannot be read.
    @discardableResult
    func loadIfNeeded() -> Bool {
        if loaded { return true }
        let url = Self.fileURL
        guard FileManager.default.fileExists(atPath: url.path) else {
            // A first launch, or the app's data was removed: genuinely empty.
            finishLoad()
            return true
        }
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            noteLoadProblem("History not readable yet (error \((error as NSError).code)); nothing is saved until it is")
            return false
        }
        do {
            let snapshot = try JSONDecoder().decode(Snapshot.self, from: data)
            apply(snapshot)
        } catch {
            // A file this build cannot decode must not block saving forever. It is kept aside for
            // diagnosis and the store starts over, as it would after a reinstall.
            let aside = url.deletingLastPathComponent()
                .appendingPathComponent("history.bad-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: url, to: aside)
            GatewayConnection.shared.log("History could not be decoded; kept aside as \(aside.lastPathComponent), starting over")
        }
        finishLoad()
        return true
    }

    private func finishLoad() {
        loaded = true
        if loadProblem != nil {
            loadProblem = nil
            GatewayConnection.shared.log("History read after the unlock")
        }
        if let id = deferredBind {
            deferredBind = nil
            bind(gatewayId: id)
        }
    }

    private func noteLoadProblem(_ problem: String) {
        guard problem != loadProblem else { return }
        loadProblem = problem
        GatewayConnection.shared.log(problem)
    }

    private func apply(_ snapshot: Snapshot) {
        gatewayId = snapshot.gatewayId
        callsSeq = snapshot.callsSeq
        smsSeq = snapshot.smsSeq
        calls = snapshot.calls
        messages = snapshot.messages
        missedSeenAt = snapshot.missedSeenAt
        threadSeen = snapshot.threadSeen
        readBaseline = snapshot.readBaseline
    }

    // MARK: - Binding

    /// History belongs to one Redmi; pairing with another starts over.
    func bind(gatewayId newId: String) {
        guard loadIfNeeded() else {
            // Binding an unread store would treat it as a new pairing and reset it; the bind runs
            // once the file has been read (the reason is already logged).
            deferredBind = newId
            return
        }
        if let current = gatewayId, current != newId { reset() }
        if gatewayId == nil {
            gatewayId = newId
            readBaseline = Date()
            missedSeenAt = Date()
        }
        scheduleSave()
    }

    /// Forgets everything; an explicit start over, so it may replace a file not read yet.
    func reset() {
        loaded = true
        loadProblem = nil
        deferredBind = nil
        gatewayId = nil
        callsSeq = 0
        smsSeq = 0
        calls = []
        messages = []
        threadSeen = [:]
        missedSeenAt = Date()
        readBaseline = Date()
        scheduleSave()
    }

    // MARK: - Calls

    /// Returns calls that are new to this iPhone and worth a notification (recent missed calls).
    /// `pushed` holds the log ids the Redmi already announced through an Apple push.
    @discardableResult
    func applyCallSync(_ items: [CallLogEntry], lastSeq: Int64, pushed: Set<Int64> = []) -> [CallRecord] {
        guard loadIfNeeded() else { return [] }
        let news = items.compactMap { upsertCall($0, pushed: pushed) }
        callsSeq = max(callsSeq, lastSeq)
        finishCalls()
        return news
    }

    @discardableResult
    func applyCallPush(_ item: CallLogEntry, pushed: Set<Int64> = []) -> [CallRecord] {
        guard loadIfNeeded() else { return [] }
        let news = [upsertCall(item, pushed: pushed)].compactMap { $0 }
        if item.seq == callsSeq + 1 { callsSeq = item.seq }
        finishCalls()
        return news
    }

    private func upsertCall(_ item: CallLogEntry, pushed: Set<Int64>) -> CallRecord? {
        let previous = calls.first { $0.logId == item.id }
        calls.removeAll { $0.logId == item.id }
        let date = Date(timeIntervalSince1970: Double(item.date) / 1000)
        let record = CallRecord(
            logId: item.id, seq: item.seq, number: item.number, type: item.type,
            date: date, duration: item.duration, answeredOn: item.answeredOn,
            arrivedAt: previous?.arrivedAt ?? Self.arrival(of: date, pushed: pushed.contains(item.id))
        )
        calls.append(record)
        guard previous == nil, record.isMissed, record.date > readBaseline,
              Date().timeIntervalSince(record.date) < 86_400 else { return nil }
        return record
    }

    /// When this iPhone learned about an entry: now, or, for one the Redmi's Apple push already
    /// announced, the entry's own time, because the person saw that banner then.
    private static func arrival(of date: Date, pushed: Bool) -> Date {
        pushed ? min(date, Date()) : Date()
    }

    private func finishCalls() {
        calls.sort { $0.date > $1.date }
        if calls.count > Self.maxCalls { calls.removeLast(calls.count - Self.maxCalls) }
        scheduleSave()
    }

    /// A missed call counts until Recents was on screen after this iPhone learned about it, so a
    /// call that syncs only after a link gap is not taken as seen.
    func isUnseenMissed(_ call: CallRecord) -> Bool {
        call.isMissed && call.date > readBaseline && (call.arrivedAt ?? call.date) > missedSeenAt
    }

    var unseenMissedCount: Int {
        calls.filter { isUnseenMissed($0) }.count
    }

    /// Recents is on screen: every missed call listed is seen, and its notifications go.
    func markMissedSeen() {
        // Delivered banners go even when nothing here is unseen: a pushed missed call can sit in
        // Notification Center before its journal entry has arrived.
        Notifier.shared.clearMissedCalls()
        loadIfNeeded()
        missedSeenAt = Date()
        scheduleSave()
    }

    /// The newest number called from either phone, for the keypad's empty-field redial.
    var lastDialledNumber: String? {
        calls.first { $0.type == "outgoing" && !$0.number.isEmpty }?.number
    }

    func number(ofLogId logId: Int64) -> String? {
        calls.first { $0.logId == logId }?.number
    }

    // MARK: - Messages

    /// Returns received messages that are new to this iPhone and worth a notification.
    /// `pushed` holds the journal ids the Redmi already announced through an Apple push.
    @discardableResult
    func applySmsSync(_ items: [SmsEntry], lastSeq: Int64, pushed: Set<Int64> = []) -> [SmsRecord] {
        guard loadIfNeeded() else { return [] }
        let news = items.compactMap { upsertSms($0, pushed: pushed) }
        smsSeq = max(smsSeq, lastSeq)
        finishMessages()
        return news
    }

    @discardableResult
    func applySmsPush(_ item: SmsEntry, pushed: Set<Int64> = []) -> [SmsRecord] {
        guard loadIfNeeded() else { return [] }
        let news = [upsertSms(item, pushed: pushed)].compactMap { $0 }
        if item.seq == smsSeq + 1 { smsSeq = item.seq }
        finishMessages()
        return news
    }

    private func upsertSms(_ item: SmsEntry, pushed: Set<Int64>) -> SmsRecord? {
        let previous = messages.first { $0.gatewayId == item.id }
        let status: SmsStatus
        switch item.box {
        case "inbox": status = .received
        case "sending": status = .sending
        case "failed": status = .failed
        default: status = .sent
        }
        let date = Date(timeIntervalSince1970: Double(item.date) / 1000)
        messages.removeAll { $0.gatewayId == item.id }
        if previous == nil, status != .received {
            replacePendingCopy(of: item, date: date, status: status)
        }
        let record = SmsRecord(
            id: "g\(item.id)", gatewayId: item.id, requestId: nil, address: item.address, body: item.body,
            date: date, status: status, read: item.read,
            arrivedAt: previous?.arrivedAt ?? Self.arrival(of: date, pushed: pushed.contains(item.id))
        )
        messages.append(record)
        guard previous == nil, record.isIncoming, !record.read, record.date > readBaseline,
              Date().timeIntervalSince(record.date) < 86_400 else { return nil }
        return record
    }

    /// The journal entry of a message sent from this iPhone replaces one pending copy: the oldest
    /// with the same text to the same person. Two identical sends ("Tamam", "Geliyorum") are two
    /// messages, and the one that never reached the Redmi must keep its own state; it used to
    /// vanish with the first one's entry and never showed "Gönderilemedi". Runs only for the
    /// entry's first appearance: a later status change of the same entry replaces nothing more.
    private func replacePendingCopy(of item: SmsEntry, date: Date, status: SmsStatus) {
        let key = PhoneIdentity.key(item.address)
        let matches = messages.indices.filter {
            messages[$0].gatewayId == nil && messages[$0].body == item.body
                && PhoneIdentity.key(messages[$0].address) == key
                && abs(messages[$0].date.timeIntervalSince(date)) < 600
        }
        guard let oldest = matches.min(by: { messages[$0].date < messages[$1].date }) else { return }
        if status != .failed, let requestId = messages[oldest].requestId {
            // It went out after all: a "Gönderilemedi" notification for it is no longer true.
            Notifier.shared.clearSendFailure(requestId: requestId)
        }
        messages.remove(at: oldest)
    }

    private func finishMessages() {
        messages.sort { $0.date < $1.date }
        if messages.count > Self.maxMessages { messages.removeFirst(messages.count - Self.maxMessages) }
        scheduleSave()
    }

    func addPending(requestId: String, address: String, body: String) {
        loadIfNeeded()
        messages.append(SmsRecord(
            id: "p\(requestId)", gatewayId: nil, requestId: requestId, address: address, body: body,
            date: Date(), status: .sending, read: true, arrivedAt: Date()
        ))
        scheduleSave()
    }

    /// Settles a send from this iPhone. A failed copy still accepts a late success for its
    /// requestId (the Redmi hands over results it kept for a closed link), but a sent copy is never
    /// turned back into a failed one: after a retry two results can arrive for one requestId, and
    /// one of them going out is what the person needs to see.
    func completePending(requestId: String, success: Bool) {
        loadIfNeeded()
        // No copy left: the journal entry of the message already replaced it, which settles it.
        guard let index = messages.firstIndex(where: { $0.requestId == requestId && $0.gatewayId == nil }) else { return }
        if !success, messages[index].status == .sent { return }
        let wasFailed = messages[index].status == .failed
        messages[index].status = success ? .sent : .failed
        scheduleSave()
        if success {
            if wasFailed { Notifier.shared.clearSendFailure(requestId: requestId) }
        } else if !wasFailed {
            Notifier.shared.postSendFailed(messages[index])
        }
    }

    /// The pending copy of a send from this iPhone, while no journal entry has replaced it.
    func pendingMessage(requestId: String) -> SmsRecord? {
        messages.first { $0.requestId == requestId && $0.gatewayId == nil }
    }

    /// True once a send from this iPhone has a result: sent, failed, or replaced by its entry.
    func sendSettled(requestId: String) -> Bool {
        guard let pending = pendingMessage(requestId: requestId) else { return true }
        return pending.status != .sending
    }

    /// "Tekrar dene" on a pending copy: it waits for a result again under the same requestId.
    ///
    /// The date moves only when the message really goes out again. Until then the journal entry of
    /// the first attempt, if there is one, is matched against the original date (upsertSms).
    func restartPending(requestId: String, resent: Bool) {
        guard let index = messages.firstIndex(where: { $0.requestId == requestId && $0.gatewayId == nil }) else { return }
        messages[index].status = .sending
        if resent {
            messages[index].date = Date()
            finishMessages()
        } else {
            scheduleSave()
        }
    }

    /// Sends from this iPhone that are still waiting for the Redmi's result.
    ///
    /// The result window lives only in the connection's memory while the pending copy is saved, so
    /// a send that iOS cut short (the app ended in the background) stayed on "Gönderiliyor…" for
    /// good. At launch the copies whose window has closed become failed, the same state the timeout
    /// leaves, and keep their requestId so a late result can still settle them. The rest are
    /// returned with the time they have left.
    func expirePendingSends(timeout: TimeInterval) -> [(requestId: String, remaining: TimeInterval)] {
        // Unread (before the first unlock), the copies are not known yet; nothing can expire.
        guard loadIfNeeded() else { return [] }
        let now = Date()
        var open: [(requestId: String, remaining: TimeInterval)] = []
        var changed = false
        for index in messages.indices where messages[index].status == .sending && messages[index].gatewayId == nil {
            let remaining = messages[index].date.addingTimeInterval(timeout).timeIntervalSince(now)
            if remaining > 0, let requestId = messages[index].requestId {
                open.append((requestId: requestId, remaining: min(remaining, timeout)))
            } else {
                messages[index].status = .failed
                changed = true
            }
        }
        if changed { scheduleSave() }
        return open
    }

    func removeMessage(id: String) {
        loadIfNeeded()
        messages.removeAll { $0.id == id }
        scheduleSave()
    }

    func address(ofGatewayId gatewayId: Int64) -> String? {
        messages.first { $0.gatewayId == gatewayId }?.address
    }

    /// Unread until its conversation was on screen after this iPhone learned about it: a message
    /// that syncs only after a link gap is not taken as read because the thread was opened during
    /// the gap. Messages older than the first sync stay read (readBaseline, the Redmi's dates).
    func isUnread(_ message: SmsRecord) -> Bool {
        guard message.isIncoming, !message.read, message.date > readBaseline else { return false }
        let seen = threadSeen[PhoneIdentity.key(message.address)] ?? .distantPast
        return (message.arrivedAt ?? message.date) > seen
    }

    var unreadCount: Int { messages.filter { isUnread($0) }.count }

    func markThreadSeen(_ key: String) {
        loadIfNeeded()
        threadSeen[key] = Date()
        scheduleSave()
    }

    // MARK: - Persistence

    private func scheduleSave() {
        savePending = true
        saveWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor [weak self] in self?.saveNow() }
        }
        saveWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    private func saveNow() {
        savePending = false
        saveWorkItem = nil
        guard let data = snapshotData() else { return }
        let url = Self.fileURL
        Self.writeQueue.async {
            let failure = Self.write(data, to: url)
            Task { @MainActor in HistoryStore.shared.noteSaveResult(failure) }
        }
    }

    /// Writes a pending save now, on the calling thread, after any write still queued: before
    /// the app may be suspended (the end of a background catch-up, leaving the screen). A save
    /// waiting out the half-second delay was lost when iOS ended the app, and the next sync then
    /// announced the same messages and missed calls again.
    func flush() {
        if savePending {
            saveWorkItem?.cancel()
            saveWorkItem = nil
            savePending = false
            if let data = snapshotData() {
                let url = Self.fileURL
                let failure = Self.writeQueue.sync { Self.write(data, to: url) }
                noteSaveResult(failure)
                return
            }
        }
        // A write already handed to the queue finishes before the app may be suspended.
        Self.writeQueue.sync {}
    }

    /// The encoded snapshot, or nil while the file has not been read: an unread store is never
    /// written over the real one.
    private func snapshotData() -> Data? {
        guard loadIfNeeded() else { return nil }
        let snapshot = Snapshot(
            gatewayId: gatewayId, callsSeq: callsSeq, smsSeq: smsSeq, calls: calls, messages: messages,
            missedSeenAt: missedSeenAt, threadSeen: threadSeen, readBaseline: readBaseline
        )
        do {
            return try JSONEncoder().encode(snapshot)
        } catch {
            GatewayConnection.shared.log("History not saved: encoding failed")
            return nil
        }
    }

    /// Returns the error code, or nil when the file was written.
    private nonisolated static func write(_ data: Data, to url: URL) -> Int? {
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return nil
        } catch {
            return (error as NSError).code
        }
    }

    private func noteSaveResult(_ failure: Int?) {
        guard failure != lastSaveFailure else { return }
        lastSaveFailure = failure
        if let failure {
            GatewayConnection.shared.log("History save failed (error \(failure))")
        } else {
            GatewayConnection.shared.log("History saved again after a failure")
        }
    }
}
