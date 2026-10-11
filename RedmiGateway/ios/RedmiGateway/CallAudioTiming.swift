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
    private var frames: [Capture] = []
    private var primed = false
    private var nextSendAt: TimeInterval?
    private(set) var generation: UInt64 = 0

    mutating func reset() {
        frames.removeAll()
        primed = false
        nextSendAt = nil
        generation &+= 1
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
            }
        }
        if frames.count > capacity { frames.removeFirst(frames.count - capacity) }
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
        if frames.isEmpty {
            primed = false
        }
        return frame
    }

    private mutating func discardExpired(now: TimeInterval) {
        while let first = frames.first, now - first.capturedAt > maximumAge {
            frames.removeFirst()
        }
        if frames.isEmpty {
            primed = false
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
        // Reserve is restored after the device actually played the last queued frame.
        if scheduledFrames == 0 { primed = false }
    }
}
