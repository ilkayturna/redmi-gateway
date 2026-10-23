import Foundation

/// Bounded PCM timing, independent of AVFoundation and the wall clock.
/// AudioEngine protects capture with a lock; playback belongs to its network queue.
struct CallAudioPacer<Frame> {
    private struct Capture {
        let frame: Frame
        let capturedAt: TimeInterval
    }
    private let interval: TimeInterval = 0.02
    /// 24 frames = 480 ms. The measured uplink stalled for 263 ms while the send completions were
    /// blocked; the old twelve-frame queue overflowed by 5-20 frames per call and that speech was
    /// lost before it could ever leave the phone.
    private let maximumAge: TimeInterval = 0.48
    private let capacity = 24
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
    /// Above this backlog (160 ms) a late sender catches up; a smaller queue is just the working
    /// reserve and must not turn into a burst. The old code restarted a full interval after every
    /// late wake instead, which sent 48 frames/s while the microphone produced 50/s: the surplus
    /// became the measured overflow=20.
    private let catchUpDepth = 8
    /// The next catch-up packet waits this long. Long enough that one timer wake still sends at
    /// most one packet, short enough to drain a measured 300 ms backlog before it goes stale.
    private let catchUpInterval: TimeInterval = 0.005
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
        // An empty or unprimed queue waits for microphone data; a due deadline wakes the timer
        // immediately so a backlog can catch up. The 0.5 ms floor prevents a tight spin when the
        // socket is still backpressured and `next` cannot emit anything.
        guard primed, !frames.isEmpty else { return now + interval }
        if let nextSendAt, nextSendAt > now { return nextSendAt }
        return now + 0.0005
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
        // Normal sub-2ms jitter keeps the producer's phase instead of accumulating timer/handler
        // cost. A lateness beyond that used to restart a full interval from actual time, which
        // sent one frame less than the microphone produced on every late tick: 48/s versus 50/s,
        // and the surplus slowly overflowed the queue. A real backlog now drains at catch-up
        // pace; a late wake with only the working reserve advances to the next future slot and
        // keeps the phase, so it never emits a near-immediate catch-up packet.
        if let deadline = nextSendAt {
            if now - deadline < 0.002 {
                nextSendAt = deadline + interval
            } else if frames.count >= catchUpDepth {
                nextSendAt = now + catchUpInterval
            } else {
                var next = deadline + interval
                while next <= now { next += interval }
                nextSendAt = next
            }
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

/// Whether the copy inside a redundant downlink packet is a frame the receiver never played.
///
/// A type 2 packet carries [previous frame][current frame]. The copy is new exactly when the
/// packet before this one carried a frame that never arrived, which is a counter gap above one.
/// The first PCM packet is always type 1, so a last counter of zero still follows the same rule.
enum RedundantFramePolicy {
    static func copyIsNew(counter: UInt32, lastPcmCounter: UInt32) -> Bool {
        counter > lastPcmCounter &+ 1
    }
}

struct CallAudioPlaybackBuffer<Frame> {
    private var frames: [Frame] = []
    private var primed = false
    private var lastArrival: TimeInterval?
    private(set) var generation: UInt64 = 0
    /// Frames handed to AVAudioPlayerNode but not played back yet. The player is kept at
    /// `targetFrames` ahead of real time instead of being fed once per arrival.
    private(set) var scheduledFrames = 0
    private(set) var droppedFrames = 0
    /// A downlink that runs dry is silent; the diagnostics log needs the count to prove whether the
    /// reserve was enough. This is the number of real underruns, not every empty player instant.
    private(set) var underruns = 0
    private(set) var maxArrivalGapMs = 0
    /// The measured downlink delivered 243-298 ms arrival gaps against a nominal 20 ms cadence, and
    /// the player drained before the next burst arrived. Sixteen frames (320 ms) were only just
    /// enough and the reserve ratcheted down at every gap until the player underran every few
    /// seconds. Twenty frames (400 ms) leave room for the next gap, and the player is kept this
    /// far ahead for the whole call: scheduling every arriving frame immediately let the reserve
    /// drain at each gap and produced 2-10 underruns per call.
    private let initialReserveFrames = 20
    /// 28 * 20 ms = 560 ms. The larger bound is required for the deeper re-prime after a real
    /// outage and for the burst measured on the Redmi uplink; a 240 ms ceiling dropped the tail
    /// of every such burst.
    private let maximumScheduledFrames = 28
    /// Three frame periods without a scheduled buffer means the player, not merely the network,
    /// has run dry. A single late frame is normal and must not force a re-prime.
    private let starvation: TimeInterval = 0.06
    private var targetFrames = 20

    mutating func reset() {
        frames.removeAll()
        primed = false
        lastArrival = nil
        scheduledFrames = 0
        droppedFrames = 0
        underruns = 0
        maxArrivalGapMs = 0
        targetFrames = initialReserveFrames
        generation &+= 1
    }

    mutating func enqueue(_ frame: Frame, now: TimeInterval) -> [Frame] {
        if let lastArrival {
            let gap = max(0, now - lastArrival)
            maxArrivalGapMs = max(maxArrivalGapMs, Int((gap * 1000).rounded()))
            if gap > 0.5 {
                // A real outage: stale audio is worthless, but the gap it exposed must not be
                // forgotten - the stream resumes with enough reserve to cover it again.
                frames.removeAll()
                primed = false
                targetFrames = reserveFrames(forGap: gap)
            } else if primed, scheduledFrames == 0, gap > starvation {
                // The player had nothing left for three frame periods. Resume after the measured
                // reserve rather than scheduling one frame into a dry device and repeating holes.
                underruns += 1
                primed = false
                targetFrames = reserveFrames(forGap: gap)
            }
        }
        lastArrival = now
        frames.append(frame)
        if primed {
            // Keep only the newest speech when a burst overruns the allowance above the reserve.
            let maximumQueued = max(1, maximumScheduledFrames - targetFrames)
            if frames.count > maximumQueued {
                droppedFrames += frames.count - maximumQueued
                frames.removeFirst(frames.count - maximumQueued)
            }
        } else if frames.count > targetFrames {
            // Waiting for the reserve: the oldest frames would play after a needlessly long delay.
            droppedFrames += frames.count - targetFrames
            frames.removeFirst(frames.count - targetFrames)
        }
        if !primed, frames.count + scheduledFrames >= targetFrames { primed = true }
        return takeReady()
    }

    /// One player buffer finished. Refill the reserve from the queue and hand the next frame back
    /// to the engine; this is what keeps the player `targetFrames` ahead instead of draining dry.
    @discardableResult
    mutating func completed(generation: UInt64) -> Frame? {
        guard generation == self.generation else { return nil }
        scheduledFrames = max(0, scheduledFrames - 1)
        return takeReady().first
    }

    private mutating func takeReady() -> [Frame] {
        guard primed else { return [] }
        var ready: [Frame] = []
        while scheduledFrames < targetFrames, !frames.isEmpty {
            ready.append(frames.removeFirst())
            scheduledFrames += 1
        }
        return ready
    }

    private func reserveFrames(forGap gap: TimeInterval) -> Int {
        let need = Int((gap / 0.02).rounded(.up)) + 2
        return min(maximumScheduledFrames, max(initialReserveFrames, need))
    }
}
