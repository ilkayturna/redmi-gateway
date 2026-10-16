import Foundation

// A standalone executable: no device, CoreBluetooth, or captured audio.
// Compile this file together with the production BLEReassembly.swift.
@main
struct BLEReassemblyTests {
    static var failures = 0

    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        if !condition() { failures += 1; print("FAIL: \(message)") }
    }

    /// A sealed frame as the Redmi sends it: 1 version byte + 12 nonce + ciphertext + 16 tag.
    static func frame(payload: Int) -> Data {
        var data = Data([2])
        data.append(contentsOf: (0..<(12 + payload + 16)).map { UInt8($0 % 251) })
        return data
    }

    static func main() {
        reassemblesChunkedFrame()
        handlesFrameThatEndsOnTheLastChunk()
        passesAWholeFrameStraightThrough()
        dropsFramesWithAMissingChunk()
        acceptsANewFrameAfterAGap()
        discardsAHalfFrameAfterTheTimeout()
        ignoresNoiseAndOrphanChunks()
        resetClearsPartialFrames()
        if failures != 0 { print("\(failures) ble reassembly regression(s)"); exit(1) }
        print("PASS: 8 ble reassembly regressions")
    }

    // Break caught: a 110 byte state frame stays unreadable because every chunk is dropped.
    static func reassemblesChunkedFrame() {
        let frame = frame(payload: 81) // 110 bytes in total, as the Redmi's sealed state frame is
        expect(frame.count == 110, "test frame is the size of a real state frame")
        var assembler = BLEFrameAssembler()
        let chunks = BLEFrameAssembler.chunks(of: frame)
        expect(chunks.count == 7, "110 bytes travel as 7 chunks of at most 20 bytes")
        expect(chunks.allSatisfy { $0.count <= 20 }, "no chunk exceeds the notification limit")
        var rebuilt: Data?
        for chunk in chunks.dropLast() {
            expect(assembler.push(chunk) == nil, "a partial frame yields nothing")
        }
        rebuilt = assembler.push(chunks.last!)
        expect(rebuilt == frame, "the chunks rebuild the sealed frame byte for byte")
    }

    // Break caught: a Redmi that terminates a frame whose length divides evenly into chunks with an
    // empty last chunk - the frame must still complete, and the empty chunk must not wipe it.
    static func handlesFrameThatEndsOnTheLastChunk() {
        let frame = frame(payload: 61) // 90 bytes: five chunks of eighteen payload bytes
        var chunks: [Data] = []
        var index = frame.startIndex
        var seq: UInt8 = 0
        while index < frame.endIndex {
            let end = min(index + 18, frame.endIndex)
            var chunk = Data([seq, 0])
            chunk.append(contentsOf: frame[index..<end])
            chunks.append(chunk)
            index = end
            seq = seq &+ 1
        }
        expect(chunks.count == 5, "90 bytes travel as five chunks")
        chunks.append(Data([seq, BLEFrameAssembler.lastChunkFlag]))
        expect(chunks.last?.count == 2, "the terminator carries no payload")
        var assembler = BLEFrameAssembler()
        var rebuilt: Data?
        for chunk in chunks.dropLast() {
            expect(assembler.push(chunk) == nil, "a partial frame yields nothing")
        }
        rebuilt = assembler.push(chunks.last!)
        expect(rebuilt == frame, "an empty terminator chunk completes the frame")
    }

    // Break caught: a link that negotiated a large MTU sends the frame whole and it is treated as
    // a chunk.
    static func passesAWholeFrameStraightThrough() {
        let frame = frame(payload: 81)
        var assembler = BLEFrameAssembler()
        expect(assembler.push(frame) == frame, "a whole frame needs no reassembly")
        expect(assembler.push(frame) == frame, "a second whole frame passes too")
    }

    // Break caught: a frame with a chunk missing is decoded as if the gap never happened.
    static func dropsFramesWithAMissingChunk() {
        let frame = frame(payload: 81)
        let chunks = BLEFrameAssembler.chunks(of: frame)
        var assembler = BLEFrameAssembler()
        expect(assembler.push(chunks[0]) == nil, "first chunk starts the frame")
        expect(assembler.push(chunks[1]) == nil, "second chunk continues it")
        expect(assembler.push(chunks[3]) == nil, "a gap cannot be hidden")
        expect(assembler.push(chunks[4]) == nil, "the broken frame stays broken")
        expect(assembler.push(chunks[5]) == nil, "and never completes")
        expect(assembler.push(chunks[6]) == nil, "even its last chunk yields nothing")
    }

    // Break caught: the chunk that follows a lost frame is discarded instead of starting the next.
    static func acceptsANewFrameAfterAGap() {
        let first = frame(payload: 81)
        let second = frame(payload: 20)
        var assembler = BLEFrameAssembler()
        let firstChunks = BLEFrameAssembler.chunks(of: first)
        _ = assembler.push(firstChunks[0])
        _ = assembler.push(firstChunks[1])
        // The rest of the first frame is lost; the next chunk is the start of the second frame.
        let secondChunks = BLEFrameAssembler.chunks(of: second)
        var rebuilt: Data?
        for chunk in secondChunks { rebuilt = assembler.push(chunk) ?? rebuilt }
        expect(rebuilt == second, "a new frame starts after the broken one")
    }

    // Break caught: a half-finished frame is held forever and later merged with new speech.
    static func discardsAHalfFrameAfterTheTimeout() {
        let frame = frame(payload: 81)
        let chunks = BLEFrameAssembler.chunks(of: frame)
        var assembler = BLEFrameAssembler()
        let start = Date()
        expect(assembler.push(chunks[0], now: start) == nil, "first chunk starts the frame")
        expect(assembler.push(chunks[1], now: start.addingTimeInterval(0.4)) == nil, "still assembling")
        // Over a second later the old half frame must be gone, not completed by stale chunks.
        expect(assembler.push(chunks[2], now: start.addingTimeInterval(1.5)) == nil,
               "a chunk after the timeout cannot continue the old frame")
        var rebuilt: Data?
        for chunk in chunks.dropFirst(3) { rebuilt = assembler.push(chunk, now: start.addingTimeInterval(1.6)) ?? rebuilt }
        expect(rebuilt == nil, "the stale frame never completes")
        // A fresh frame right after the timeout is accepted as a whole.
        var fresh = BLEFrameAssembler()
        var again: Data?
        for chunk in chunks { again = fresh.push(chunk, now: start.addingTimeInterval(2)) ?? again }
        expect(again == frame, "a fresh frame after the timeout reassembles")
    }

    // Break caught: empty or one byte notifications, or a chunk with no frame to join, corrupt the
    // buffer or fabricate a frame.
    static func ignoresNoiseAndOrphanChunks() {
        var assembler = BLEFrameAssembler()
        expect(assembler.push(Data()) == nil, "an empty notification is noise")
        expect(assembler.push(Data([0x01])) == nil, "a one byte notification is noise")
        expect(assembler.push(Data([0x05, 0x00, 0xAA])) == nil, "a chunk without its frame is ignored")
        let frame = frame(payload: 10)
        var rebuilt: Data?
        for chunk in BLEFrameAssembler.chunks(of: frame) { rebuilt = assembler.push(chunk) ?? rebuilt }
        expect(rebuilt == frame, "noise does not stop the next frame")
    }

    // Break caught: a disconnect leaves half a frame that the next connection completes.
    static func resetClearsPartialFrames() {
        let frame = frame(payload: 81)
        let chunks = BLEFrameAssembler.chunks(of: frame)
        var assembler = BLEFrameAssembler()
        _ = assembler.push(chunks[0])
        _ = assembler.push(chunks[1])
        assembler.reset()
        expect(assembler.push(chunks[2]) == nil, "after a reset the old frame is gone")
        var rebuilt: Data?
        for chunk in BLEFrameAssembler.chunks(of: frame) { rebuilt = assembler.push(chunk) ?? rebuilt }
        expect(rebuilt == frame, "the next frame reassembles cleanly")
    }
}
