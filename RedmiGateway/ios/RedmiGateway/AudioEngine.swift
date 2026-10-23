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
    /// How many times a start is retried while the freshly activated session reports no input format.
    /// 20 x 150 ms = 3 s, inside the Redmi's 8 s wait, so a retry that works still lands in time.
    private let maxStartAttempts = 20
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let networkQueue = DispatchQueue(label: "com.ilkay.redmigateway.audio", qos: .userInteractive)

    // networkQueue state
    private var connection: NWConnection?
    private var heartbeat: DispatchSourceTimer?
    private var captureTimer: DispatchSourceTimer?
    private var activeGeneration: UInt64?
    private var pendingSends = 0
    /// Four datagrams in flight leave room for the 263 ms link stalls without making the capture
    /// queue wait on a single completion; the old limit of three was hit by every Wi-Fi retry.
    private let maximumPendingSends = 4
    private var sealer: MediaSealer?
    private var lastSession: UInt32 = 0
    private var lastKey = Data()
    /// Packet counters live as long as the session: a GCM nonce must never repeat under one key.
    private var txCounter: UInt32 = 0
    private var rxLastCounter: UInt32 = 0
    private var playbackBuffer = CallAudioPlaybackBuffer<Data>()
    private var lastDownlink: Date?
    /// Counter of the last downlink packet that carried PCM, used to decide whether the copy in a
    /// redundant (type 2) packet is a frame that never arrived.
    private var lastPcmRxCounter: UInt32 = 0
    /// Downlink receive health: what actually arrived, versus what the Redmi sent.
    private var receivedFrames = 0
    private var recoveredFrames = 0
    private var missingPackets = 0
    private var shortPackets = 0
    private var lastReceivedFrames = 0
    private var lastRecoveredFrames = 0
    private var lastMissingPackets = 0
    private var pcmFrameBytes: Int { frameSamples * 2 }
    private var lastPcmSentAt: Date?
    private var recoveryRequested = false
    /// Playback budget drops are silent; the diagnostics log has to show them to be fixable.
    private var loggedPlaybackDrops = 0
    /// True once voice processing has been configured early, so warm-up never pays for it twice.
    private var voiceProcessingWarmed = false
    /// Repeat every uplink frame in the next packet; see setUplinkRedundancy.
    private var uplinkRedundancy = false
    private var lastSentPcm: Data?
    /// Capture health counters, all touched on the network queue except the pacer's own.
    private var sentFrames = 0
    private var lastProducedFrames = 0
    private var lastSentFrames = 0
    private var lastCaptureHealthAt: Date?
    /// Timestamps for the "where did the first second go" diagnostics.
    private var engineStartedAt: Date?
    private var firstPcmLogged = false

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
    /// The Swift flag can outlive the actual engine: an audio-route change stops AVAudioEngine
    /// without touching `running`. Callers asking "is audio flowing?" must see the real state.
    var isRunning: Bool { running && engine.isRunning }

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
                self.log("Audio interruption began (flag=\(self.running), engine=\(self.engine.isRunning))")
                if self.running {
                    self.player.pause()
                    self.engine.pause()
                }
            case .ended:
                self.isInterrupted = false
                self.log("Audio interruption ended (flag=\(self.running), engine=\(self.engine.isRunning))")
                self.requestRecovery("interruption ended")
            @unknown default:
                break
            }
        }
    }

    func resetSessionPrepared() {
        isSessionPrepared = false
        voiceProcessingWarmed = false
    }

    /// Repeats every uplink frame once, in the packet that follows it.
    ///
    /// A frame lost in transit (Wi-Fi retries, a hotspot hiccup) is rebuilt by the Redmi from the
    /// copy in the next packet, which removes the 20 ms hole that otherwise plays as a click. The
    /// copy costs 100 % more uplink bytes, so it is only enabled where those bytes are free - the
    /// Redmi asks for it in audio_ready and the caller keeps it off a remote (data plan) link.
    /// Nothing is delayed: the copy rides along with the frame that follows.
    func setUplinkRedundancy(_ enabled: Bool) {
        guard uplinkRedundancy != enabled else { return }
        uplinkRedundancy = enabled
        log("Uplink redundancy \(enabled ? "on" : "off")")
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

    /// Builds the parts of the call-audio graph that do not depend on the active session, so the
    /// start after CallKit activates it only has to run engine.start(). Nothing captures audio
    /// here: the engine stays stopped, so there is no microphone use, no indicator and no CPU while
    /// the phone is idle. The format-dependent steps stay in start(), because activating the
    /// session can still change the input format.
    func warmUp() {
        guard !running else { return }
        let began = Date()
        _ = engine.inputNode
        if !voiceProcessingWarmed {
            do {
                try engine.inputNode.setVoiceProcessingEnabled(true)
                voiceProcessingWarmed = true
            } catch {
                // The session may not accept voice processing before CallKit activates it; the
                // start path configures it exactly as it always did.
                log("Audio path: voice processing warm-up skipped: \(error.localizedDescription)")
            }
        }
        engine.prepare()
        let ms = Int(Date().timeIntervalSince(began) * 1000)
        log("Audio path: warm-up \(voiceProcessingWarmed ? "done" : "partial") in \(ms) ms (engine stopped)")
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
    ///
    /// `attempt` only exists for the retry below; every caller uses the default.
    /// `force` is used only by recovery: a route or hardware change must rebuild the graph even
    /// when the session id and key are unchanged.
    func start(host: String, port: UInt16, session: UInt32, key: Data,
               attempt: Int = 1, force: Bool = false) {
        // A second start for the same session must not tear a working graph down and build it again.
        // Measured on 17.10.2026 at 14:10: one call asked for the transfer three times, every copy
        // ran the whole start again, and each restart cost an audio-route change plus seconds of
        // silence - the 1.9 s handover the user waited for was three competing starts. The session
        // id and key identify the same relay, so an identical request is a no-op - but only while
        // the engine is really running. On 22.10.2026 at 15:44 an audio-hardware change stopped
        // the engine; recovery asked for a start, this guard saw the stale `running` flag, ignored
        // it, and the call never got audio back. `force` (recovery) must always rebuild.
        let wasRunning = running
        let wasEngineRunning = engine.isRunning
        if !force, wasRunning, wasEngineRunning, session == lastSession, key == lastKey {
            log("Audio engine already running for this session; duplicate start ignored")
            return
        }
        if force || wasRunning {
            log("Audio engine rebuilding (force=\(force), flag=\(wasRunning), " +
                "engine=\(wasEngineRunning), session=\(session))")
        }
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
        // CallKit normally activates the session before it hands the call over; if it did not, the
        // input node reports no format at all and every start below fails.
        // `AVAudioSession` has no `isActive` getter in the iOS SDK. Activating an already active
        // session is a no-op, so this both covers CallKit not having activated it and keeps the
        // recovery path deterministic after a route change.
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setActive(true)
        } catch {
            log("Audio session could not be activated: \(error.localizedDescription)")
        }
        captureLock.lock()
        let generation = capturePacer.generation
        captureLock.unlock()
        do {
            let input = engine.inputNode
            // Voice processing is the expensive part of this setup the first time (hundreds of
            // milliseconds). warmUp() pays that while the phone is still ringing; this call stays
            // unconditional, so a warm-up that the idle session refused changes nothing here.
            let vpBegan = Date()
            try? input.setVoiceProcessingEnabled(true)
            let vpMs = Int(Date().timeIntervalSince(vpBegan) * 1000)
            let inputFormat = input.outputFormat(forBus: 0)
            guard inputFormat.sampleRate > 0,
                  let monoFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                                                 channels: 1, interleaved: false),
                  let converter = AVAudioConverter(from: inputFormat, to: monoFormat) else {
                // Measured on 22.10.2026 14:39: the person answered on the iPhone, CallKit handed the
                // call over, and every start returned running=false with exactly this line. No PCM
                // ever reached the Redmi, which took the audio back after eight seconds, so the whole
                // conversation stayed on the Redmi. The input node can report no format for a moment
                // right after the session is activated, and giving up at that instant loses the call.
                let sessionInfo = AVAudioSession.sharedInstance()
                log("Audio format unavailable (attempt \(attempt)/\(maxStartAttempts), " +
                    "inputSr=\(inputFormat.sampleRate), channels=\(inputFormat.channelCount), " +
                    "sessionSr=\(sessionInfo.sampleRate))")
                if attempt < maxStartAttempts, !running {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                        guard let self, !self.running else { return }
                        self.start(host: host, port: port, session: session, key: key,
                                   attempt: attempt + 1, force: force)
                    }
                }
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
            let startBegan = Date()
            try engine.start()
            let startMs = Int(Date().timeIntervalSince(startBegan) * 1000)
            player.play()
            running = true
            log("Audio engine: vp=\(vpMs) ms start=\(startMs) ms")
            if isSpeakerEnabled { try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker) }
        } catch {
            log("Audio engine start failed: \(error.localizedDescription)")
            stop()
            return
        }

        networkQueue.async { [weak self] in
            guard let self else { return }
            // Both are read by sendPcm on this same queue, so the first-frame measurement stays
            // single-threaded.
            self.engineStartedAt = Date()
            self.firstPcmLogged = false
            if self.lastSession != session || self.lastKey != key {
                self.txCounter = 0
                self.rxLastCounter = 0
                self.lastPcmRxCounter = 0
                self.receivedFrames = 0
                self.recoveredFrames = 0
                self.missingPackets = 0
                self.shortPackets = 0
                self.lastReceivedFrames = 0
                self.lastRecoveredFrames = 0
                self.lastMissingPackets = 0
            }
            self.lastSession = session
            self.lastKey = key
            self.sealer = MediaSealer(key: key, session: session)
            self.activeGeneration = generation
            self.pendingSends = 0
            self.playbackBuffer.reset()
            self.lastDownlink = nil
            // A new stream starts with nothing to repeat: the first packet carries one frame.
            self.lastSentPcm = nil
            self.lastPcmSentAt = Date()
            self.recoveryRequested = false
            self.loggedPlaybackDrops = 0

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
            // cost. A measured backlog drains at catch-up pace so it cannot grow into the
            // overflow that lost speech in build 126; an empty queue waits for microphone data.
            let captureTimer = DispatchSource.makeTimerSource(queue: self.networkQueue)
            captureTimer.schedule(deadline: .now() + .milliseconds(20), leeway: .milliseconds(1))
            captureTimer.setEventHandler { [weak self, weak socket] in
                guard let self, let socket, self.connection === socket,
                      self.activeGeneration == generation else { return }
                let now = Self.monotonicNow()
                if self.pendingSends < self.maximumPendingSends {
                    self.captureLock.lock()
                    let frame = self.capturePacer.generation == generation
                        ? self.capturePacer.next(now: now) : nil
                    self.captureLock.unlock()
                    if let frame { self.sendPcm(frame) }
                }
                self.captureLock.lock()
                let wakeUp = self.capturePacer.nextWakeUp(now: now)
                self.captureLock.unlock()
                // While the socket is backpressured, poll a little instead of spinning on a due
                // deadline; otherwise fire at the pacer's next slot.
                let floor: TimeInterval = self.pendingSends >= self.maximumPendingSends ? 0.005 : 0.0005
                let delay = max(floor, wakeUp - now)
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
                // Every five seconds: what the capture produced against what actually left, so a
                // frame deficit (the Redmi's underruns) shows up as numbers instead of a guess.
                self.captureHealthTick()
                if self.playbackBuffer.droppedFrames > self.loggedPlaybackDrops {
                    self.loggedPlaybackDrops = self.playbackBuffer.droppedFrames
                    self.log("Downlink playback budget full: \(self.loggedPlaybackDrops) frame(s) dropped")
                }
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
            self.loggedPlaybackDrops = 0
        }
    }

    /// A route switch can invalidate the input tap without ending the CallKit call.
    private func requestRecovery(_ reason: String) {
        guard running, !isInterrupted else { return }
        let now = Date()
        guard now.timeIntervalSince(lastRecoveryRequest) > 1.5 else { return }
        lastRecoveryRequest = now
        log("Rebuilding call audio: \(reason) (flag=\(running), engine=\(engine.isRunning))")
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
        if !firstPcmLogged, let startedAt = engineStartedAt {
            firstPcmLogged = true
            log("Audio path: firstPcmOut=\(Int(Date().timeIntervalSince(startedAt) * 1000)) ms after start")
        }
        var pcm = Data(capacity: samples.count * 2)
        let muted = isMuted
        for sample in samples {
            // Muted frames keep flowing as silence so the Redmi still sees a live iPhone.
            let bits = UInt16(bitPattern: muted ? 0 : sample)
            pcm.append(UInt8(truncatingIfNeeded: bits))
            pcm.append(UInt8(truncatingIfNeeded: bits >> 8))
        }
        // Type 2 carries the frame before this one as well, so the Redmi can rebuild a frame that
        // was lost on the way. Type 1 stays the plain frame: an older Redmi never asks for the
        // copy, and the very first frame of a stream has nothing to repeat.
        if uplinkRedundancy, let previous = lastSentPcm, previous.count == pcm.count {
            var both = Data(capacity: previous.count + pcm.count)
            both.append(previous)
            both.append(pcm)
            if sendPacket(type: 2, pcm: both) {
                sentFrames += 1
                lastSentPcm = pcm
                lastPcmSentAt = Date()
            }
            return
        }
        if sendPacket(type: 1, pcm: pcm) {
            sentFrames += 1
            lastSentPcm = uplinkRedundancy ? pcm : nil
            lastPcmSentAt = Date()
        }
    }

    /// Every five seconds: the capture rate against the send rate, plus what the queue threw away.
    ///
    /// A produced/s below 50 means speech that never existed and no receiver buffer can restore it;
    /// a sent/s below produced/s points at backpressure or expiry, and the counters say which.
    private func captureHealthTick() {
        let now = Date()
        guard let last = lastCaptureHealthAt else {
            lastCaptureHealthAt = now
            return
        }
        let elapsed = now.timeIntervalSince(last)
        guard elapsed >= 5 else { return }
        lastCaptureHealthAt = now
        captureLock.lock()
        let produced = capturePacer.acceptedFrames
        let expired = capturePacer.droppedExpired
        let overflow = capturePacer.droppedOverflow
        captureLock.unlock()
        let producedPerSecond = Int(Double(produced - lastProducedFrames) / elapsed)
        let sentPerSecond = Int(Double(sentFrames - lastSentFrames) / elapsed)
        lastProducedFrames = produced
        lastSentFrames = sentFrames
        let receivedPerSecond = Int(Double(receivedFrames - lastReceivedFrames) / elapsed)
        let recoveredPerSecond = Int(Double(recoveredFrames - lastRecoveredFrames) / elapsed)
        let missingPerSecond = Int(Double(missingPackets - lastMissingPackets) / elapsed)
        lastReceivedFrames = receivedFrames
        lastRecoveredFrames = recoveredFrames
        lastMissingPackets = missingPackets
        log("CAPTURE_HEALTH produced=\(producedPerSecond)/s sent=\(sentPerSecond)/s " +
            "expired=\(expired) overflow=\(overflow) pending=\(pendingSends)")
        log("DOWNLINK_HEALTH maxGap=\(playbackBuffer.maxArrivalGapMs)ms " +
            "underruns=\(playbackBuffer.underruns) dropped=\(playbackBuffer.droppedFrames) " +
            "scheduled=\(playbackBuffer.scheduledFrames) rx=\(receivedPerSecond)/s " +
            "recovered=\(recoveredPerSecond)/s missing=\(missingPerSecond)/s " +
            "short=\(shortPackets)")
    }

    @discardableResult
    private func sendPacket(type: UInt8, pcm: Data) -> Bool {
        guard pendingSends < maximumPendingSends, let sealer, let socket = connection else { return false }
        txCounter &+= 1
        let hearing = lastDownlink.map { Date().timeIntervalSince($0) < 0.5 } ?? false
        guard let packet = sealer.seal(type: type, flags: hearing ? MediaSealer.flagHearing : 0,
                                       counter: txCounter, payload: pcm) else { return false }
        pendingSends += 1
        // A keepalive breaks the "the previous frame is in the last packet" chain. Without this the
        // Redmi would read the next type-2 copy as if the packet before it had carried PCM, and
        // replay a frame it already has (an audible 20 ms repeat).
        if type == 0 { lastSentPcm = nil }
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
            if let data, let sealer = self.sealer, let opened = sealer.open(data),
               opened.counter > self.rxLastCounter {
                let previous = self.rxLastCounter
                self.rxLastCounter = opened.counter
                if previous != 0 {
                    // Counter gaps are packets the network never delivered; the diagnostics need
                    // this number to tell loss from jitter.
                    self.missingPackets += Int(opened.counter &- previous &- 1)
                }
                switch opened.type {
                case 1:
                    guard opened.payload.count == self.pcmFrameBytes else {
                        self.shortPackets += 1
                        break
                    }
                    self.lastPcmRxCounter = opened.counter
                    self.receivedFrames += 1
                    self.enqueuePlayback(opened.payload)
                case 2:
                    // [previous frame][current frame]. The copy is new only when the packet before
                    // this one carried a frame that never arrived, exactly the Redmi's rule.
                    guard opened.payload.count == 2 * self.pcmFrameBytes else {
                        self.shortPackets += 1
                        break
                    }
                    if RedundantFramePolicy.copyIsNew(counter: opened.counter,
                                                      lastPcmCounter: self.lastPcmRxCounter) {
                        self.recoveredFrames += 1
                        self.enqueuePlayback(Data(opened.payload.prefix(self.pcmFrameBytes)))
                    }
                    self.lastPcmRxCounter = opened.counter
                    self.receivedFrames += 1
                    self.enqueuePlayback(Data(opened.payload.suffix(self.pcmFrameBytes)))
                default:
                    break
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
                if let next = self.playbackBuffer.completed(generation: generation) {
                    self.schedulePlayback(next)
                }
            }
        }
    }

    private func log(_ message: String) {
        DispatchQueue.main.async { [weak self] in self?.onLog?(message) }
    }
}
