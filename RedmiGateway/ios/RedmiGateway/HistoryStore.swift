import Combine
import Foundation

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

    var isIncoming: Bool { status == .received }
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

    private(set) var callsSeq: Int64 = 0
    private(set) var smsSeq: Int64 = 0
    private var gatewayId: String?
    /// Messages older than the first sync count as read.
    private var readBaseline: Date = Date()
    private var saveWorkItem: DispatchWorkItem?

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

    private static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("history.json")
    }

    private init() {
        guard let data = try? Data(contentsOf: Self.fileURL),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
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
        if let current = gatewayId, current != newId { reset() }
        if gatewayId == nil {
            gatewayId = newId
            readBaseline = Date()
            missedSeenAt = Date()
        }
        scheduleSave()
    }

    func reset() {
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
    @discardableResult
    func applyCallSync(_ items: [CallLogEntry], lastSeq: Int64) -> [CallRecord] {
        let news = items.compactMap { upsertCall($0) }
        callsSeq = max(callsSeq, lastSeq)
        finishCalls()
        return news
    }

    @discardableResult
    func applyCallPush(_ item: CallLogEntry) -> [CallRecord] {
        let news = [upsertCall(item)].compactMap { $0 }
        if item.seq == callsSeq + 1 { callsSeq = item.seq }
        finishCalls()
        return news
    }

    private func upsertCall(_ item: CallLogEntry) -> CallRecord? {
        let known = calls.contains { $0.logId == item.id }
        calls.removeAll { $0.logId == item.id }
        let record = CallRecord(
            logId: item.id, seq: item.seq, number: item.number, type: item.type,
            date: Date(timeIntervalSince1970: Double(item.date) / 1000), duration: item.duration,
            answeredOn: item.answeredOn
        )
        calls.append(record)
        guard !known, record.isMissed, record.date > readBaseline,
              Date().timeIntervalSince(record.date) < 86_400 else { return nil }
        return record
    }

    private func finishCalls() {
        calls.sort { $0.date > $1.date }
        if calls.count > Self.maxCalls { calls.removeLast(calls.count - Self.maxCalls) }
        scheduleSave()
    }

    var unseenMissedCount: Int {
        calls.filter { $0.isMissed && $0.date > missedSeenAt }.count
    }

    func markMissedSeen() {
        guard unseenMissedCount > 0 else { return }
        missedSeenAt = Date()
        scheduleSave()
    }

    // MARK: - Messages

    /// Returns received messages that are new to this iPhone and worth a notification.
    @discardableResult
    func applySmsSync(_ items: [SmsEntry], lastSeq: Int64) -> [SmsRecord] {
        let news = items.compactMap { upsertSms($0) }
        smsSeq = max(smsSeq, lastSeq)
        finishMessages()
        return news
    }

    @discardableResult
    func applySmsPush(_ item: SmsEntry) -> [SmsRecord] {
        let news = [upsertSms(item)].compactMap { $0 }
        if item.seq == smsSeq + 1 { smsSeq = item.seq }
        finishMessages()
        return news
    }

    private func upsertSms(_ item: SmsEntry) -> SmsRecord? {
        let known = messages.contains { $0.gatewayId == item.id }
        let status: SmsStatus
        switch item.box {
        case "inbox": status = .received
        case "sending": status = .sending
        case "failed": status = .failed
        default: status = .sent
        }
        let date = Date(timeIntervalSince1970: Double(item.date) / 1000)
        messages.removeAll { $0.gatewayId == item.id }
        if status != .received {
            // The journal entry of a message sent from this iPhone replaces its pending copy.
            let key = PhoneIdentity.key(item.address)
            messages.removeAll {
                $0.gatewayId == nil && $0.body == item.body && PhoneIdentity.key($0.address) == key
                    && abs($0.date.timeIntervalSince(date)) < 600
            }
        }
        let record = SmsRecord(
            id: "g\(item.id)", gatewayId: item.id, requestId: nil, address: item.address, body: item.body,
            date: date, status: status, read: item.read
        )
        messages.append(record)
        guard !known, record.isIncoming, !record.read, record.date > readBaseline,
              Date().timeIntervalSince(record.date) < 86_400 else { return nil }
        return record
    }

    private func finishMessages() {
        messages.sort { $0.date < $1.date }
        if messages.count > Self.maxMessages { messages.removeFirst(messages.count - Self.maxMessages) }
        scheduleSave()
    }

    func addPending(requestId: String, address: String, body: String) {
        messages.append(SmsRecord(
            id: "p\(requestId)", gatewayId: nil, requestId: requestId, address: address, body: body,
            date: Date(), status: .sending, read: true
        ))
        scheduleSave()
    }

    /// Settles a send from this iPhone. A failed copy still accepts a late success for its
    /// requestId (the Redmi hands over results it kept for a closed link), but a sent copy is never
    /// turned back into a failed one: after a retry two results can arrive for one requestId, and
    /// one of them going out is what the person needs to see.
    func completePending(requestId: String, success: Bool) {
        guard let index = messages.firstIndex(where: { $0.requestId == requestId && $0.gatewayId == nil }) else { return }
        if !success, messages[index].status == .sent { return }
        messages[index].status = success ? .sent : .failed
        scheduleSave()
    }

    /// The pending copy of a send from this iPhone, while no journal entry has replaced it.
    func pendingMessage(requestId: String) -> SmsRecord? {
        messages.first { $0.requestId == requestId && $0.gatewayId == nil }
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
        messages.removeAll { $0.id == id }
        scheduleSave()
    }

    func isUnread(_ message: SmsRecord) -> Bool {
        guard message.isIncoming, !message.read else { return false }
        let seen = max(threadSeen[PhoneIdentity.key(message.address)] ?? .distantPast, readBaseline)
        return message.date > seen
    }

    var unreadCount: Int { messages.filter { isUnread($0) }.count }

    func markThreadSeen(_ key: String) {
        threadSeen[key] = Date()
        scheduleSave()
    }

    // MARK: - Persistence

    private func scheduleSave() {
        saveWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor [weak self] in self?.saveNow() }
        }
        saveWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    private func saveNow() {
        let snapshot = Snapshot(
            gatewayId: gatewayId, callsSeq: callsSeq, smsSeq: smsSeq, calls: calls, messages: messages,
            missedSeenAt: missedSeenAt, threadSeen: threadSeen, readBaseline: readBaseline
        )
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        let url = Self.fileURL
        DispatchQueue.global(qos: .utility).async {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }
}
