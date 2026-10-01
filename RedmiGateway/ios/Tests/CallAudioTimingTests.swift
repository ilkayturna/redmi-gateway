import Foundation

// A standalone executable: no device, AVFoundation mocks, call, or recorded audio.
// Compile this file together with the production CallAudioTiming.swift.
@main
struct CallAudioTimingTests {
    static var failures = 0

    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        if !condition() { failures += 1; print("FAIL: \(message)") }
    }

    static func main() {
        captureBatchesArePaced()
        schedulerStallDoesNotCatchUp()
        microphoneReprimesAfterStarvation()
        staleAndOverflowingCaptureIsDiscarded()
        staleCaptureCannotEnterRestartedStream()
        playbackReprimesAfterShortGap()
        oldPlaybackCompletionCannotDrainNewStream()
        playbackCapacityStaysBounded()
        if failures != 0 { print("\(failures) audio timing regression(s)"); exit(1) }
        print("PASS: 8 call audio timing regressions")
    }

    // Break caught: emitting every frame from one tap callback at the same instant.
    static func captureBatchesArePaced() {
        for batchSize in [1, 5, 10] { // 20 / 100 / 200 ms microphone callbacks.
            var pacer = CallAudioPacer<Int>()
            var sent: [(Int, Double)] = []
            var sequence = 0
            for tick in 1...120 {
                let now = Double(tick) * 0.02
                if tick % batchSize == 0 {
                    let frames = Array(sequence..<(sequence + batchSize))
                    sequence += batchSize
                    pacer.enqueue(frames, capturedAt: now, now: now, generation: pacer.generation)
                }
                // Multiple callbacks at one deadline must not drain the whole batch.
                for _ in 0..<12 {
                    if let frame = pacer.next(now: now) { sent.append((frame, now)) }
                }
            }
            expect(sent.count >= 108, "\(batchSize)-frame capture maintains speech throughput")
            expect(sent.enumerated().allSatisfy { $0.offset == $0.element.0 },
                   "\(batchSize)-frame capture preserves PCM order without drops")
            expect(zip(sent, sent.dropFirst()).allSatisfy { $0.1.1 - $0.0.1 >= 0.019999 },
                   "\(batchSize)-frame capture emits at most one packet per 20 ms")
        }
    }

    // Break caught: advancing an old deadline repeatedly after a delayed timer.
    static func schedulerStallDoesNotCatchUp() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue(Array(0..<10), capturedAt: 0.2, now: 0.2, generation: pacer.generation)
        expect(pacer.next(now: 0.2) == 0, "first paced frame")
        expect(pacer.next(now: 0.28) == 1, "delayed scheduler emits one fresh frame")
        expect(pacer.next(now: 0.28) == nil, "delayed scheduler does not catch up in a burst")
        expect(pacer.next(now: 0.299) == nil, "next frame waits a complete interval")
        expect(pacer.next(now: 0.30) == 2, "next frame resumes at real time")
    }

    // Break caught: restarting speech with a single frame after an empty queue.
    static func microphoneReprimesAfterStarvation() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue([1, 2, 3], capturedAt: 0.04, now: 0.04, generation: pacer.generation)
        _ = pacer.next(now: 0.04); _ = pacer.next(now: 0.06); _ = pacer.next(now: 0.08)
        expect(pacer.next(now: 0.10) == nil, "empty microphone queue has no fabricated PCM")
        pacer.enqueue([4], capturedAt: 0.12, now: 0.12, generation: pacer.generation)
        expect(pacer.next(now: 0.12) == nil, "mic waits for reserve after starvation")
        pacer.enqueue([5], capturedAt: 0.14, now: 0.14, generation: pacer.generation)
        expect(pacer.next(now: 0.14) == nil, "two frames do not prematurely resume mic")
        pacer.enqueue([6], capturedAt: 0.16, now: 0.16, generation: pacer.generation)
        expect(pacer.next(now: 0.16) == 4, "mic resumes oldest fresh speech with reserve")
    }

    // Break caught: unbounded capture backlog and sending obsolete speech after a stall.
    static func staleAndOverflowingCaptureIsDiscarded() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue(Array(1...20), capturedAt: 0.4, now: 0.4, generation: pacer.generation)
        expect(pacer.next(now: 0.4) == 9, "overflow keeps the latest twelve PCM frames")
        expect(pacer.next(now: 1.0) == nil, "expired speech cannot leave the sender")
        pacer.enqueue([21, 22, 23], capturedAt: 0.5, now: 1.0, generation: pacer.generation)
        expect(pacer.next(now: 1.0) == nil, "delayed capture work cannot revive stale speech")
    }

    // Break caught: an old input tap closure appending PCM after stop/start.
    static func staleCaptureCannotEnterRestartedStream() {
        var pacer = CallAudioPacer<Int>()
        let old = pacer.generation
        pacer.enqueue([1, 2, 3], capturedAt: 0.1, now: 0.1, generation: old)
        pacer.reset()
        pacer.enqueue([4, 5, 6], capturedAt: 0.2, now: 0.2, generation: old)
        expect(pacer.next(now: 0.2) == nil, "restart clears queued and late old capture")
        pacer.enqueue([7, 8, 9], capturedAt: 0.3, now: 0.3, generation: pacer.generation)
        expect(pacer.next(now: 0.3) == 7, "new stream accepts its own capture")
    }

    // Break caught: original playbackPrimed stays true after a <500 ms drain.
    static func playbackReprimesAfterShortGap() {
        var playback = CallAudioPlaybackBuffer<Int>()
        expect(playback.enqueue(1, now: 0).isEmpty, "first downlink frame waits")
        expect(playback.enqueue(2, now: 0.02).isEmpty, "second downlink frame waits")
        expect(playback.enqueue(3, now: 0.04) == [1, 2, 3], "downlink starts with reserve")
        let generation = playback.generation
        for _ in 0..<3 { playback.completed(generation: generation) }
        expect(playback.enqueue(4, now: 0.2).isEmpty, "200 ms outage restores downlink reserve")
        expect(playback.enqueue(5, now: 0.22).isEmpty, "downlink continues re-priming")
        expect(playback.enqueue(6, now: 0.24) == [4, 5, 6], "downlink resumes in order")
    }

    // Break caught: an AVAudioPlayerNode completion from before stop mutating a new stream.
    static func oldPlaybackCompletionCannotDrainNewStream() {
        var playback = CallAudioPlaybackBuffer<Int>()
        let old = playback.generation
        _ = playback.enqueue(1, now: 0); _ = playback.enqueue(2, now: 0.02)
        _ = playback.enqueue(3, now: 0.04)
        playback.reset()
        _ = playback.enqueue(4, now: 0.1); _ = playback.enqueue(5, now: 0.12)
        expect(playback.enqueue(6, now: 0.14) == [4, 5, 6], "new playback starts")
        for _ in 0..<3 { playback.completed(generation: old) }
        expect(playback.scheduledFrames == 3, "old completions leave new playback untouched")
        expect(playback.enqueue(7, now: 0.16) == [7], "old completions cannot force false rebuffer")
    }

    // Break caught: scheduling an unbounded amount of stale playback into AVAudioPlayerNode.
    static func playbackCapacityStaysBounded() {
        var playback = CallAudioPlaybackBuffer<Int>()
        var scheduled: [Int] = []
        for frame in 1...20 { scheduled += playback.enqueue(frame, now: 0) }
        expect(scheduled == Array(1...12), "scheduled downlink audio has a finite capacity")
        expect(playback.droppedFrames == 8, "downlink overflow is observable in counters")
    }
}
