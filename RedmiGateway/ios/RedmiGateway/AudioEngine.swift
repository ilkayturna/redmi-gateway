import AVFoundation
import Darwin
import Foundation
import Network

/// Two-way call audio for a CallKit call whose conversation the Redmi hands to this iPhone.
///
/// 20 ms frames of 16 kHz mono PCM16 travel over UDP, sealed with the connection's media key
/// (see MediaSealer). Every uplink packet tells the Redmi whether downlink audio is arriving
/// here, so the Redmi only keeps the call on the iPhone while audio flows both ways.
final class AudioEngine {
    static let shared = AudioEngine()

    /// Technical events for the diagnostics log; never shown as call UI.
    var onLog: ((String) -> Void)?
    var onNeedsRestart: (() -> Void)?
    private var captureMute = CallAudioCaptureMute()
    var isMuted: Bool {
        get {
            captureLock.lock()
            defer { captureLock.unlock() }
            return captureMute.isMuted
        }
        set {
            captureLock.lock()
            if captureMute.setMuted(newValue, now: Self.monotonicNow()) { capturePacer.discardPending() }
            captureLock.unlock()
        }
    }
    private(set) var isSpeakerEnabled = false

    private let sampleRate: Double = 16_000
    private let frameSamples = 320
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let networkQueue = DispatchQueue(label: "com.ilkay.redmigateway.audio", qos: .userInteractive)

    // networkQueue state
    private var connection: NWConnection?
    private var heartbeat: DispatchSourceTimer?
    private var captureTimer: DispatchSourceTimer?
    private var activeGeneration: UInt64?
    private var pendingSends = 0
    private var sealer: MediaSealer?
    private var lastSession: UInt32 = 0
    private var lastKey = Data()
    /// Packet counters live as long as the session: a GCM nonce must never repeat under one key.
    private var txCounter: UInt32 = 0
    private var rxLastCounter: UInt32 = 0
    private var playbackBuffer = CallAudioPlaybackBuffer<Data>()
    private var lastDownlink: Date?
    private var lastPcmSentAt: Date?
    private var recoveryRequested = false

    // The tap deposits directly into this bounded queue, so a stalled network queue
    // cannot retain an unbounded number of async closures containing microphone PCM.
    private let captureLock = NSLock()
    private var capturePacer = CallAudioPacer<[Int16]>()

    // main queue state
    private var tapInstalled = false
    private var running = false
    private var isInterrupted = false
    private var lastRecoveryRequest = Date.distantPast
    private(set) var isSessionPrepared = false
    var isRunning: Bool { running }

    private init() {
        engine.attach(player)
        let center = NotificationCenter.default
        center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            self?.requestRecovery("audio hardware changed")
        }
        center.addObserver(forName: AVAudioSession.routeChangeNotification,
                           object: AVAudioSession.sharedInstance(), queue: .main) { [weak self] notification in
            guard let self,
                  let value = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: value) else { return }
            switch reason {
            case .oldDeviceUnavailable, .newDeviceAvailable, .routeConfigurationChange:
                self.requestRecovery("audio route changed")
            default:
                // Speaker toggles and category updates do not need a rebuilt graph.
                break
            }
        }
        center.addObserver(forName: AVAudioSession.interruptionNotification,
                           object: AVAudioSession.sharedInstance(), queue: .main) { [weak self] notification in
            guard let self,
                  let value = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: value) else { return }
            switch type {
            case .began:
                self.isInterrupted = true
                if self.running {
                    self.player.pause()
                    self.engine.pause()
                }
            case .ended:
                self.isInterrupted = false
                self.requestRecovery("interruption ended")
            @unknown default:
                break
            }
        }
    }

    func resetSessionPrepared() {
        isSessionPrepared = false
    }

    /// Asked while the app is open, so a first call never needs a prompt over the lock screen.
    func requestMicrophonePermission() {
        let session = AVAudioSession.sharedInstance()
        if session.recordPermission == .undetermined {
            session.requestRecordPermission { _ in }
        }
    }

    var microphoneAllowed: Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }

    /// CallKit activates the audio session; its policy is set before a call is reported.
    func prepareSession() {
        guard !running else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothHFP, .allowBluetoothA2DP]
            )
            try AVAudioSession.sharedInstance().setPreferredIOBufferDuration(0.02)
            isSessionPrepared = true
        } catch {
            log("Audio session setup failed: \(error.localizedDescription)")
        }
    }

    @discardableResult
    func setSpeakerEnabled(_ enabled: Bool) -> Bool {
        do {
            try AVAudioSession.sharedInstance().overrideOutputAudioPort(enabled ? .speaker : .none)
            isSpeakerEnabled = enabled
            return true
        } catch {
            log("Speaker switch failed: \(error.localizedDescription)")
            return false
        }
    }

    /// Called after CallKit activated the audio session and the Redmi answered audio_start.
    func start(host: String, port: UInt16, session: UInt32, key: Data) {
        stop(preservingControls: true)
        guard microphoneAllowed else {
            log("Microphone permission missing")
            return
        }
        guard !host.isEmpty, session != 0, key.count == BridgeCrypto.keyBytes,
              let endpointPort = NWEndpoint.Port(rawValue: port) else {
            log("Invalid audio endpoint")
            return
        }
        captureLock.lock()
        let generation = capturePacer.generation
        captureLock.unlock()
        do {
            let input = engine.inputNode
            try? input.setVoiceProcessingEnabled(true)
            let inputFormat = input.outputFormat(forBus: 0)
            guard inputFormat.sampleRate > 0,
                  let monoFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                                                 channels: 1, interleaved: false),
                  let converter = AVAudioConverter(from: inputFormat, to: monoFormat) else {
                log("Audio format unavailable")
                return
            }
            if inputFormat.channelCount > 1 { converter.channelMap = [0 as NSNumber] }
            engine.connect(player, to: engine.mainMixerNode, format: monoFormat)

            var pending: [Int16] = []
            var pendingMuteGeneration: UInt64?
            let tapFrames = AVAudioFrameCount(max(256, Int(inputFormat.sampleRate / 50)))
            input.installTap(onBus: 0, bufferSize: tapFrames, format: inputFormat) { [weak self] buffer, when in
                guard let self else { return }
                let captureMute = self.captureMuteSnapshot()
                let now = Self.monotonicNow()
                let capturedAt = when.isHostTimeValid
                    ? min(now, AVAudioTime.seconds(forHostTime: when.hostTime)
                               + Double(buffer.frameLength) / inputFormat.sampleRate)
                    : now
                let capacity = AVAudioFrameCount(Int(ceil(Double(buffer.frameLength) * self.sampleRate / inputFormat.sampleRate)) + 32)
                guard let converted = AVAudioPCMBuffer(pcmFormat: monoFormat, frameCapacity: capacity) else { return }
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
                guard conversionError == nil, converted.frameLength > 0,
                      let channel = converted.floatChannelData?[0] else { return }
                if pendingMuteGeneration != captureMute.generation { pending.removeAll() }
                pendingMuteGeneration = captureMute.generation
                for index in 0..<Int(converted.frameLength) {
                    pending.append(Int16(max(-1, min(1, channel[index])) * 32767))
                }
                var frames: [[Int16]] = []
                while pending.count >= self.frameSamples {
                    let frame = Array(pending.prefix(self.frameSamples))
                    pending.removeFirst(self.frameSamples)
                    frames.append(frame)
                }
                self.enqueueCapture(frames, capturedAt: capturedAt - Double(pending.count) / self.sampleRate,
                                    generation: generation, captureMute: captureMute)
            }
            tapInstalled = true
            engine.prepare()
            try engine.start()
            player.play()
            running = true
            if isSpeakerEnabled { try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker) }
        } catch {
            log("Audio engine start failed: \(error.localizedDescription)")
            stop()
            return
        }

        networkQueue.async { [weak self] in
            guard let self else { return }
            if self.lastSession != session || self.lastKey != key {
                self.txCounter = 0
                self.rxLastCounter = 0
            }
            self.lastSession = session
            self.lastKey = key
            self.sealer = MediaSealer(key: key, session: session)
            self.activeGeneration = generation
            self.pendingSends = 0
            self.playbackBuffer.reset()
            self.lastDownlink = nil
            self.lastPcmSentAt = Date()
            self.recoveryRequested = false

            // Interactive voice: Wi-Fi queues these packets as voice (WMM AC_VO), which keeps
            // latency low on busy hotspots and shared networks.
            let parameters = NWParameters.udp
            parameters.serviceClass = .interactiveVoice
            parameters.includePeerToPeer = true
            let socket = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: parameters)
            self.connection = socket
            socket.stateUpdateHandler = { [weak self, weak socket] state in
                guard let self, let socket, self.connection === socket else { return }
                switch state {
                case .ready:
                    self.sendPacket(type: 0, pcm: Data())
                    self.receive(on: socket)
                case .failed(let error):
                    self.log("Audio socket failed: \(error.localizedDescription)")
                default:
                    break
                }
            }
            socket.start(queue: self.networkQueue)

            // Each one-shot uses the pacer's nominal deadline, accounting for handler
            // cost. A real stall starts a new phase; it never triggers a catch-up loop.
            let captureTimer = DispatchSource.makeTimerSource(queue: self.networkQueue)
            captureTimer.schedule(deadline: .now() + .milliseconds(20), leeway: .milliseconds(1))
            captureTimer.setEventHandler { [weak self, weak socket] in
                guard let self, let socket, self.connection === socket,
                      self.activeGeneration == generation else { return }
                if self.pendingSends < 3 {
                    self.captureLock.lock()
                    let frame = self.capturePacer.generation == generation
                        ? self.capturePacer.next(now: Self.monotonicNow()) : nil
                    self.captureLock.unlock()
                    if let frame { self.sendPcm(frame) }
                }
                self.captureLock.lock()
                let wakeUp = self.capturePacer.nextWakeUp(now: Self.monotonicNow())
                self.captureLock.unlock()
                let delay = max(0, wakeUp - Self.monotonicNow())
                self.captureTimer?.schedule(deadline: .now() + .nanoseconds(Int(ceil(delay * 1_000_000_000))),
                                            leeway: .milliseconds(1))
            }
            self.captureTimer = captureTimer
            captureTimer.resume()

            let timer = DispatchSource.makeTimerSource(queue: self.networkQueue)
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler { [weak self] in
                guard let self, self.activeGeneration == generation else { return }
                self.sendPacket(type: 0, pcm: Data())
                if let last = self.lastPcmSentAt, Date().timeIntervalSince(last) > 3, !self.recoveryRequested {
                    self.recoveryRequested = true
                    DispatchQueue.main.async { [weak self] in self?.requestRecovery("microphone stream stopped") }
                }
            }
            self.heartbeat = timer
            timer.resume()
        }
    }

    func stop(preservingControls: Bool = false) {
        captureLock.lock()
        capturePacer.reset()
        // schedulePlayback uses this lock too: no old receive can schedule a buffer
        // after this stop and leave it waiting for the following player.play().
        player.stop()
        captureLock.unlock()
        if !preservingControls {
            isMuted = false
            isSpeakerEnabled = false
            try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.none)
        }
        isInterrupted = false
        if tapInstalled {
            engine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if running {
            engine.stop()
            running = false
        }
        networkQueue.async { [weak self] in
            guard let self else { return }
            self.heartbeat?.cancel()
            self.heartbeat = nil
            self.captureTimer?.cancel()
            self.captureTimer = nil
            self.activeGeneration = nil
            self.pendingSends = 0
            self.connection?.cancel()
            self.connection = nil
            self.sealer = nil
            self.playbackBuffer.reset()
            self.lastPcmSentAt = nil
            self.lastDownlink = nil
        }
    }

    /// A route switch can invalidate the input tap without ending the CallKit call.
    private func requestRecovery(_ reason: String) {
        guard running, !isInterrupted else { return }
        let now = Date()
        guard now.timeIntervalSince(lastRecoveryRequest) > 1.5 else { return }
        lastRecoveryRequest = now
        log("Rebuilding call audio: \(reason)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.onNeedsRestart?() }
    }

    // MARK: - networkQueue

    private static func monotonicNow() -> TimeInterval {
        AVAudioTime.seconds(forHostTime: mach_absolute_time())
    }

    private func captureMuteSnapshot() -> CallAudioCaptureMute {
        captureLock.lock()
        defer { captureLock.unlock() }
        return captureMute
    }

    private func enqueueCapture(_ frames: [[Int16]], capturedAt: TimeInterval, generation: UInt64,
                                captureMute snapshot: CallAudioCaptureMute) {
        captureLock.lock()
        let protectedFrames = captureMute.filter(frames, capturedAt: capturedAt,
                                                capturedGeneration: snapshot.generation,
                                                capturedWhileMuted: snapshot.isMuted)
        capturePacer.enqueue(protectedFrames, capturedAt: capturedAt,
                             now: Self.monotonicNow(), generation: generation)
        captureLock.unlock()
    }

    private func sendPcm(_ samples: [Int16]) {
        guard sealer != nil else { return }
        var pcm = Data(capacity: samples.count * 2)
        let muted = isMuted
        for sample in samples {
            // Muted frames keep flowing as silence so the Redmi still sees a live iPhone.
            let bits = UInt16(bitPattern: muted ? 0 : sample)
            pcm.append(UInt8(truncatingIfNeeded: bits))
            pcm.append(UInt8(truncatingIfNeeded: bits >> 8))
        }
        if sendPacket(type: 1, pcm: pcm) { lastPcmSentAt = Date() }
    }

    @discardableResult
    private func sendPacket(type: UInt8, pcm: Data) -> Bool {
        guard pendingSends < 3, let sealer, let socket = connection else { return false }
        txCounter &+= 1
        let hearing = lastDownlink.map { Date().timeIntervalSince($0) < 0.5 } ?? false
        guard let packet = sealer.seal(type: type, flags: hearing ? MediaSealer.flagHearing : 0,
                                       counter: txCounter, payload: pcm) else { return false }
        pendingSends += 1
        socket.send(content: packet, completion: .contentProcessed { [weak self, weak socket] _ in
            self?.networkQueue.async { [weak self, weak socket] in
                guard let self, let socket, self.connection === socket else { return }
                self.pendingSends = max(0, self.pendingSends - 1)
            }
        })
        return true
    }

    private func receive(on socket: NWConnection) {
        socket.receiveMessage { [weak self, weak socket] data, _, _, error in
            guard let self, let socket, self.connection === socket else { return }
            if let data, let sealer = self.sealer, let opened = sealer.open(data), opened.counter > self.rxLastCounter {
                self.rxLastCounter = opened.counter
                if opened.type == 1, !opened.payload.isEmpty, opened.payload.count % 2 == 0 {
                    self.enqueuePlayback(opened.payload)
                }
            }
            if error == nil { self.receive(on: socket) }
        }
    }

    private func enqueuePlayback(_ pcm: Data) {
        let now = Date()
        lastDownlink = now
        for frame in playbackBuffer.enqueue(pcm, now: Self.monotonicNow()) {
            schedulePlayback(frame)
        }
    }

    private func schedulePlayback(_ pcm: Data) {
        let generation = playbackBuffer.generation
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                                         channels: 1, interleaved: false),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(pcm.count / 2)),
              let channel = buffer.floatChannelData?[0] else {
            playbackBuffer.completed(generation: generation)
            return
        }
        let bytes = [UInt8](pcm)
        let count = bytes.count / 2
        buffer.frameLength = AVAudioFrameCount(count)
        for index in 0..<count {
            let bits = UInt16(bytes[index * 2]) | (UInt16(bytes[index * 2 + 1]) << 8)
            channel[index] = Float(Int16(bitPattern: bits)) / 32768
        }
        captureLock.lock()
        defer { captureLock.unlock() }
        guard let activeGeneration, capturePacer.generation == activeGeneration else {
            playbackBuffer.completed(generation: generation)
            return
        }
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            self?.networkQueue.async { [weak self] in
                guard let self else { return }
                self.playbackBuffer.completed(generation: generation)
            }
        }
    }

    private func log(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.onLog?(message) }
    }
}
