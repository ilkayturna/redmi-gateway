import Foundation

/// Reports waiting in `Documents/diag-outbox/` until the Redmi confirmed every part.
///
/// File work only, callable from any thread: MetricKit hands over its reports on a queue of its
/// own, and a report can be tens of KB, which is better not written on the main thread during a
/// call. The sender (DiagnosticsOutbox) runs on the main actor and reads one report at a time.
enum DiagnosticsOutboxStore {
    struct Stored {
        let name: String
        let bytes: Int
        /// The original size when the report was cut to fit 64 parts.
        let truncatedFrom: Int?
        /// Older reports deleted to keep the outbox under its caps.
        let evicted: [String]
    }

    struct WriteFailure: Error {
        let reason: String
    }

    static var directory: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("diag-outbox", isDirectory: true)
    }

    /// Writes one report and trims the outbox to its caps.
    static func write(kind: String, data: Data, createdAt: Date = Date()) -> Result<Stored, WriteFailure> {
        guard DiagnosticsFormat.kinds.contains(kind) else { return .failure(WriteFailure(reason: "unknown kind \(kind)")) }
        guard let directory else { return .failure(WriteFailure(reason: "no documents folder")) }
        var payload = data
        var truncatedFrom: Int?
        if payload.count > DiagnosticsFormat.maxPayloadBytes {
            // The Redmi takes at most 64 parts. The head of a report names what happened, so it is
            // the part worth keeping.
            truncatedFrom = payload.count
            payload = payload.prefix(DiagnosticsFormat.maxPayloadBytes)
        }
        let name = DiagnosticsFormat.outboxFileName(id: UUID().uuidString, kind: kind, createdAt: createdAt)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try payload.write(to: directory.appendingPathComponent(name), options: .atomic)
        } catch {
            let nsError = error as NSError
            return .failure(WriteFailure(reason: "\(nsError.domain) \(nsError.code)"))
        }
        return .success(Stored(name: name, bytes: payload.count, truncatedFrom: truncatedFrom, evicted: trim()))
    }

    /// The reports waiting, oldest first. No folder simply means nothing was ever queued.
    static func entries() -> [DiagnosticsFormat.OutboxEntry] {
        guard let directory,
              let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        return names.compactMap { DiagnosticsFormat.parseOutboxFileName($0) }
            .sorted { ($0.createdAt, $0.name) < ($1.createdAt, $1.name) }
    }

    static func read(_ entry: DiagnosticsFormat.OutboxEntry) -> Data? {
        guard let directory else { return nil }
        return try? Data(contentsOf: directory.appendingPathComponent(entry.name))
    }

    static func remove(_ name: String) {
        guard let directory else { return }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
    }

    /// Keeps the outbox at 20 files and 2 MB, oldest dropped first; returns what was dropped.
    static func trim() -> [String] {
        guard let directory,
              let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return [] }
        var files: [DiagnosticsFormat.OutboxFile] = []
        for name in names {
            guard let entry = DiagnosticsFormat.parseOutboxFileName(name) else { continue }
            let path = directory.appendingPathComponent(name).path
            let size = ((try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber)?.intValue ?? 0
            files.append(DiagnosticsFormat.OutboxFile(name: name, bytes: size, createdAt: entry.createdAt))
        }
        let evicted = DiagnosticsFormat.outboxEvictions(files)
        for name in evicted { remove(name) }
        return evicted
    }
}

/// Sends the outbox to the Redmi as `client_diag` parts (docs/PROTOCOL.md).
///
/// The owner should never have to paste a log from this iPhone again: a crash text, a MetricKit
/// report or an unclean exit is kept here until the Redmi has every part, and the PC pulls it from
/// the Redmi. One part travels at a time and waits for its ack. Reports are large next to call
/// traffic, so they go only on a local link and never while a call is up.
@MainActor
final class DiagnosticsOutbox {
    static let shared = DiagnosticsOutbox()

    private static let ackTimeout: TimeInterval = 10
    /// Refusals after which a report is dropped, so one the Redmi cannot take does not block the
    /// ones behind it forever.
    private static let maxRefusals = 3

    private struct Sending {
        let entry: DiagnosticsFormat.OutboxEntry
        let data: Data
        let parts: Int
        var part: Int
    }

    private var sending: Sending?
    private var ackTask: Task<Void, Never>?
    /// Set for the rest of the connection when the Redmi cannot take reports (an older build), or
    /// refused or never confirmed a part; cleared when the socket closes.
    private var pausedReason: String?
    private var refusals: [String: Int] = [:]
    /// Reports may be waiting from an earlier run, so the first check looks.
    private var mayHavePending = true
    /// Why sending waits, logged once per change instead of on every tick.
    private var lastHoldReason: String?

    private init() {}

    private func log(_ message: String) {
        GatewayConnection.shared.log("Diagnostics outbox: " + message)
    }

    /// Stores a report made on the main actor (an exception text, an unclean exit) and tries to
    /// send it. Returns whether the report is on disk.
    @discardableResult
    func enqueue(kind: String, data: Data) -> Bool {
        switch DiagnosticsOutboxStore.write(kind: kind, data: data) {
        case .success(let stored):
            noteStored(stored, kind: kind)
            return true
        case .failure(let failure):
            log("\(kind) of \(data.count) bytes could not be stored: \(failure.reason)")
            return false
        }
    }

    /// Takes note of a report stored on another thread (MetricKit) and tries to send it.
    func noteStored(_ stored: DiagnosticsOutboxStore.Stored, kind: String) {
        if let original = stored.truncatedFrom {
            log("\(kind) cut from \(original) to \(stored.bytes) bytes to fit 64 parts")
        }
        if !stored.evicted.isEmpty {
            log("dropped the \(stored.evicted.count) oldest report(s) to stay under 20 files and 2 MB")
        }
        mayHavePending = true
        kick()
    }

    /// Sends the next part when the link allows it. Called after authentication, on every telemetry
    /// tick, after each ack and when a report is stored; cheap when there is nothing to do.
    func kick() {
        // A part is on its way; its ack or its timeout continues.
        guard ackTask == nil else { return }
        let connection = GatewayConnection.shared
        // No socket: the next authentication kicks again.
        guard connection.isAuthenticated else { return }
        guard sending != nil || mayHavePending else { return }
        if let reason = holdReason(connection) {
            noteHold(reason)
            return
        }
        lastHoldReason = nil
        if sending == nil, !loadNext() { return }
        sendCurrentPart()
    }

    private func holdReason(_ connection: GatewayConnection) -> String? {
        if let pausedReason { return pausedReason }
        // Away from home the link is a VPN over the Redmi's mobile data; reports wait for home.
        if connection.isRemoteLink { return "remote link" }
        // The call owns the link; reports wait until it ends.
        if connection.currentCall != nil || CallKitManager.shared.hasCalls { return "call in progress" }
        return nil
    }

    private func noteHold(_ reason: String) {
        guard reason != lastHoldReason else { return }
        lastHoldReason = reason
        log("waiting: \(reason)")
    }

    private func pause(_ reason: String) {
        pausedReason = reason
        // Already said where the pause starts; the hold line would only repeat it.
        lastHoldReason = reason
    }

    private func loadNext() -> Bool {
        guard let entry = DiagnosticsOutboxStore.entries().first else {
            mayHavePending = false
            return false
        }
        guard let data = DiagnosticsOutboxStore.read(entry) else {
            // Most likely protected data before the first unlock after a restart; the file stays.
            let reason = "\(entry.kind) id=\(entry.id.prefix(8)) could not be read"
            log(reason + "; it stays for the next connection")
            pause(reason)
            return false
        }
        let parts = DiagnosticsFormat.partCount(byteCount: data.count)
        guard parts <= DiagnosticsFormat.maxParts else {
            // Only the store writes here and it cuts reports to size, so this is a stray file.
            log("\(entry.kind) id=\(entry.id.prefix(8)) needs \(parts) parts (max \(DiagnosticsFormat.maxParts)); removed")
            DiagnosticsOutboxStore.remove(entry.name)
            return false
        }
        sending = Sending(entry: entry, data: data, parts: parts, part: 0)
        log("sending \(entry.kind) id=\(entry.id.prefix(8)) bytes=\(data.count) parts=\(parts)")
        return true
    }

    private func sendCurrentPart() {
        guard let current = sending, let chunk = DiagnosticsFormat.part(current.part, of: current.data) else {
            log("lost track of the current part; the report starts again on the next tick")
            sending = nil
            return
        }
        GatewayConnection.shared.send([
            "type": "client_diag",
            "id": current.entry.id,
            "kind": current.entry.kind,
            "createdAt": DiagnosticsFormat.isoTimestamp(current.entry.createdAt),
            "part": current.part,
            "parts": current.parts,
            "enc": "base64",
            "data": chunk
        ])
        let id = current.entry.id
        let part = current.part
        ackTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(DiagnosticsOutbox.ackTimeout * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            self.ackTimedOut(id: id, part: part)
        }
    }

    /// The Redmi's answer to one part.
    func handleAck(_ ack: AckMessage) {
        guard let current = sending, ackTask != nil, ack.id == current.entry.id, ack.part == current.part else {
            let id = String((ack.id ?? "?").prefix(8))
            let part = ack.part.map { String($0) } ?? "?"
            log("ignored an ack for id=\(id) part=\(part) (not the part in flight)")
            return
        }
        ackTask?.cancel()
        ackTask = nil
        guard ack.success else {
            refused(current, reason: ack.error ?? "no reason given")
            return
        }
        if current.part + 1 < current.parts {
            sending?.part = current.part + 1
        } else {
            DiagnosticsOutboxStore.remove(current.entry.name)
            sending = nil
            refusals[current.entry.id] = nil
            log("delivered \(current.entry.kind) id=\(current.entry.id.prefix(8)) bytes=\(current.data.count) parts=\(current.parts)")
        }
        kick()
    }

    private func ackTimedOut(id: String, part: Int) {
        // An ack that arrived meanwhile already moved on; this timer is stale then.
        guard let current = sending, current.entry.id == id, current.part == part, ackTask != nil else { return }
        ackTask = nil
        sending = nil
        // A dead socket looks the same, so a timeout never drops a report; it waits for the next
        // connection, which starts it again from the first part.
        let reason = "no ack for part \(part) of \(current.entry.kind) id=\(id.prefix(8)) within \(Int(Self.ackTimeout)) s"
        log(reason + "; paused until the next connection")
        pause(reason)
    }

    private func refused(_ current: Sending, reason: String) {
        sending = nil
        let count = (refusals[current.entry.id] ?? 0) + 1
        refusals[current.entry.id] = count
        let label = "\(current.entry.kind) id=\(current.entry.id.prefix(8)) part \(current.part)"
        if count >= Self.maxRefusals {
            DiagnosticsOutboxStore.remove(current.entry.name)
            refusals[current.entry.id] = nil
            log("Redmi refused \(label): \(reason); dropped after \(count) refusals")
        } else {
            log("Redmi refused \(label): \(reason); paused until the next connection")
        }
        pause("Redmi refused \(label)")
    }

    /// The Redmi answered `unknown_command` for `client_diag`: an older build. Reports stay on this
    /// iPhone until a Redmi that takes them connects.
    func handleUnsupported() {
        ackTask?.cancel()
        ackTask = nil
        sending = nil
        guard pausedReason == nil else { return }
        log("the Redmi does not take client_diag (older build); reports stay on this iPhone")
        pause("Redmi does not take client_diag")
    }

    /// The socket closed. The next connection starts the current report again from its first part;
    /// the Redmi overwrites a part it already has.
    func connectionClosed() {
        ackTask?.cancel()
        ackTask = nil
        sending = nil
        pausedReason = nil
        lastHoldReason = nil
    }
}

/// Telemetry lines not yet handed to the socket, kept on disk.
///
/// The ring that feeds `client_log` lives in memory, so a crash, a watchdog kill or iOS ending the
/// suspended app took its unsent lines along: exactly the lines that explain the ending. Each line
/// now also goes to a small file, rewritten whenever a batch leaves, and the next launch sends
/// what is left first, tagged "[prev]".
enum TelemetryBacklog {
    private static let queueKey = DispatchSpecificKey<Bool>()
    private static let queue: DispatchQueue = {
        let serial = DispatchQueue(label: "com.ilkay.redmigateway.telemetry-backlog", qos: .utility)
        serial.setSpecific(key: TelemetryBacklog.queueKey, value: true)
        return serial
    }()
    /// Touched on `queue` only.
    private static var failureReported = false

    private static var url: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("telemetry-unsent.txt")
    }

    /// Lines that just entered the ring.
    static func append(_ lines: [String]) {
        guard !lines.isEmpty else { return }
        let data = encode(lines)
        queue.async { write(data, replacing: false) }
    }

    /// Everything still unsent, after a batch left or the ring dropped its oldest lines.
    static func replace(with lines: [String]) {
        let data = encode(lines)
        queue.async { write(data, replacing: true) }
    }

    /// Reads and removes the previous run's unsent lines. Called first at launch, before this run
    /// writes any of its own.
    static func takePrevious() -> [String] {
        queue.sync { () -> [String] in
            // No file: the previous run sent everything, or this is the first run.
            guard let url, let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
            try? FileManager.default.removeItem(at: url)
            return text.split(separator: "\n").map { DiagnosticsFormat.unescapeBacklogLine(String($0)) }
        }
    }

    /// Waits for queued writes, so the exception handler does not lose the last lines. Skipped on
    /// the backlog queue itself, where waiting would deadlock.
    static func drain() {
        guard DispatchQueue.getSpecific(key: queueKey) == nil else { return }
        queue.sync {}
    }

    private static func encode(_ lines: [String]) -> Data {
        Data(lines.map { DiagnosticsFormat.escapeBacklogLine($0) + "\n" }.joined().utf8)
    }

    private static func write(_ data: Data, replacing: Bool) {
        guard let url else {
            reportFailure("no documents folder")
            return
        }
        do {
            if replacing {
                if data.isEmpty {
                    if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
                } else {
                    try data.write(to: url, options: .atomic)
                }
            } else if let handle = try? FileHandle(forWritingTo: url) {
                defer { try? handle.close() }
                _ = try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } else {
                try data.write(to: url, options: .atomic)
            }
        } catch {
            let nsError = error as NSError
            reportFailure("\(nsError.domain) \(nsError.code)")
        }
    }

    /// Said once per run: the log line itself lands in this backlog, and a line per failure would
    /// feed the failure.
    private static func reportFailure(_ reason: String) {
        guard !failureReported else { return }
        failureReported = true
        Task { @MainActor in GatewayConnection.shared.log("Telemetry backlog could not be written: \(reason)") }
    }
}

/// Remembers on disk whether the app is in use; see DiagnosticsFormat.runMarkerState.
///
/// NSException is the only ending this process sees itself. A watchdog kill, a memory kill or a
/// Swift trap during a call leaves nothing behind, and MetricKit reports it only later, if at all.
/// A marker left at `running` tells the next launch that the previous run ended while in use, and
/// what it was doing then.
enum ExitMarker {
    /// What was written last, so the marker is written on change only. Main thread only.
    private static var lastKey = ""
    private static var failureLogged = false

    private static var url: URL? {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("run-marker.json")
    }

    /// Writes the marker when something in it changed. Main thread only.
    static func record(inForeground: Bool, appState: String, calls: Int, audioRunning: Bool, muted: Bool, build: String) {
        let state = DiagnosticsFormat.runMarkerState(inForeground: inForeground, calls: calls, audioRunning: audioRunning)
        let key = "\(state)|\(appState)|\(calls)|\(audioRunning)|\(muted)|\(build)"
        guard key != lastKey else { return }
        lastKey = key
        write([
            "state": state,
            "at": DiagnosticsFormat.isoTimestamp(Date()),
            "appState": appState,
            "calls": calls,
            "audio": audioRunning ? "running" : "stopped",
            "muted": muted ? 1 : 0,
            "appBuild": build
        ])
    }

    /// iOS is ending the app on purpose (willTerminate).
    static func recordTerminated() {
        lastKey = "terminated"
        write(["state": "terminated", "at": DiagnosticsFormat.isoTimestamp(Date())])
    }

    private static func write(_ marker: [String: Any]) {
        do {
            guard let url else { throw CocoaError(.fileNoSuchFile) }
            let data = try JSONSerialization.data(withJSONObject: marker, options: [.sortedKeys])
            try data.write(to: url, options: .atomic)
        } catch {
            // Before the first unlock after a restart the file cannot be written; said once per run.
            guard !failureLogged else { return }
            failureLogged = true
            let code = (error as NSError).code
            Task { @MainActor in GatewayConnection.shared.log("Run marker could not be written (\(code))") }
        }
    }

    /// At launch, before the first `record`: a previous run that ended while in use and without an
    /// exception becomes an `exit-unclean` report with the marker's last state.
    @MainActor
    static func reportPreviousRun(crashRecorded: Bool, launchAppState: String) {
        // No marker: the first launch of this build, or protected data before the first unlock.
        guard let url, let data = try? Data(contentsOf: url) else { return }
        guard let marker = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            GatewayConnection.shared.log("Run marker unreadable (\(data.count) bytes); how the previous run ended is unknown")
            return
        }
        let state = marker["state"] as? String
        guard DiagnosticsFormat.previousRunEndedUncleanly(markerState: state, crashRecorded: crashRecorded) else { return }
        let summary = ["appState", "calls", "audio", "muted", "appBuild", "at"]
            .map { key in "\(key)=\(marker[key] ?? "?")" }
            .joined(separator: " ")
        GatewayConnection.shared.log("Previous run ended while in use, without an exception: \(summary)")
        var report = marker
        report["detectedAt"] = DiagnosticsFormat.isoTimestamp(Date())
        report["launchAppState"] = launchAppState
        guard let payload = try? JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]) else {
            GatewayConnection.shared.log("Unclean exit report could not be encoded")
            return
        }
        DiagnosticsOutbox.shared.enqueue(kind: "exit-unclean", data: payload)
    }
}
