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
    /// How long the queue may stay empty before the send reserve is rebuilt. The tap produces a
    /// frame every ~21 ms and the timer takes one every 20 ms, so a healthy queue empties all the
    /// time; treating that as starvation held every following frame back for 60 ms and then sent a
    /// clump (measured on the Redmi: burst=5, gap=338 ms, 46.7 frames/s, which drained its 20 ms
    /// buffer into concealment - the crackle). A real stall still rebuilds the reserve.
    private let starvation: TimeInterval = 0.03
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
        nextSendAt = nil
        generation &+= 1
        acceptedFrames = 0
        droppedExpired = 0
        droppedOverflow = 0
    }

    mutating func discardPending() {
        frames.removeAll()
        primed = false
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
        // The reserve belongs to the start of a stream, not to every drain. The tap produces a
        // frame every ~21 ms and the timer takes one every 20 ms, so a healthy queue empties all
        // the time; re-arming the three-frame reserve there held each following frame back for
        // 60 ms and then sent a clump. Measured on the Redmi: burst=5, gap=338 ms, 46.7 frames/s,
        // which drained its 20 ms buffer into concealment - the crackle. Only reset() and
        // discardPending() un-prime now.
        return frame
    }

    private mutating func discardExpired(now: TimeInterval) {
        while let first = frames.first, now - first.capturedAt > maximumAge {
            frames.removeFirst()
            droppedExpired += 1
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

    mutating func reset() {
        frames.removeAll()
        primed = false
        lastArrival = nil
        scheduledFrames = 0
        droppedFrames = 0
        generation &+= 1
    }

    mutating func enqueue(_ frame: Frame, now: TimeInterval) -> [Frame] {
        if let lastArrival, now - lastArrival > 0.5 {
            frames.removeAll()
            primed = false
        }
        lastArrival = now
        frames.append(frame)
        if !primed && frames.count >= 3 { primed = true }
        guard primed else { return [] }
        var ready: [Frame] = []
        while !frames.isEmpty {
            let next = frames.removeFirst()
            if scheduledFrames < 12 {
                scheduledFrames += 1
                ready.append(next)
            } else {
                droppedFrames += 1
            }
        }
        return ready
    }

    mutating func completed(generation: UInt64) {
        guard generation == self.generation else { return }
        scheduledFrames = max(0, scheduledFrames - 1)
        // No re-priming here either: a steady downlink finishes its last scheduled frame all the
        // time, and rebuilding the three-frame reserve at that moment delivered the audio in
        // clumps with holes - the same defect as the sender's, heard on this side. The reserve is
        // rebuilt only by a real outage (half a second without frames, see enqueue) or reset().
    }
}
