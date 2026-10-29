import Foundation

/// The pure arithmetic behind what this iPhone sends to the Redmi about itself.
///
/// Foundation only, so a standalone swiftc test (ios/Tests/DiagnosticsFormatTests.swift) covers it
/// without a simulator. Every limit here comes from the Redmi: it drops any WebSocket text longer
/// than 16,384 chars before it even decrypts it, a sealed frame is the inner JSON in base64 (4/3 of
/// its UTF-8 bytes) plus about 30 chars, and it cuts a stored log line at 4,000 chars. A frame over
/// the gate vanishes without a trace on either phone, so everything that can grow is split here.
enum DiagnosticsFormat {

    // MARK: - client_diag parts

    /// Raw bytes per `client_diag` part: 8,000 base64 chars, about 8.3 KB of inner JSON and about
    /// 11.1 K sealed chars, well under the 16,384-char gate.
    static let partBytes = 6000
    /// The Redmi accepts at most this many parts for one payload.
    static let maxParts = 64
    /// The largest payload that fits in `maxParts` parts.
    static var maxPayloadBytes: Int { partBytes * maxParts }

    /// Every `kind` the Redmi files a payload under.
    static let kinds: Set<String> = [
        "metrickit-crash", "metrickit-hang", "metrickit-cpu", "metrickit-disk", "metrickit-metric",
        "nsexception", "exit-unclean", "log-tail"
    ]

    /// Parts a payload of `byteCount` bytes travels in; an empty payload is still one (empty) part.
    static func partCount(byteCount: Int) -> Int {
        max(1, (byteCount + partBytes - 1) / partBytes)
    }

    /// Base64 of part `index` of `data`, or nil when the payload has no such part.
    static func part(_ index: Int, of data: Data) -> String? {
        guard index >= 0, index < partCount(byteCount: data.count) else { return nil }
        let start = data.startIndex + index * partBytes
        let end = min(start + partBytes, data.endIndex)
        return data.subdata(in: start..<end).base64EncodedString()
    }

    /// The Redmi's rule for a payload id: `^[A-Za-z0-9-]{8,64}$`. A UUID string passes.
    static func isValidId(_ id: String) -> Bool {
        guard (8...64).contains(id.utf8.count) else { return false }
        return id.utf8.allSatisfy { byte in
            (byte >= 0x30 && byte <= 0x39) || (byte >= 0x41 && byte <= 0x5A)
                || (byte >= 0x61 && byte <= 0x7A) || byte == 0x2D
        }
    }

    /// `createdAt` as the Redmi expects it, for example `2026-10-29T08:00:00Z`.
    static func isoTimestamp(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    // MARK: - Outbox files

    /// One payload waiting in `Documents/diag-outbox/`. Everything the sender needs is in the file
    /// name, so listing the folder is enough to find the oldest payload and what it is.
    struct OutboxEntry: Equatable {
        let name: String
        let id: String
        let kind: String
        let createdAt: Date
    }

    /// `<created ms>_<id>_<kind>.json`. Neither an id nor a kind can contain an underscore.
    static func outboxFileName(id: String, kind: String, createdAt: Date) -> String {
        let millis = Int64((createdAt.timeIntervalSince1970 * 1000).rounded())
        return "\(millis)_\(id)_\(kind).json"
    }

    /// Reads a name written by `outboxFileName`; anything else in the folder is not a payload.
    static func parseOutboxFileName(_ name: String) -> OutboxEntry? {
        guard name.hasSuffix(".json") else { return nil }
        let stem = String(name.dropLast(".json".count))
        let fields = stem.split(separator: "_", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        guard fields.count == 3, let millis = Int64(fields[0]), isValidId(fields[1]), kinds.contains(fields[2]) else {
            return nil
        }
        return OutboxEntry(name: name, id: fields[1], kind: fields[2],
                           createdAt: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    struct OutboxFile {
        let name: String
        let bytes: Int
        let createdAt: Date
    }

    /// Names to delete, oldest first, so the outbox keeps at most `maxFiles` files and `maxBytes`
    /// bytes. A phone that cannot reach the Redmi for weeks must not fill up with reports.
    static func outboxEvictions(_ files: [OutboxFile], maxFiles: Int = 20, maxBytes: Int = 2 * 1024 * 1024) -> [String] {
        let oldestFirst = files.sorted { ($0.createdAt, $0.name) < ($1.createdAt, $1.name) }
        var count = oldestFirst.count
        var total = oldestFirst.reduce(0) { $0 + $1.bytes }
        var evicted: [String] = []
        for file in oldestFirst {
            guard count > maxFiles || total > maxBytes else { break }
            evicted.append(file.name)
            count -= 1
            total -= file.bytes
        }
        return evicted
    }

    // MARK: - client_log lines and batches

    /// The longest message kept as one ring line, in UTF-16 units (what the Redmi counts). The
    /// Redmi cuts stored lines at 4,000 and adds its own prefix; the line also carries a time stamp
    /// and, when split, an "[i/n] " tag.
    static let maxLineChars = 3500
    /// The most JSON-escaped UTF-8 bytes one piece may take, so a single piece always fits a batch
    /// even when it is made of quotes, backslashes or control characters.
    static let maxLineEscapedBytes = 8000
    /// The JSON-escaped UTF-8 bytes the lines of one `client_log` batch may take. With the envelope
    /// that is about 9.1 KB of JSON and about 12.2 K sealed chars.
    static let batchMaxEscapedBytes = 9000
    /// The Redmi keeps at most 200 lines of a frame; 40 keeps a batch small on a slow link.
    static let batchMaxLines = 40

    /// Bytes `scalar` takes inside a JSON string written by JSONSerialization, as an upper bound.
    /// Quotes, backslashes and the slash (Apple escapes it) take two; every control character is
    /// counted as a six-byte `\u00XX`, which is never less than what is written. Everything else is
    /// written as plain UTF-8.
    static func escapedBytes(_ scalar: Unicode.Scalar) -> Int {
        switch scalar.value {
        case 0x22, 0x5C, 0x2F: return 2
        case 0..<0x20: return 6
        case 0x20..<0x80: return 1
        case 0x80..<0x800: return 2
        case 0x800..<0x10000: return 3
        default: return 4
        }
    }

    static func escapedByteCount(_ text: String) -> Int {
        text.unicodeScalars.reduce(0) { $0 + escapedBytes($1) }
    }

    /// Splits a message that is too long for one line into "[i/n] " pieces; a short one comes back
    /// unchanged. Pieces end on character boundaries, so no emoji or accent is cut in half.
    static func splitLine(_ message: String) -> [String] {
        if message.utf16.count <= maxLineChars, escapedByteCount(message) <= maxLineEscapedBytes {
            return [message]
        }
        var pieces: [String] = []
        var current = ""
        var currentUnits = 0
        var currentBytes = 0
        for character in message {
            let units = character.utf16.count
            let bytes = character.unicodeScalars.reduce(0) { $0 + escapedBytes($1) }
            if !current.isEmpty, currentUnits + units > maxLineChars || currentBytes + bytes > maxLineEscapedBytes {
                pieces.append(current)
                current = ""
                currentUnits = 0
                currentBytes = 0
            }
            current.append(character)
            currentUnits += units
            currentBytes += bytes
        }
        if !current.isEmpty { pieces.append(current) }
        let total = pieces.count
        return pieces.enumerated().map { "[\($0.offset + 1)/\(total)] \($0.element)" }
    }

    /// How many lines from the front of `lines` the next batch takes: at least one when any is
    /// waiting (a single piece never exceeds the budget), then as many as fit both limits.
    static func batchCount<S: Sequence>(_ lines: S, maxLines: Int = DiagnosticsFormat.batchMaxLines,
                                        maxBytes: Int = DiagnosticsFormat.batchMaxEscapedBytes) -> Int
        where S.Element == String {
        var count = 0
        var bytes = 0
        for line in lines {
            guard count < maxLines else { break }
            // The two quotes and the comma around each array element.
            let cost = escapedByteCount(line) + 3
            if count > 0, bytes + cost > maxBytes { break }
            count += 1
            bytes += cost
        }
        return count
    }

    private static let stampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        // A fixed format needs the POSIX locale, or a 12-hour setting on the phone can change it.
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    /// `HH:mm:ss.SSS` in local time, so iPhone lines line up with the Redmi's millisecond log.
    static func timestamp(_ date: Date) -> String {
        stampFormatter.string(from: date)
    }

    /// Marks a line the previous run wrote but never sent; tagging twice changes nothing.
    static let previousRunTag = "[prev] "

    static func taggedAsPrevious(_ line: String) -> String {
        line.hasPrefix(previousRunTag) ? line : previousRunTag + line
    }

    /// One ring line as one line of the on-disk backlog: a backslash, a newline or a carriage
    /// return inside the line is escaped so the file stays one entry per line.
    static func escapeBacklogLine(_ line: String) -> String {
        var out = ""
        out.reserveCapacity(line.utf8.count)
        for scalar in line.unicodeScalars {
            switch scalar {
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    static func unescapeBacklogLine(_ line: String) -> String {
        var out = ""
        var escaping = false
        for scalar in line.unicodeScalars {
            if escaping {
                switch scalar {
                case "n": out += "\n"
                case "r": out += "\r"
                default: out.unicodeScalars.append(scalar)
                }
                escaping = false
            } else if scalar == "\\" {
                escaping = true
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
        if escaping { out += "\\" }
        return out
    }

    // MARK: - Build date and TestFlight

    /// TestFlight builds stop opening this many days after upload; CI uploads right after building.
    static let testFlightDays = 90

    /// Reads Info.plist `RGBuildDate`, which CI writes as `2026-10-28T22:17:33Z`.
    static func parseBuildDate(_ text: String) -> Date? {
        ISO8601DateFormatter().date(from: text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// `90 - whole days since the build`. A clock behind the build date counts as day zero; the
    /// value can go to zero or below, which only an expired build would ever see.
    static func testFlightDaysLeft(buildDate: Date, now: Date) -> Int {
        let elapsed = now.timeIntervalSince(buildDate)
        let days = elapsed > 0 ? Int(elapsed / 86_400) : 0
        return testFlightDays - days
    }

    /// The hardware identifier, for example `iPhone16,2` (`arm64` on the simulator).
    static func machineIdentifier() -> String {
        var info = utsname()
        guard uname(&info) == 0 else { return "unknown" }
        let bytes = withUnsafeBytes(of: &info.machine) { raw in Array(raw.prefix(while: { $0 != 0 })) }
        let text = String(decoding: bytes, as: UTF8.self)
        return text.isEmpty ? "unknown" : text
    }

    // MARK: - Run marker

    /// The marker the app keeps while it runs: `running` while it is on screen, in a call or
    /// carrying call audio, `idle` otherwise. iOS ends an idle background app without notice all the
    /// time, so only a `running` marker left behind is worth a report.
    static func runMarkerState(inForeground: Bool, calls: Int, audioRunning: Bool) -> String {
        (inForeground || calls > 0 || audioRunning) ? "running" : "idle"
    }

    /// Whether the previous run ended while in use without saying so: no clean exit and no recorded
    /// exception (that one already travels as its own report).
    static func previousRunEndedUncleanly(markerState: String?, crashRecorded: Bool) -> Bool {
        markerState == "running" && !crashRecorded
    }

    // MARK: - MetricKit

    private static func jsonObject(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func list(_ object: [String: Any], _ key: String) -> [[String: Any]] {
        object[key] as? [[String: Any]] ?? []
    }

    /// The `client_diag` kind for a MetricKit diagnostic payload: the most serious diagnostic it
    /// holds decides. A slow-launch report (iOS 16+) is filed with hangs; anything unknown is filed
    /// with crashes, where it is looked at first.
    static func metricKitKind(diagnosticJSON data: Data) -> String {
        guard let object = jsonObject(data) else { return "metrickit-crash" }
        if !list(object, "crashDiagnostics").isEmpty { return "metrickit-crash" }
        if !list(object, "hangDiagnostics").isEmpty { return "metrickit-hang" }
        if !list(object, "cpuExceptionDiagnostics").isEmpty { return "metrickit-cpu" }
        if !list(object, "diskWriteExceptionDiagnostics").isEmpty { return "metrickit-disk" }
        if !list(object, "appLaunchDiagnostics").isEmpty { return "metrickit-hang" }
        return "metrickit-crash"
    }

    private static func text(_ value: Any?, limit: Int = 160) -> String? {
        guard let value else { return nil }
        let raw: String
        if let string = value as? String {
            raw = string
        } else if let number = value as? NSNumber {
            raw = number.stringValue
        } else {
            raw = "\(value)"
        }
        let flat = raw.replacingOccurrences(of: "\n", with: " ")
        return flat.count > limit ? String(flat.prefix(limit)) + "…" : flat
    }

    /// The top frames of the attributed (crashing or hanging) thread: `binary+0xoffset`, top first.
    static func topFrames(_ diagnostic: [String: Any], limit: Int = 6) -> [String] {
        guard let tree = diagnostic["callStackTree"] as? [String: Any] else { return [] }
        let stacks = list(tree, "callStacks")
        let stack = stacks.first(where: { ($0["threadAttributed"] as? Bool) == true }) ?? stacks.first
        guard let stack else { return [] }
        var frame = list(stack, "callStackRootFrames").first
        var frames: [String] = []
        while let current = frame, frames.count < limit {
            let binary = current["binaryName"] as? String ?? "?"
            if let offset = (current["offsetIntoBinaryTextSegment"] as? NSNumber)?.int64Value {
                frames.append("\(binary)+0x\(String(offset, radix: 16))")
            } else {
                frames.append(binary)
            }
            frame = list(current, "subFrames").first
        }
        return frames
    }

    /// One log line about a diagnostic payload; the raw JSON travels separately.
    static func metricKitDiagnosticSummary(_ data: Data) -> String {
        guard let object = jsonObject(data) else { return "unreadable payload of \(data.count) bytes" }
        var parts: [String] = []
        let groups = [("crashDiagnostics", "crash"), ("hangDiagnostics", "hang"),
                      ("cpuExceptionDiagnostics", "cpu"), ("diskWriteExceptionDiagnostics", "disk"),
                      ("appLaunchDiagnostics", "launch")]
        for (key, label) in groups {
            let count = list(object, key).count
            if count > 0 { parts.append("\(label)=\(count)") }
        }
        let first = list(object, "crashDiagnostics").first ?? list(object, "hangDiagnostics").first
        if let first {
            let meta = first["diagnosticMetaData"] as? [String: Any] ?? [:]
            let fields = [("exceptionType", "type"), ("exceptionCode", "code"), ("signal", "signal"),
                          ("terminationReason", "reason"), ("hangDuration", "hang"),
                          ("appBuildVersion", "build"), ("osVersion", "os")]
            for (key, label) in fields {
                if let value = text(meta[key]) { parts.append("\(label)=\(value)") }
            }
            let frames = topFrames(first)
            if !frames.isEmpty { parts.append("frames=" + frames.joined(separator: " < ")) }
        }
        return parts.isEmpty ? "no diagnostics in \(data.count) bytes" : parts.joined(separator: " ")
    }

    /// `cumulativeAppWatchdogExitCount` -> `AppWatchdog`.
    static func shortExitName(_ name: String) -> String {
        var short = name
        if short.hasPrefix("cumulative") { short.removeFirst("cumulative".count) }
        if short.hasSuffix("ExitCount") { short.removeLast("ExitCount".count) }
        return short
    }

    /// One log line about a daily metric payload: the non-zero app exit counts, which is where a
    /// watchdog or memory kill in the background shows up.
    static func metricKitMetricSummary(_ data: Data) -> String {
        guard let object = jsonObject(data) else { return "unreadable payload of \(data.count) bytes" }
        var parts: [String] = []
        if let begin = text(object["timeStampBegin"]) { parts.append("from=\(begin)") }
        if let meta = object["metaData"] as? [String: Any], let build = text(meta["appBuildVersion"]) {
            parts.append("build=\(build)")
        }
        if let exits = object["applicationExitMetrics"] as? [String: Any] {
            for (key, label) in [("foregroundExitData", "fg"), ("backgroundExitData", "bg")] {
                guard let counts = exits[key] as? [String: Any] else { continue }
                var nonZero: [String] = []
                for (name, value) in counts {
                    guard let number = value as? NSNumber, number.intValue > 0 else { continue }
                    nonZero.append("\(shortExitName(name))=\(number.intValue)")
                }
                if !nonZero.isEmpty { parts.append("\(label)[\(nonZero.sorted().joined(separator: ","))]") }
            }
        }
        return parts.isEmpty ? "no exit data in \(data.count) bytes" : parts.joined(separator: " ")
    }
}
