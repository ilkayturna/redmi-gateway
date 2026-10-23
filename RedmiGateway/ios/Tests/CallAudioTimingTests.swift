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
        transientEmptyQueueKeepsPacing()
        pacedSpeechHasNoSendGaps()
        reloadedReserveCannotBypassPacing()
        timerCadenceSurvivesHandlerCostAndWakeJitter()
        midIntervalStallCannotEmitCloseCatchUp()
        discardedReserveCannotReplaySpeech()
        mutedCaptureCannotReplayAfterUnmute()
        delayedCallbackCannotReplayMutedInterval()
        staleAndOverflowingCaptureIsDiscarded()
        staleCaptureCannotEnterRestartedStream()
        playbackStartsWithJitterReserveAndReprimesAfterAGap()
        oldPlaybackCompletionCannotDrainNewStream()
        playbackCapacityStaysBounded()
        if failures != 0 { print("\(failures) audio timing regression(s)"); exit(1) }
        print("PASS: 16 call audio timing regressions")
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

    // Break caught: restarting speech with a single frame after an empty queue. The queue has to be
    // empty for a real outage (seven frame periods), not merely between two microphone callbacks -
    // see transientEmptyQueueKeepsPacing for the healthy case that must not rebuild.
    static func microphoneReprimesAfterStarvation() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue([1, 2, 3], capturedAt: 0.04, now: 0.04, generation: pacer.generation)
        _ = pacer.next(now: 0.04); _ = pacer.next(now: 0.06); _ = pacer.next(now: 0.08)
        expect(pacer.next(now: 0.10) == nil, "empty microphone queue has no fabricated PCM")
        pacer.enqueue([4], capturedAt: 0.30, now: 0.30, generation: pacer.generation)
        expect(pacer.next(now: 0.30) == nil, "mic waits for reserve after starvation")
        pacer.enqueue([5], capturedAt: 0.32, now: 0.32, generation: pacer.generation)
        expect(pacer.next(now: 0.32) == nil, "two frames do not prematurely resume mic")
        pacer.enqueue([6], capturedAt: 0.34, now: 0.34, generation: pacer.generation)
        expect(pacer.next(now: 0.34) == 4, "mic resumes oldest fresh speech with reserve")
    }

    // Break caught (the 08:33 crackle): treating every empty queue as starvation. The tap produces
    // a frame every ~21 ms and the timer takes one every 20 ms, so a healthy queue is empty for a
    // moment all the time; rebuilding the three-frame reserve there sent clumps of frames with
    // 60-100 ms holes (measured on the Redmi: burst=5, gap=338 ms, 46.7 frames/s), which drained
    // its 20 ms buffer into concealment. Only an empty queue older than one timer period starves.
    static func transientEmptyQueueKeepsPacing() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue([1, 2, 3], capturedAt: 0.04, now: 0.04, generation: pacer.generation)
        _ = pacer.next(now: 0.04); _ = pacer.next(now: 0.06); _ = pacer.next(now: 0.08)
        expect(pacer.next(now: 0.10) == nil, "empty microphone queue has no fabricated PCM")
        pacer.enqueue([4], capturedAt: 0.10, now: 0.10, generation: pacer.generation)
        expect(pacer.next(now: 0.10) == 4, "a queue empty for one period keeps sending")
        pacer.enqueue([5], capturedAt: 0.12, now: 0.12, generation: pacer.generation)
        expect(pacer.next(now: 0.12) == 5, "steady speech keeps flowing without a new reserve")
        pacer.reset()
        pacer.enqueue([6], capturedAt: 0.30, now: 0.30, generation: pacer.generation)
        expect(pacer.next(now: 0.30) == nil, "a restarted stream builds its three-frame reserve")
        pacer.enqueue([7], capturedAt: 0.32, now: 0.32, generation: pacer.generation)
        expect(pacer.next(now: 0.32) == nil, "two frames are not yet a reserve")
        pacer.enqueue([8], capturedAt: 0.34, now: 0.34, generation: pacer.generation)
        expect(pacer.next(now: 0.34) == 6, "the new stream resumes with its reserve in order")
    }

    // Break caught: the same re-priming at the cadence the Redmi actually measured. The tap
    // delivered one frame every ~21 ms (46.7 frames/s in bursts) and the consumer takes one every
    // 20 ms, so the queue drains for real; rebuilding the reserve on each drain then turned the
    // small production shortfall into 60 ms holes (measured: gap=338 ms at the worst). The sender
    // may only be as fast as its microphone, but it must never add a hole of its own.
    static func pacedSpeechHasNoSendGaps() {
        var pacer = CallAudioPacer<Int>()
        var sent: [(Int, Double)] = []
        var captured = 0
        var captureAt = 0.021
        var wake = 0.021
        while wake < 10 {
            while captureAt <= wake + 0.000000001 {
                pacer.enqueue([captured], capturedAt: captureAt, now: wake, generation: pacer.generation)
                captured += 1
                captureAt += 0.021
            }
            if let frame = pacer.next(now: wake) { sent.append((frame, wake)) }
            wake = pacer.nextWakeUp(now: wake)
        }
        expect(sent.count >= 470, "the sender keeps up with the measured 47.6 frames per second")
        expect(sent.map { $0.0 } == Array(0..<sent.count), "steady capture keeps PCM order")
        let gaps = zip(sent, sent.dropFirst()).map { $0.1.1 - $0.0.1 }
        expect(gaps.allSatisfy { $0 <= 0.045 }, "the sender adds no hole beyond the capture period")
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

    // Break caught: draining a reserve clears the last send deadline, allowing
    // another newly captured reserve to emit a packet at the same instant.
    static func reloadedReserveCannotBypassPacing() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue([1, 2, 3], capturedAt: 0.04, now: 0.04, generation: pacer.generation)
        _ = pacer.next(now: 0.04); _ = pacer.next(now: 0.06)
        expect(pacer.next(now: 0.08) == 3, "old reserve finishes")
        pacer.enqueue([4, 5, 6], capturedAt: 0.08, now: 0.08, generation: pacer.generation)
        expect(pacer.next(now: 0.08) == nil, "fresh reserve cannot burst after a drain")
        expect(pacer.next(now: 0.099) == nil, "fresh reserve retains the complete interval")
        expect(pacer.next(now: 0.1) == 4, "fresh reserve resumes at its due time")
    }

    // Break caught: rearming now+20ms adds every tick's wake jitter and handler
    // cost to the producer's20ms sample period, filling the queue and dropping speech.
    static func timerCadenceSurvivesHandlerCostAndWakeJitter() {
        var pacer = CallAudioPacer<Int>()
        var captured = 0
        var captureAt = 0.02
        var wake = 0.02
        var sent: [(Int, Double)] = []
        let wakeDelays = [0.0005, 0.001, 0.0001, 0.0008]
        while wake < 30 {
            while captureAt <= wake + 0.000000001 {
                pacer.enqueue([captured], capturedAt: captureAt, now: wake, generation: pacer.generation)
                captured += 1
                captureAt = Double(captured + 1) * 0.02
            }
            if let frame = pacer.next(now: wake) { sent.append((frame, wake)) }
            expect(pacer.next(now: wake) == nil, "jittered wake never emits a second packet")
            // 0.5ms encryption/handler work, then at most1ms timer wake delay.
            wake = pacer.nextWakeUp(now: wake + 0.0005) + wakeDelays[sent.count % wakeDelays.count]
        }
        expect(sent.count >= 1470, "30-second capture sustains at least49 PCM frames per second")
        expect(sent.enumerated().allSatisfy { $0.offset == $0.element.0 },
               "normal timer jitter never overflows or expires ordered speech")
        expect(zip(sent, sent.dropFirst()).allSatisfy { $0.1.1 - $0.0.1 >= 0.018 },
               "ordinary timer jitter preserves bounded packet separation")
    }

    // Break caught: preserving a nominal deadline after a19ms-late wake emits
    // the next packet1ms later, even though a full catch-up loop was removed.
    static func midIntervalStallCannotEmitCloseCatchUp() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue(Array(0..<10), capturedAt: 0.2, now: 0.2, generation: pacer.generation)
        _ = pacer.next(now: 0.2)
        expect(pacer.next(now: 0.239) == 1, "late wake emits one frame")
        expect(pacer.nextWakeUp(now: 0.2395) >= 0.258999, "late wake restores a full paced interval")
        expect(pacer.next(now: 0.24) == nil, "missed phase cannot cause1ms catch-up packet")
    }

    // Break caught: mute toggles retain the paced reserve and replay old speech.
    static func discardedReserveCannotReplaySpeech() {
        var pacer = CallAudioPacer<Int>()
        pacer.enqueue([1, 2, 3], capturedAt: 0.1, now: 0.1, generation: pacer.generation)
        _ = pacer.next(now: 0.1)
        let generation = pacer.generation
        pacer.discardPending()
        expect(pacer.next(now: 0.12) == nil, "discarded reserve contains no retained speech")
        expect(pacer.generation == generation, "mute discard keeps the live tap generation")
        pacer.enqueue([4, 5, 6], capturedAt: 0.14, now: 0.14, generation: generation)
        expect(pacer.next(now: 0.14) == 4, "mute discard accepts a fresh reserve")
    }

    // Break caught: captured muted speech is held as real PCM and escapes on unmute,
    // or a tap callback started before a mute toggle enqueues afterwards.
    static func mutedCaptureCannotReplayAfterUnmute() {
        var control = CallAudioCaptureMute()
        let speech: [[Int16]] = [[123, -456, 789]]
        let silence: [[Int16]] = [[0, 0, 0]]
        let old = control.generation
        _ = control.setMuted(true, now: 0.1)
        let muted = control.generation
        expect(control.filter(speech, capturedAt: 0.14, capturedGeneration: muted, capturedWhileMuted: true) == silence,
               "muted microphone PCM becomes silence before buffering")
        _ = control.setMuted(false, now: 0.2)
        expect(control.filter(speech, capturedAt: 0.14, capturedGeneration: muted, capturedWhileMuted: true) == silence,
               "unmute cannot replay captured private speech")
        expect(control.filter(speech, capturedAt: 0.1, capturedGeneration: old, capturedWhileMuted: false) == silence,
               "late tap crossing a mute transition cannot revive old speech")
        expect(control.filter(speech, capturedAt: 0.24, capturedGeneration: control.generation, capturedWhileMuted: false) == speech,
               "fresh unmuted capture retains real speech")
    }

    // Break caught: a200ms hardware callback delivered after unmute has the new
    // control snapshot but contains PCM that the device captured while muted.
    static func delayedCallbackCannotReplayMutedInterval() {
        var control = CallAudioCaptureMute()
        _ = control.setMuted(true, now: 0.1)
        _ = control.setMuted(false, now: 0.2)
        let delayed: [[Int16]] = Array(repeating: [123, -456], count: 10)
        expect(control.filter(delayed, capturedAt: 0.21, capturedGeneration: control.generation,
                              capturedWhileMuted: false) == Array(repeating: [0, 0], count: 10),
               "late200ms callback cannot replay any frame captured during mute")
        expect(control.filter([[1], [2], [3]], capturedAt: 0.25,
                              capturedGeneration: control.generation, capturedWhileMuted: false) == [[0], [2], [3]],
               "only capture safely after unmute may contain real speech")
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

    // Break caught: the measured hotspot delivered 160 ms gaps after a 20 ms cadence. A three-frame
    // (60 ms) reserve could not cover one, so every stall reached the speaker as a hole. Eight
    // frames (160 ms) cover the gap; after the player really runs dry, four frames resume it without
    // adding a second full-length pause.
    static func playbackStartsWithJitterReserveAndReprimesAfterAGap() {
        var playback = CallAudioPlaybackBuffer<Int>()
        for frame in 1...7 {
            expect(playback.enqueue(frame, now: Double(frame - 1) * 0.02).isEmpty,
                   "downlink keeps building the jitter reserve")
        }
        expect(playback.enqueue(8, now: 0.14) == Array(1...8),
               "downlink starts with the measured 160 ms reserve")
        let generation = playback.generation
        for _ in 0..<8 { playback.completed(generation: generation) }
        expect(playback.enqueue(9, now: 0.30).isEmpty,
               "a 160 ms gap re-primes instead of playing one frame into a dry player")
        expect(playback.enqueue(10, now: 0.32).isEmpty, "re-prime waits for four frames")
        expect(playback.enqueue(11, now: 0.34).isEmpty, "third re-prime frame")
        expect(playback.enqueue(12, now: 0.36) == [9, 10, 11, 12],
               "downlink resumes in order with a short reserve")
        expect(playback.underruns == 1, "the real underrun is visible in diagnostics")
        expect(playback.maxArrivalGapMs >= 160, "the arrival gap is visible in diagnostics")
        expect(playback.enqueue(13, now: 0.38) == [13], "steady downlink keeps flowing")
        for _ in 0..<5 { playback.completed(generation: generation) }
        expect(playback.enqueue(14, now: 0.90).isEmpty,
               "a half-second outage restores the startup reserve")
        for frame in 15...20 {
            expect(playback.enqueue(frame, now: 0.90 + Double(frame - 14) * 0.02).isEmpty,
                   "outage reserve rebuilds")
        }
        expect(playback.enqueue(21, now: 1.04) == Array(14...21),
               "post-outage downlink restarts with the full reserve")
        expect(playback.enqueue(22, now: 1.06) == [22],
               "downlink keeps flowing after the outage reserve")
    }

    // Break caught: an AVAudioPlayerNode completion from before stop mutating a new stream.
    static func oldPlaybackCompletionCannotDrainNewStream() {
        var playback = CallAudioPlaybackBuffer<Int>()
        let old = playback.generation
        for frame in 1...8 { _ = playback.enqueue(frame, now: Double(frame - 1) * 0.02) }
        playback.reset()
        for frame in 4...10 {
            expect(playback.enqueue(frame, now: 0.1 + Double(frame - 4) * 0.02).isEmpty,
                   "new playback builds its reserve")
        }
        expect(playback.enqueue(11, now: 0.24) == Array(4...11), "new playback starts")
        expect(playback.enqueue(12, now: 0.26) == [12], "new playback keeps flowing")
        for _ in 0..<8 { playback.completed(generation: old) }
        expect(playback.scheduledFrames == 9, "old completions leave new playback untouched")
        expect(playback.enqueue(13, now: 0.28) == [13],
               "old completions cannot force false rebuffer")
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
