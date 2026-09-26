import AVFoundation
import Foundation
import Network

/// Local, two-way PCM audio for an active CallKit call.
/// The Redmi sends 20 ms, 16 kHz mono frames over the authenticated relay session.
final class AudioEngine {
    static let shared = AudioEngine()

    var onStatus: ((String) -> Void)?

    private let sampleRate: Double = 16_000
    private let frameSamples = 320
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let networkQueue = DispatchQueue(label: "com.ilkay.redmigateway.audio", qos: .userInteractive)
    private var connection: NWConnection?
    private var heartbeat: DispatchSourceTimer?
    private var sessionId: UInt32 = 0
    private var sequence: UInt16 = 0
    private var playbackPrimed = false
    private var playbackQueue: [Data] = []
    private var queuedBuffers = 0
    private var lastDownlink: Date?
    private var tapInstalled = false
    private var running = false

    private init() {
        engine.attach(player)
    }

    /// CallKit activates the audio session. Configure its policy before reporting a call.
    func prepareSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker]
            )
            try AVAudioSession.sharedInstance().setPreferredIOBufferDuration(0.02)
        } catch {
            report("iPhone ses ayarı başarısız: \(error.localizedDescription)")
        }
    }

    /// Called only after CXProviderDelegate.didActivate and Redmi's audio_ready response.
    func start(host: String, port: UInt16, session: UInt32) {
        stop()
        guard !host.isEmpty, session != 0, let endpointPort = NWEndpoint.Port(rawValue: port) else {
            report("Görüşme sesi: geçersiz bağlantı")
            return
        }

        do {
            let input = engine.inputNode
            let inputFormat = input.outputFormat(forBus: 0)
            guard inputFormat.sampleRate > 0,
                  let convertedFormat = AVAudioFormat(
                    commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                    channels: 1, interleaved: false
                  ),
                  let converter = AVAudioConverter(from: inputFormat, to: convertedFormat),
                  let playbackFormat = AVAudioFormat(
                    commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                    channels: 1, interleaved: false
                  ) else {
                report("Görüşme sesi: ses biçimi açılamadı")
                return
            }

            engine.connect(player, to: engine.mainMixerNode, format: playbackFormat)
            var pendingSamples: [Int16] = []
            let tapFrames = AVAudioFrameCount(max(256, Int(inputFormat.sampleRate / 50)))
            input.installTap(onBus: 0, bufferSize: tapFrames, format: inputFormat) { [weak self] buffer, _ in
                guard let self else { return }
                let capacity = AVAudioFrameCount(
                    max(1, Int(ceil(Double(buffer.frameLength) * self.sampleRate / inputFormat.sampleRate)) + 32)
                )
                guard let converted = AVAudioPCMBuffer(pcmFormat: convertedFormat, frameCapacity: capacity) else {
                    return
                }
                var fed = false
                var conversionError: NSError?
                _ = converter.convert(to: converted, error: &conversionError) { _, status in
                    if fed {
                        status.pointee = .noDataNow
                        return nil
                    }
                    fed = true
                    status.pointee = .haveData
                    return buffer
                }
                guard conversionError == nil,
                      converted.frameLength > 0,
                      let channel = converted.floatChannelData?[0] else { return }

                for index in 0..<Int(converted.frameLength) {
                    let value = max(-1.0, min(1.0, channel[index]))
                    pendingSamples.append(Int16(value * 32767.0))
                }
                while pendingSamples.count >= self.frameSamples {
                    let frame = Array(pendingSamples.prefix(self.frameSamples))
                    pendingSamples.removeFirst(self.frameSamples)
                    self.networkQueue.async { [weak self] in self?.sendPcm(frame) }
                }
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            player.play()
            running = true

            networkQueue.async { [weak self] in
                guard let self else { return }
                self.sessionId = session
                self.sequence = 0
                self.playbackPrimed = false
                self.playbackQueue.removeAll()
                self.queuedBuffers = 0
                self.lastDownlink = nil

                let socket = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .udp)
                self.connection = socket
                socket.stateUpdateHandler = { [weak self, weak socket] state in
                    guard let self, let socket, self.connection === socket else { return }
                    switch state {
                    case .ready:
                        self.report("iPhone görüşme sesi bağlanıyor")
                        self.sendKeepalive()
                        self.receive(on: socket)
                    case .failed(let error):
                        self.report("Görüşme ses bağlantısı koptu: \(error.localizedDescription)")
                    default:
                        break
                    }
                }
                socket.start(queue: self.networkQueue)

                let timer = DispatchSource.makeTimerSource(queue: self.networkQueue)
                timer.schedule(deadline: .now() + 1, repeating: 1)
                timer.setEventHandler { [weak self] in self?.sendKeepalive() }
                self.heartbeat = timer
                timer.resume()
            }
        } catch {
            report("iPhone görüşme sesi açılamadı: \(error.localizedDescription)")
            stop()
        }
    }

    func stop() {
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if running {
            player.stop()
            engine.stop()
            running = false
        }
        networkQueue.async { [weak self] in
            guard let self else { return }
            self.heartbeat?.cancel()
            self.heartbeat = nil
            self.connection?.cancel()
            self.connection = nil
            self.sessionId = 0
            self.playbackQueue.removeAll()
            self.playbackPrimed = false
        }
    }

    private func sendKeepalive() {
        sendPacket(type: 0, pcm: Data())
    }

    private func sendPcm(_ samples: [Int16]) {
        guard sessionId != 0 else { return }
        var pcm = Data(capacity: samples.count * 2)
        for sample in samples {
            let bits = UInt16(bitPattern: sample)
            pcm.append(UInt8(truncatingIfNeeded: bits))
            pcm.append(UInt8(truncatingIfNeeded: bits >> 8))
        }
        sendPacket(type: 1, pcm: pcm)
    }

    private func sendPacket(type: UInt8, pcm: Data) {
        guard sessionId != 0, let socket = connection else { return }
        var packet = Data(capacity: 8 + pcm.count)
        packet.append(UInt8(truncatingIfNeeded: sessionId >> 24))
        packet.append(UInt8(truncatingIfNeeded: sessionId >> 16))
        packet.append(UInt8(truncatingIfNeeded: sessionId >> 8))
        packet.append(UInt8(truncatingIfNeeded: sessionId))
        packet.append(UInt8(truncatingIfNeeded: sequence >> 8))
        packet.append(UInt8(truncatingIfNeeded: sequence))
        packet.append(type)
        packet.append(0)
        packet.append(pcm)
        sequence &+= 1
        socket.send(content: packet, completion: .contentProcessed { _ in })
    }

    private func receive(on socket: NWConnection) {
        socket.receiveMessage { [weak self, weak socket] data, _, _, error in
            guard let self, let socket, self.connection === socket else { return }
            if let data, data.count >= 8 {
                let bytes = [UInt8](data)
                let receivedSession = (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16) |
                    (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
                if receivedSession == self.sessionId && bytes[6] == 1 && (data.count - 8) % 2 == 0 {
                    let now = Date()
                    if let last = self.lastDownlink, now.timeIntervalSince(last) > 0.5 {
                        self.playbackQueue.removeAll()
                        self.playbackPrimed = false
                    }
                    self.lastDownlink = now
                    self.playbackQueue.append(data.dropFirst(8))
                    if !self.playbackPrimed && self.playbackQueue.count >= 3 {
                        self.playbackPrimed = true
                        self.report("Görüşme sesi iPhone'a ulaşıyor")
                    }
                    if self.playbackPrimed {
                        while !self.playbackQueue.isEmpty {
                            self.schedulePlayback(self.playbackQueue.removeFirst())
                        }
                    }
                }
            }
            if error == nil { self.receive(on: socket) }
        }
    }

    private func schedulePlayback(_ pcm: Data) {
        guard queuedBuffers < 12,
              let format = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                         sampleRate: sampleRate, channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format,
                                            frameCapacity: AVAudioFrameCount(pcm.count / 2)),
              let channel = buffer.floatChannelData?[0] else { return }
        let bytes = [UInt8](pcm)
        let count = bytes.count / 2
        buffer.frameLength = AVAudioFrameCount(count)
        for index in 0..<count {
            let bits = UInt16(bytes[index * 2]) | (UInt16(bytes[index * 2 + 1]) << 8)
            channel[index] = Float(Int16(bitPattern: bits)) / 32768.0
        }
        queuedBuffers += 1
        player.scheduleBuffer(buffer) { [weak self] in
            self?.networkQueue.async { [weak self] in
                guard let self else { return }
                self.queuedBuffers = max(0, self.queuedBuffers - 1)
            }
        }
    }

    private func report(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.onStatus?(message) }
    }
}
