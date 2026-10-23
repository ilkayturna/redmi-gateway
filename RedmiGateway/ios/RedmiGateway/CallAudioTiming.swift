import Foundation

/// Bounded PCM timing, independent of AVFoundation and the wall clock.
/// AudioEngine protects capture with a lock; playback belongs to its network queue.
struct CallAudioPacer<Frame> {
    private struct Capture {
        let frame: Frame
        let capturedAt: TimeInterval
    }
    private let interval: TimeInterval = 0.02
    private let maximumAge: TimeInterval = 0.32
    private let capacity = 12
    /// How long the queue may stay empty before the send reserve is rebuilt.
    ///
    /// The tap produces a frame every ~21 ms and the timer takes one every 20 ms, so a healthy
    /// queue is empty for one to two frame periods all the time - and when the microphone runs
    /// slightly slow (the measured 46.7 frames/s) it is empty almost permanently. Rebuilding the
    /// three-frame reserve there held each following frame back for 60 ms and turned the small
    /// production shortfall into 60-80 ms holes (measured on the Redmi: burst=5, gap=338 ms), which
    /// drained its 20 ms buffer into concealment - the crackle. Only a real outage, seven frame
    /// periods without a single frame, rebuilds the reserve.
    private let starvation: TimeInterval = 0.15
    private var frames: [Capture] = []
    private var primed = false
    private var emptySince: TimeInterval?
    private var nextSendAt: TimeInterval?
    private(set) var generation: UInt64 = 0
    /// Diagnostics: frames the capture produced, and frames this queue threw away before sending.
    /// A sustained produced/s below 50 is the frame deficit no receiver buffer can hide, and the
    /// two drop counters say whether backpressure or expiry is eating the speech.
    private(set) var acceptedFrames = 0
    private(set) var droppedExpired = 0
    private(set) var droppedOverflow = 0

    mutating func reset() {
        frames.removeAll()
        primed = false
        emptySince = nil
        nextSendAt = nil
        generation &+= 1
        acceptedFrames = 0
        droppedExpired = 0
        droppedOverflow = 0
    }

    mutating func discardPending() {
        frames.removeAll()
        primed = false
        emptySince = nil
    }

    func nextWakeUp(now: TimeInterval) -> TimeInterval {
        // Empty queues or socket backpressure must not spin on a past deadline.
        if let nextSendAt, nextSendAt > now { return nextSendAt }
        return now + interval
    }

    mutating func enqueue(_ batch: [Frame], capturedAt: TimeInterval,
                          now: TimeInterval, generation: UInt64) {
        guard generation == self.generation else { return }
        discardExpired(now: now)
        for (index, frame) in batch.enumerated() {
            // capturedAt is the end of the batch, stamped before conversion or dispatch.
            let timestamp = capturedAt - Double(batch.count - index - 1) * interval
            if now - timestamp <= maximumAge {
                frames.append(Capture(frame: frame, capturedAt: timestamp))
                acceptedFrames += 1
            } else {
                droppedExpired += 1
            }
        }
        if frames.count > capacity {
            droppedOverflow += frames.count - capacity
            frames.removeFirst(frames.count - capacity)
        }
    }

    mutating func next(now: TimeInterval) -> Frame? {
        discardExpired(now: now)
        // A queue that is briefly empty is simply caught up with the microphone; only a queue that
        // stays empty for a whole timer period has starved, and only then is the reserve rebuilt.
        if primed, frames.isEmpty, emptySince == nil { emptySince = now }
        if primed, let since = emptySince, now - since > starvation {
            primed = false
            emptySince = nil
        }
        if !frames.isEmpty { emptySince = nil }
        if !primed {
            guard frames.count >= 3 else { return nil }
            primed = true
        }
        if let nextSendAt, now + 0.000000001 < nextSendAt { return nil }
        guard !frames.isEmpty else { return nil }
        let frame = frames.removeFirst().frame
        // Normal sub-2ms jitter keeps the producer's phase instead of accumulating
        // timer/handler cost. Larger stalls start a full interval from actual time,
        // so the next nominal deadline cannot cause a near-immediate catch-up packet.
        if let deadline = nextSendAt, now - deadline < 0.002 {
            nextSendAt = deadline + interval
        } else {
            nextSendAt = now + interval
        }
        // The queue is empty until the microphone's next frame: remember when, so only a real
        // starvation (see the header of this method) rebuilds the reserve.
        if frames.isEmpty { emptySince = now }
        return frame
    }

    private mutating func discardExpired(now: TimeInterval) {
        var dropped = false
        while let first = frames.first, now - first.capturedAt > maximumAge {
            frames.removeFirst()
            dropped = true
            droppedExpired += 1
        }
        // Speech that aged out means the stream really stopped; that reserve is rebuilt too.
        if dropped, frames.isEmpty {
            primed = false
            emptySince = nil
        }
    }
}

/// The capture tap takes a snapshot; an intervening mute toggle invalidates that PCM.
struct CallAudioCaptureMute {
    private(set) var isMuted = false
    private(set) var generation: UInt64 = 0
    private var lastTransitionAt: TimeInterval?

    mutating func setMuted(_ value: Bool, now: TimeInterval) -> Bool {
        guard isMuted != value else { return false }
        isMuted = value
        generation &+= 1
        lastTransitionAt = now
        return true
    }

    func filter(_ frames: [[Int16]], capturedAt: TimeInterval, capturedGeneration: UInt64,
                capturedWhileMuted: Bool) -> [[Int16]] {
        let silenceAll = isMuted || capturedWhileMuted || capturedGeneration != generation
        return frames.enumerated().map { index, frame in
            let startedAt = capturedAt - Double(frames.count - index) * 0.02
            // A delayed hardware callback can have a current, unmuted snapshot but
            // still contain samples captured before unmute. Mask those frames too.
            let crossesTransition = lastTransitionAt.map { startedAt + 0.000000001 < $0 } ?? false
            return silenceAll || crossesTransition ? Array(repeating: 0, count: frame.count) : frame
        }
    }
}

struct CallAudioPlaybackBuffer<Frame> {
    private var frames: [Frame] = []
    private var primed = false
    private var lastArrival: TimeInterval?
    private(set) var generation: UInt64 = 0
    private(set) var scheduledFrames = 0
    private(set) var droppedFrames = 0
    /// A downlink that runs dry is silent; the diagnostics log needs the count to prove whether the
    /// reserve was enough. This is the number of real underruns, not every empty player instant.
    private(set) var underruns = 0
    private(set) var maxArrivalGapMs = 0
    /// The measured hotspot delivered 160 ms gaps after a nominal 20 ms cadence. Three frames
    /// (60 ms) was less than half of one gap, so every stall reached the speaker as a hole. The
    /// startup reserve is one 160 ms gap; after the player really runs dry, four frames (80 ms)
    /// are enough to resume without turning the recovery into a second long pause.
    private let initialReserveFrames = 8
    private let reprimeReserveFrames = 4
    /// 12 * 20 ms = 240 ms, the same bounded latency budget as the sender.
    private let maximumScheduledFrames = 12
    /// Three frame periods without a scheduled buffer means the player, not merely the network,
    /// has run dry. A single late frame is normal and must not force a re-prime.
    private let starvation: TimeInterval = 0.06
    private var reserveFrames = 8

    mutating func reset() {
        frames.removeAll()
        primed = false
        lastArrival = nil
        scheduledFrames = 0
        droppedFrames = 0
        underruns = 0
        maxArrivalGapMs = 0
        reserveFrames = initialReserveFrames
        generation &+= 1
    }

    mutating func enqueue(_ frame: Frame, now: TimeInterval) -> [Frame] {
        if let lastArrival {
            let gap = max(0, now - lastArrival)
            maxArrivalGapMs = max(maxArrivalGapMs, Int((gap * 1000).rounded()))
            if gap > 0.5 {
                // A real outage: stale audio is worthless, and the stream starts with the full
                // reserve again.
                frames.removeAll()
                primed = false
                reserveFrames = initialReserveFrames
            } else if primed, scheduledFrames == 0, gap > starvation {
                // The player had nothing left for three frame periods. Resume after a short
                // reserve rather than scheduling one frame into a dry device and repeating holes.
                underruns += 1
                primed = false
                reserveFrames = reprimeReserveFrames
            }
        }
        lastArrival = now
        frames.append(frame)
        if !primed && frames.count >= reserveFrames { primed = true }
        guard primed else { return [] }
        var ready: [Frame] = []
        while !frames.isEmpty {
            let next = frames.removeFirst()
            if scheduledFrames < maximumScheduledFrames {
                scheduledFrames += 1
                ready.append(next)
            } else {
                droppedFrames += 1
            }
        }
        // The next outage gets the full startup reserve; the smaller one is only for this recovery.
        reserveFrames = initialReserveFrames
        return ready
    }

    mutating func completed(generation: UInt64) {
        guard generation == self.generation else { return }
        scheduledFrames = max(0, scheduledFrames - 1)
        // No re-priming here: a steady downlink finishes its last scheduled frame all the time,
        // and rebuilding the reserve at that moment delivered the audio in clumps with holes.
        // enqueue() re-primes only after a measured starvation (see `starvation`).
    }
}
