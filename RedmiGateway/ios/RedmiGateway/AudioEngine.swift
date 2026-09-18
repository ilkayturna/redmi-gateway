import Foundation
import AVFoundation
import Network

/// Low-Latency Full-Duplex Audio Engine for RedmiGateway iOS CallKit client.
/// Uses AVAudioEngine with .voiceChat mode and separate UDP sockets.
public final class AudioEngine: ObservableObject {
    public static let shared = AudioEngine()

    @Published public var isRunning: Bool = false
    @Published public var isMockLoopback: Bool = false
    @Published public var roundTripLatencyMs: Double = 0.0
    @Published public var packetsSent: UInt64 = 0
    @Published public var packetsReceived: UInt64 = 0

    private let audioEngine = AVAudioEngine()
    private var inputNode: AVAudioInputNode?
    private var playerNode = AVAudioPlayerNode()

    private var txConnection: NWConnection? // Outgoing to Redmi (port 8791)
    private var rxListener: NWListener?     // Incoming from Redmi (port 8790)

    private var redmiHost: String = ""
    private var txPort: UInt16 = 8791
    private var rxPort: UInt16 = 8790

    private var sequenceNumber: UInt32 = 0
    private var pingTimestamps: [UInt32: DispatchTime] = [:]

    private let sampleRate: Double = 16000.0
    private let channels: AVAudioChannelCount = 1

    private init() {}

    public func configure(host: String, txPort: UInt16 = 8791, rxPort: UInt16 = 8790) {
        self.redmiHost = host
        self.txPort = txPort
        self.rxPort = rxPort
    }

    public func start() {
        guard !isRunning else { return }
        setupAudioSession()
        setupAudioEngine()
        setupUdpNetworking()
        isRunning = true
    }

    public func stop() {
        guard isRunning else { return }
        audioEngine.stop()
        inputNode?.removeTap(onBus: 0)
        playerNode.stop()

        txConnection?.cancel()
        rxListener?.cancel()

        txConnection = nil
        rxListener = nil
        isRunning = false
    }

    // MARK: - Audio Session Setup
    private func setupAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
            try session.setPreferredSampleRate(sampleRate)
            try session.setPreferredIOBufferDuration(0.02) // 20ms buffer
            try session.setActive(true)
        } catch {
            print("[AudioEngine] Error setting up AVAudioSession: \(error)")
        }
    }

    // MARK: - AVAudioEngine Setup
    private func setupAudioEngine() {
        let inputFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: channels, interleaved: true)!

        audioEngine.attach(playerNode)
        audioEngine.connect(playerNode, to: audioEngine.mainMixerNode, format: inputFormat)

        inputNode = audioEngine.inputNode
        inputNode?.installTap(onBus: 0, bufferSize: 320, format: nil) { [weak self] (buffer, time) in
            self?.processMicrophoneBuffer(buffer)
        }

        do {
            try audioEngine.start()
            playerNode.play()
        } catch {
            print("[AudioEngine] Error starting AVAudioEngine: \(error)")
        }
    }

    // MARK: - Microphone Processing -> UDP Packet
    private func processMicrophoneBuffer(_ buffer: AVAudioPCMBuffer) {
        guard isRunning, let txConn = txConnection else { return }

        // Downsample / convert to 16kHz 16-bit mono PCM
        let channelData = buffer.floatChannelData?[0]
        let frameCount = Int(buffer.frameLength)
        guard let floatData = channelData, frameCount > 0 else { return }

        var pcmData = Data()
        for i in 0..<frameCount {
            let sample = max(-1.0, min(1.0, floatData[i]))
            var int16Sample = Int16(sample * 32767.0)
            pcmData.append(UnsafeBufferPointer(start: &int16Sample, count: 1))
        }

        // Header: seq (4 bytes), timestamp (4 bytes), type (1 byte)
        var packet = Data()
        var seqBE = sequenceNumber.bigEndian
        let nowMs = UInt32(Date().timeIntervalSince1970 * 1000)
        var tsBE = nowMs.bigEndian
        var type: UInt8 = 0x01 // PCM 16kHz mono

        packet.append(Data(bytes: &seqBE, count: 4))
        packet.append(Data(bytes: &tsBE, count: 4))
        packet.append(Data(bytes: &type, count: 1))
        packet.append(pcmData)

        pingTimestamps[sequenceNumber] = DispatchTime.now()
        sequenceNumber += 1

        txConn.send(content: packet, completion: .contentProcessed { [weak self] error in
            if error == nil {
                DispatchQueue.main.async { self?.packetsSent += 1 }
            }
        })
    }

    // MARK: - UDP Networking
    private func setupUdpNetworking() {
        guard !redmiHost.isEmpty else { return }

        // TX to Redmi
        let hostEndpoint = NWEndpoint.Host(redmiHost)
        let portEndpoint = NWEndpoint.Port(rawValue: txPort)!
        txConnection = NWConnection(host: hostEndpoint, port: portEndpoint, using: .udp)
        txConnection?.start(queue: .global(qos: .userInteractive))

        // RX Listener on rxPort
        do {
            rxListener = try NWListener(using: .udp, on: NWEndpoint.Port(rawValue: rxPort)!)
            rxListener?.newConnectionHandler = { [weak self] incomingConn in
                incomingConn.start(queue: .global(qos: .userInteractive))
                self?.receiveLoop(on: incomingConn)
            }
            rxListener?.start(queue: .global(qos: .userInteractive))
        } catch {
            print("[AudioEngine] Failed to start RX UDP listener: \(error)")
        }
    }

    private func receiveLoop(on connection: NWConnection) {
        connection.receiveMessage { [weak self] (data, context, isComplete, error) in
            guard let self = self, let packet = data, packet.count > 9 else {
                if isComplete || error != nil { return }
                return
            }

            let seq = packet.subdata(in: 0..<4).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            _ = packet.subdata(in: 4..<8).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            let pcmPayload = packet.subdata(in: 9..<packet.count)

            // Calculate Round-Trip Latency if loopback
            if let sendTime = self.pingTimestamps.removeValue(forKey: seq) {
                let elapsedNs = DispatchTime.now().uptimeNanoseconds - sendTime.uptimeNanoseconds
                let latencyMs = Double(elapsedNs) / 1_000_000.0
                DispatchQueue.main.async {
                    self.roundTripLatencyMs = latencyMs
                    self.packetsReceived += 1
                }
            }

            self.playPcmData(pcmPayload)
            self.receiveLoop(on: connection)
        }
    }

    private func playPcmData(_ data: Data) {
        let frameCount = data.count / 2
        guard frameCount > 0,
              let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: channels, interleaved: true),
              let pcmBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)) else {
            return
        }

        pcmBuffer.frameLength = AVAudioFrameCount(frameCount)
        data.withUnsafeBytes { rawBuffer in
            if let src = rawBuffer.baseAddress {
                memcpy(pcmBuffer.int16ChannelData![0], src, data.count)
            }
        }

        playerNode.scheduleBuffer(pcmBuffer, at: nil, options: [], completionHandler: nil)
    }
}
