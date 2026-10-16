import Foundation

/// Rebuilds one sealed BLE state frame from the notifications that carried it.
///
/// An ATT notification never carries more than `MTU - 3` bytes, and the MTU stays at its 23 byte
/// default until something negotiates more, so the Redmi splits a ~110 byte sealed frame into
/// chunks. A chunk is `[seq][flags]` plus payload, at most 20 bytes; the last chunk of a frame
/// sets `flags & lastChunk`, and `seq` counts from zero inside each frame so a missing chunk shows
/// up as a gap.
///
/// The two shapes cannot be confused: a sealed frame is never shorter than 29 bytes (1 version
/// + 12 nonce + 16 tag), so anything of 20 bytes or less is a chunk and anything longer is a whole
/// frame. A Redmi that never fragments - an older build, or a link with a large MTU - keeps
/// working through the same path.
struct BLEFrameAssembler {
    static let headerBytes = 2
    static let maxChunkBytes = 20
    static let lastChunkFlag: UInt8 = 0x01
    /// A half-finished frame is dropped after this, so nothing is held indefinitely.
    static let timeout: TimeInterval = 1

    private var buffer = Data()
    private var expectedSeq: UInt8 = 0
    private var startedAt: Date?

    /// Feeds one notification; returns the whole sealed frame once it is complete.
    mutating func push(_ data: Data, now: Date = Date()) -> Data? {
        if let started = startedAt, now.timeIntervalSince(started) > Self.timeout { reset() }
        // A whole frame is longer than any chunk can be.
        if data.count > Self.maxChunkBytes {
            reset()
            return data
        }
        // Two bytes are a valid chunk header with no payload, which is how a frame whose length
        // divides evenly into chunks is terminated; anything shorter is noise.
        guard data.count >= Self.headerBytes else {
            reset()
            return nil
        }
        let seq = data[data.startIndex]
        let flags = data[data.startIndex + 1]
        let payload = data[(data.startIndex + Self.headerBytes)...]
        if startedAt != nil, seq != expectedSeq {
            // A chunk went missing, so this frame cannot be trusted. The chunk that broke it may
            // itself be the first of the next frame, which is the only thing accepted below.
            reset()
        }
        guard startedAt != nil || seq == 0 else {
            reset()
            return nil
        }
        if startedAt == nil { startedAt = now }
        expectedSeq = seq &+ 1
        buffer.append(payload)
        guard flags & Self.lastChunkFlag != 0 else { return nil }
        // A terminator with nothing before it is malformed: no empty frame is ever handed on.
        guard !buffer.isEmpty else {
            reset()
            return nil
        }
        let frame = buffer
        reset()
        return frame
    }

    mutating func reset() {
        buffer.removeAll()
        expectedSeq = 0
        startedAt = nil
    }

    /// Splits a sealed frame the way the Redmi does; used by tests and kept beside the parser so
    /// both sides of the wire format stay in one file. The payload never exceeds what a receiver
    /// can tell apart from a whole frame, whatever the link's room is.
    static func chunks(of frame: Data,
                       payload: Int = BLEFrameAssembler.maxChunkBytes - BLEFrameAssembler.headerBytes) -> [Data] {
        precondition(payload > 0, "a chunk must carry at least one byte")
        var out: [Data] = []
        var index = frame.startIndex
        var seq: UInt8 = 0
        repeat {
            let end = min(index + payload, frame.endIndex)
            let body = frame[index..<end]
            let isLast = end == frame.endIndex
            var chunk = Data([seq, isLast ? BLEFrameAssembler.lastChunkFlag : 0])
            chunk.append(contentsOf: body)
            out.append(chunk)
            index = end
            seq = seq &+ 1
        } while index < frame.endIndex
        return out
    }
}
