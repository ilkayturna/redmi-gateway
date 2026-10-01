import Foundation

/// The call's PCM queues are owned by AudioEngine.networkQueue.
/// This extraction first preserves the original immediate-send and playback behavior,
/// so the timing regressions exercise the same helper used by the real audio graph.
struct CallAudioPacer<Frame> {
    private var frames: [Frame] = []
    private(set) var generation: UInt64 = 0

    mutating func reset() {
        frames.removeAll()
        generation &+= 1
    }

    mutating func enqueue(_ batch: [Frame], capturedAt: TimeInterval,
                          now: TimeInterval, generation: UInt64) {
        frames.append(contentsOf: batch)
    }

    mutating func next(now: TimeInterval) -> Frame? {
        frames.isEmpty ? nil : frames.removeFirst()
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
        scheduledFrames = max(0, scheduledFrames - 1)
    }
}
