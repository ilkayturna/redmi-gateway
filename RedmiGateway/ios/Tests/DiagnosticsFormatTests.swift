import Foundation

// A standalone executable: no device, MetricKit or socket.
// Compile this file together with the production DiagnosticsFormat.swift.
@main
struct DiagnosticsFormatTests {
    static var failures = 0
    static var checks = 0

    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        checks += 1
        if !condition() { failures += 1; print("FAIL: \(message)") }
    }

    /// The Redmi drops any WebSocket text longer than this before decrypting it.
    static let frameGate = 16_384

    /// What a sealed frame of `json` costs on the wire: {"t":"s","c":N,"d":"<base64(ciphertext||tag)>"}.
    static func sealedChars(_ json: Data) -> Int {
        let sealedBytes = json.count + 16
        return (sealedBytes + 2) / 3 * 4 + 40
    }

    static func json(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    static func main() {
        partsCoverThePayloadExactly()
        aFullPartFitsTheFrameGate()
        idsFollowTheRedmisRule()
        outboxNamesRoundTrip()
        outboxEvictsOldestFirst()
        shortLinesStayWhole()
        longLinesSplitIntoTaggedPieces()
        escapedCountNeverUnderestimatesJSONSerialization()
        batchesStayUnderTheBudget()
        worstCaseBatchFitsTheFrameGate()
        timestampsCarryMilliseconds()
        previousRunTagIsIdempotent()
        backlogEscapingRoundTrips()
        testFlightDaysCountDown()
        machineIdentifierIsNotEmpty()
        runMarkerOnlyReportsAnInUseRun()
        metricKitKindPicksTheMostSeriousDiagnostic()
        metricKitSummaryNamesTheCrash()
        metricKitMetricSummaryListsExits()
        if failures != 0 { print("\(failures) diagnostics format regression(s)"); exit(1) }
        print("PASS: \(checks) diagnostics format checks")
    }

    // Break caught: a part boundary that loses or repeats a byte corrupts the joined report.
    static func partsCoverThePayloadExactly() {
        let data = Data((0..<14_500).map { UInt8($0 % 251) })
        let count = DiagnosticsFormat.partCount(byteCount: data.count)
        expect(count == 4, "14,500 bytes travel as 4 parts of at most 4,200")
        var joined = Data()
        for index in 0..<count {
            guard let part = DiagnosticsFormat.part(index, of: data), let raw = Data(base64Encoded: part) else {
                expect(false, "part \(index) decodes")
                continue
            }
            expect(raw.count <= DiagnosticsFormat.partBytes, "part \(index) holds at most 4,200 bytes")
            joined.append(raw)
        }
        expect(joined == data, "the parts join back byte for byte")
        expect(DiagnosticsFormat.part(4, of: data) == nil, "there is no part past the last")
        expect(DiagnosticsFormat.part(-1, of: data) == nil, "there is no negative part")
        expect(DiagnosticsFormat.partCount(byteCount: 0) == 1, "an empty payload is one part")
        expect(DiagnosticsFormat.part(0, of: Data()) == "", "the empty part is empty")
        expect(DiagnosticsFormat.partCount(byteCount: 4200) == 1, "exactly 4,200 bytes is one part")
        expect(DiagnosticsFormat.partCount(byteCount: 4201) == 2, "one byte more is two parts")
        expect(DiagnosticsFormat.maxPayloadBytes == 268_800, "64 parts carry 268,800 bytes")
        // A slice of a larger Data must use its own indices.
        let slice = data[100..<6200]
        expect(Data(base64Encoded: DiagnosticsFormat.part(1, of: slice) ?? "") == Data(data[4300..<6200]),
               "a Data slice is cut at its own offsets")
    }

    // Break caught: a part whose sealed frame passes 16,384 chars is dropped silently by the Redmi.
    static func aFullPartFitsTheFrameGate() {
        let data = Data(repeating: 0xFF, count: 12_000)
        let part = DiagnosticsFormat.part(0, of: data) ?? ""
        expect(part.count == 5600, "a full part is 5,600 base64 chars")
        let frame = json([
            "type": "client_diag", "id": UUID().uuidString, "kind": "metrickit-crash",
            "createdAt": DiagnosticsFormat.isoTimestamp(Date()), "part": 63, "parts": 64,
            "enc": "base64", "data": part
        ])
        // 0xFF bytes are all "/" in base64, which JSONSerialization escapes: the worst case.
        expect(sealedChars(frame) < frameGate, "a full part of slashes stays under the frame gate (\(sealedChars(frame)))")
        let typical = Data(Array("{\"crashDiagnostics\":[{\"callStackTree\":{\"frames\":[]}}]}".utf8) + Array(repeating: UInt8(0x41), count: 12_000))
        let typicalFrame = json([
            "type": "client_diag", "id": UUID().uuidString, "kind": "metrickit-crash",
            "createdAt": DiagnosticsFormat.isoTimestamp(Date()), "part": 0, "parts": 3,
            "enc": "base64", "data": DiagnosticsFormat.part(0, of: typical) ?? ""
        ])
        expect(sealedChars(typicalFrame) < 9_000, "a typical part seals to about 7.9 K chars (\(sealedChars(typicalFrame)))")
    }

    static func idsFollowTheRedmisRule() {
        expect(DiagnosticsFormat.isValidId(UUID().uuidString), "a UUID is a valid id")
        expect(DiagnosticsFormat.isValidId("abcDEF12"), "8 letters and digits are valid")
        expect(!DiagnosticsFormat.isValidId("abc1234"), "7 chars are too short")
        expect(!DiagnosticsFormat.isValidId(String(repeating: "a", count: 65)), "65 chars are too long")
        expect(!DiagnosticsFormat.isValidId("abc_12345"), "an underscore is not allowed")
        expect(!DiagnosticsFormat.isValidId("abçd12345"), "a non-ASCII letter is not allowed")
    }

    static func outboxNamesRoundTrip() {
        let id = UUID().uuidString
        let created = Date(timeIntervalSince1970: 1_793_100_000.123)
        let name = DiagnosticsFormat.outboxFileName(id: id, kind: "metrickit-crash", createdAt: created)
        let entry = DiagnosticsFormat.parseOutboxFileName(name)
        expect(entry?.id == id, "the id comes back from the file name")
        expect(entry?.kind == "metrickit-crash", "the kind comes back from the file name")
        expect(abs((entry?.createdAt.timeIntervalSince1970 ?? 0) - 1_793_100_000.123) < 0.002,
               "the creation time comes back to the millisecond")
        expect(DiagnosticsFormat.parseOutboxFileName("notes.txt") == nil, "a foreign file is ignored")
        expect(DiagnosticsFormat.parseOutboxFileName("12_\(id)_unknown-kind.json") == nil, "an unknown kind is ignored")
        expect(DiagnosticsFormat.parseOutboxFileName("x_\(id)_nsexception.json") == nil, "a bad time is ignored")
        expect(DiagnosticsFormat.parseOutboxFileName("12_short_nsexception.json") == nil, "a bad id is ignored")
    }

    static func outboxEvictsOldestFirst() {
        let base = Date(timeIntervalSince1970: 1_000)
        let files = (0..<25).map {
            DiagnosticsFormat.OutboxFile(name: "f\($0)", bytes: 10, createdAt: base.addingTimeInterval(Double($0)))
        }.shuffled()
        let byCount = DiagnosticsFormat.outboxEvictions(files)
        expect(byCount == ["f0", "f1", "f2", "f3", "f4"], "25 files keep the newest 20 (\(byCount))")
        let big = [
            DiagnosticsFormat.OutboxFile(name: "old", bytes: 1_500_000, createdAt: base),
            DiagnosticsFormat.OutboxFile(name: "mid", bytes: 600_000, createdAt: base.addingTimeInterval(1)),
            DiagnosticsFormat.OutboxFile(name: "new", bytes: 400_000, createdAt: base.addingTimeInterval(2))
        ]
        expect(DiagnosticsFormat.outboxEvictions(big) == ["old"], "over 2 MB the oldest goes first")
        expect(DiagnosticsFormat.outboxEvictions(Array(big.suffix(2))).isEmpty, "1 MB of two files stays")
        expect(DiagnosticsFormat.outboxEvictions([]).isEmpty, "an empty outbox evicts nothing")
    }

    static func shortLinesStayWhole() {
        let line = "Connected to the Redmi"
        expect(DiagnosticsFormat.splitLine(line) == [line], "a short line is not touched")
        let exact = String(repeating: "a", count: DiagnosticsFormat.maxLineChars)
        expect(DiagnosticsFormat.splitLine(exact) == [exact], "a line of exactly 3,500 chars stays whole")
    }

    // Break caught: a crash stack or a MetricKit summary logged as one line was cut at 4,000 chars on
    // the Redmi, or pushed a whole batch over the frame gate.
    static func longLinesSplitIntoTaggedPieces() {
        let long = String(repeating: "x", count: 8_000)
        let pieces = DiagnosticsFormat.splitLine(long)
        expect(pieces.count == 3, "8,000 chars become 3 pieces")
        expect(pieces.first?.hasPrefix("[1/3] ") == true, "the first piece is tagged [1/3]")
        expect(pieces.last?.hasPrefix("[3/3] ") == true, "the last piece is tagged [3/3]")
        expect(pieces.allSatisfy { $0.utf16.count <= DiagnosticsFormat.maxLineChars + 10 }, "no piece passes 3,510 chars")
        let rebuilt = pieces.map { String($0.drop(while: { $0 != " " }).dropFirst()) }.joined()
        expect(rebuilt == long, "the pieces rebuild the line")

        // Emoji are two UTF-16 units; a piece must never cut one in half.
        let emoji = String(repeating: "📞", count: 2_000)
        let emojiPieces = DiagnosticsFormat.splitLine(emoji)
        expect(emojiPieces.count == 2, "4,000 UTF-16 units of emoji become 2 pieces")
        expect(emojiPieces.allSatisfy { $0.utf16.count <= DiagnosticsFormat.maxLineChars + 10 }, "emoji pieces stay in budget")
        let emojiRebuilt = emojiPieces.map { String($0.drop(while: { $0 != " " }).dropFirst()) }.joined()
        expect(emojiRebuilt == emoji, "emoji survive the split")

        // Control characters are short in chars but six bytes each on the wire: 3,000 of them fit the
        // char limit and still have to be split by their escaped size.
        let controls = String(repeating: "\u{01}", count: 3_000)
        let controlPieces = DiagnosticsFormat.splitLine(controls)
        expect(controlPieces.count == 3, "3,000 control characters split by escaped size (\(controlPieces.count))")
        expect(controlPieces.allSatisfy { DiagnosticsFormat.escapedByteCount($0) <= DiagnosticsFormat.maxLineEscapedBytes + 10 },
               "escaped pieces stay in budget")
    }

    // Break caught: a budget built on a count lower than what JSONSerialization writes lets a batch
    // through that the Redmi then drops.
    static func escapedCountNeverUnderestimatesJSONSerialization() {
        let samples = [
            "plain ascii", "ç ğ ı ö ş ü İ", "/path/to/file", "quote \" and backslash \\",
            "new\nline\ttab\r", "\u{01}\u{1F}", "📞 emoji", "日本語", ""
        ]
        for sample in samples {
            let written = json(["s": sample])
            // {"s":"..."} is 8 bytes of envelope around the escaped string.
            let actual = written.count - 8
            expect(DiagnosticsFormat.escapedByteCount(sample) >= actual,
                   "the estimate for \(sample.debugDescription) covers the \(actual) bytes written")
        }
        expect(DiagnosticsFormat.escapedByteCount("abc") == 3, "ASCII costs one byte each")
        expect(DiagnosticsFormat.escapedByteCount("ç") == 2, "a Turkish letter costs two bytes")
        expect(DiagnosticsFormat.escapedByteCount("\"") == 2, "a quote costs two bytes")
    }

    static func batchesStayUnderTheBudget() {
        let short = Array(repeating: "[12:00:00.000] short line", count: 100)
        expect(DiagnosticsFormat.batchCount(short) == 40, "short lines are capped at 40 per batch")
        let long = Array(repeating: String(repeating: "y", count: 3_000), count: 10)
        let count = DiagnosticsFormat.batchCount(long)
        expect(count == 2, "3,000-char lines go two at a time (\(count))")
        let huge = [String(repeating: "z", count: 8_500)]
        expect(DiagnosticsFormat.batchCount(huge) == 1, "a single line always goes, even when large")
        expect(DiagnosticsFormat.batchCount([String]()) == 0, "nothing waiting, nothing sent")
        expect(DiagnosticsFormat.batchCount(short[95...]) == 5, "an array slice works from its start")
    }

    // Break caught: the first batch after a crash carried the whole stack and was dropped by the gate.
    static func worstCaseBatchFitsTheFrameGate() {
        let piece = "[12:34:56.789] [2/9] " + String(repeating: "ğ", count: DiagnosticsFormat.maxLineChars)
        let ring = Array(repeating: DiagnosticsFormat.taggedAsPrevious(piece), count: 40)
        let count = DiagnosticsFormat.batchCount(ring)
        let frame = json([
            "type": "client_log", "appVersion": "2.0", "appBuild": "999", "dropped": 123_456,
            "lines": Array(ring.prefix(count))
        ])
        expect(count >= 1, "a batch of long Turkish lines still sends one")
        expect(sealedChars(frame) < frameGate, "the worst case batch seals under the gate (\(sealedChars(frame)))")

        let controls = String(repeating: "\u{07}", count: DiagnosticsFormat.maxLineChars)
        let controlPieces = DiagnosticsFormat.splitLine(controls).map { "[12:34:56.789] " + $0 }
        let controlCount = DiagnosticsFormat.batchCount(controlPieces)
        let controlFrame = json(["type": "client_log", "lines": Array(controlPieces.prefix(controlCount))])
        expect(sealedChars(controlFrame) < frameGate, "a batch of control characters seals under the gate")
    }

    static func timestampsCarryMilliseconds() {
        // Half a second is exact in binary, so the formatter cannot round it either way.
        let stamp = DiagnosticsFormat.timestamp(Date(timeIntervalSince1970: 1_793_104_931.5))
        let pattern = "^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}$"
        expect(stamp.range(of: pattern, options: .regularExpression) != nil, "the stamp reads HH:mm:ss.SSS (\(stamp))")
        expect(stamp.hasSuffix(".500"), "the milliseconds are kept")
    }

    static func previousRunTagIsIdempotent() {
        let once = DiagnosticsFormat.taggedAsPrevious("[10:00:00.000] Connected")
        expect(once == "[prev] [10:00:00.000] Connected", "a line from the previous run is tagged")
        expect(DiagnosticsFormat.taggedAsPrevious(once) == once, "a line carried over twice is tagged once")
    }

    static func backlogEscapingRoundTrips() {
        let samples = ["plain", "two\nlines", "back\\slash\\n literal", "cr\r\nlf", "trailing\\", "", "ç📞"]
        for sample in samples {
            let escaped = DiagnosticsFormat.escapeBacklogLine(sample)
            expect(!escaped.contains("\n") && !escaped.contains("\r"), "an escaped line has no line break")
            expect(DiagnosticsFormat.unescapeBacklogLine(escaped) == sample, "\(sample.debugDescription) round-trips")
        }
    }

    static func testFlightDaysCountDown() {
        guard let built = DiagnosticsFormat.parseBuildDate("2026-10-28T22:17:33Z") else {
            expect(false, "the CI build date parses")
            return
        }
        expect(DiagnosticsFormat.parseBuildDate(" 2026-10-28T22:17:33Z\n") == built, "surrounding space is ignored")
        expect(DiagnosticsFormat.parseBuildDate("28.10.2026") == nil, "another format is unknown, not a guess")
        expect(DiagnosticsFormat.testFlightDaysLeft(buildDate: built, now: built) == 90, "a fresh build has 90 days")
        expect(DiagnosticsFormat.testFlightDaysLeft(buildDate: built, now: built.addingTimeInterval(86_399)) == 90,
               "a day is only counted once it is over")
        expect(DiagnosticsFormat.testFlightDaysLeft(buildDate: built, now: built.addingTimeInterval(2 * 86_400)) == 88,
               "two days later 88 are left")
        expect(DiagnosticsFormat.testFlightDaysLeft(buildDate: built, now: built.addingTimeInterval(-3_600)) == 90,
               "a clock behind the build counts as day zero")
        expect(DiagnosticsFormat.testFlightDaysLeft(buildDate: built, now: built.addingTimeInterval(91 * 86_400)) == -1,
               "an expired build goes below zero")
    }

    static func machineIdentifierIsNotEmpty() {
        let model = DiagnosticsFormat.machineIdentifier()
        expect(!model.isEmpty && !model.contains("\u{0}"), "the machine identifier is a clean string (\(model))")
    }

    static func runMarkerOnlyReportsAnInUseRun() {
        expect(DiagnosticsFormat.runMarkerState(inForeground: true, calls: 0, audioRunning: false) == "running",
               "on screen is in use")
        expect(DiagnosticsFormat.runMarkerState(inForeground: false, calls: 1, audioRunning: false) == "running",
               "a call in the background is in use")
        expect(DiagnosticsFormat.runMarkerState(inForeground: false, calls: 0, audioRunning: true) == "running",
               "call audio in the background is in use")
        expect(DiagnosticsFormat.runMarkerState(inForeground: false, calls: 0, audioRunning: false) == "idle",
               "an idle background app is not")
        expect(DiagnosticsFormat.previousRunEndedUncleanly(markerState: "running", crashRecorded: false),
               "a run that ended in use without an exception is reported")
        expect(!DiagnosticsFormat.previousRunEndedUncleanly(markerState: "running", crashRecorded: true),
               "an exception already travels as its own report")
        expect(!DiagnosticsFormat.previousRunEndedUncleanly(markerState: "idle", crashRecorded: false),
               "iOS ending an idle app is normal")
        expect(!DiagnosticsFormat.previousRunEndedUncleanly(markerState: "terminated", crashRecorded: false),
               "a clean termination is normal")
        expect(!DiagnosticsFormat.previousRunEndedUncleanly(markerState: nil, crashRecorded: false),
               "a first launch has nothing to report")
    }

    static let crashPayload = """
    {"timeStampBegin":"2026-10-28 20:00:00","timeStampEnd":"2026-10-28 20:00:00",
     "hangDiagnostics":[{"diagnosticMetaData":{"hangDuration":"2.1 sec"}}],
     "crashDiagnostics":[{"version":"1.0.0",
       "diagnosticMetaData":{"appBuildVersion":"155","osVersion":"iPhone OS 27.0 (24A100)",
         "exceptionType":1,"exceptionCode":0,"signal":11,"terminationReason":"Namespace SIGNAL, Code 11"},
       "callStackTree":{"callStackPerThread":true,"callStacks":[
         {"threadAttributed":false,"callStackRootFrames":[{"binaryName":"libsystem_kernel.dylib","offsetIntoBinaryTextSegment":16}]},
         {"threadAttributed":true,"callStackRootFrames":[{"binaryName":"AVFAudio","offsetIntoBinaryTextSegment":4096,
           "subFrames":[{"binaryName":"RedmiGateway","offsetIntoBinaryTextSegment":255,
             "subFrames":[{"binaryName":"UIKitCore","offsetIntoBinaryTextSegment":1}]}]}]}]}}]}
    """

    static func metricKitKindPicksTheMostSeriousDiagnostic() {
        expect(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data(crashPayload.utf8)) == "metrickit-crash",
               "a payload with a crash is a crash report")
        let hang = #"{"hangDiagnostics":[{}],"cpuExceptionDiagnostics":[{}]}"#
        expect(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data(hang.utf8)) == "metrickit-hang", "a hang beats CPU")
        let cpu = #"{"cpuExceptionDiagnostics":[{}],"diskWriteExceptionDiagnostics":[{}]}"#
        expect(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data(cpu.utf8)) == "metrickit-cpu", "CPU beats disk")
        let disk = #"{"diskWriteExceptionDiagnostics":[{}]}"#
        expect(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data(disk.utf8)) == "metrickit-disk", "disk alone is disk")
        let launch = #"{"appLaunchDiagnostics":[{}]}"#
        expect(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data(launch.utf8)) == "metrickit-hang", "a slow launch is filed with hangs")
        expect(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data("not json".utf8)) == "metrickit-crash", "unreadable goes with crashes")
        expect(DiagnosticsFormat.kinds.contains(DiagnosticsFormat.metricKitKind(diagnosticJSON: Data("{}".utf8))),
               "every kind chosen is one the Redmi knows")
    }

    static func metricKitSummaryNamesTheCrash() {
        let summary = DiagnosticsFormat.metricKitDiagnosticSummary(Data(crashPayload.utf8))
        expect(summary.contains("crash=1") && summary.contains("hang=1"), "the summary counts each kind (\(summary))")
        expect(summary.contains("signal=11"), "the summary names the signal")
        expect(summary.contains("build=155"), "the summary names the build")
        expect(summary.contains("frames=AVFAudio+0x1000 < RedmiGateway+0xff < UIKitCore+0x1"),
               "the frames come from the attributed thread, top first")
        expect(!summary.contains("\n"), "the summary is one line")
        expect(DiagnosticsFormat.metricKitDiagnosticSummary(Data("[]".utf8)).hasPrefix("unreadable"), "a non-object is unreadable")
    }

    static func metricKitMetricSummaryListsExits() {
        let payload = """
        {"timeStampBegin":"2026-10-27 00:00:00","metaData":{"appBuildVersion":"155"},
         "applicationExitMetrics":{
           "foregroundExitData":{"cumulativeNormalAppExitCount":3,"cumulativeAppWatchdogExitCount":0},
           "backgroundExitData":{"cumulativeMemoryResourceLimitExitCount":2,"cumulativeSuspendedWithLockedFileExitCount":0}}}
        """
        let summary = DiagnosticsFormat.metricKitMetricSummary(Data(payload.utf8))
        expect(summary.contains("fg[NormalApp=3]"), "foreground exits are listed (\(summary))")
        expect(summary.contains("bg[MemoryResourceLimit=2]"), "background exits are listed")
        expect(!summary.contains("AppWatchdog"), "zero counts are left out")
        expect(summary.contains("build=155"), "the build is named")
        expect(DiagnosticsFormat.shortExitName("cumulativeAppWatchdogExitCount") == "AppWatchdog", "exit names are shortened")
    }
}
