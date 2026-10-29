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
            let changed = captureMute.setMuted(newValue, now: Self.monotonicNow())
            if changed { capturePacer.discardPending() }
            captureLock.unlock()
            // Logged after the lock: log() hops to the main queue and must never run under it.
            if changed { log("Audio path: microphone \(newValue ? "muted" : "unmuted")") }
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
    /// Repeat every uplink frame in the next packet; see setUplinkRedundancy.
    private var uplinkRedundancy = false
    private var lastSentPcm: Data?
    /// Capture health counters, all touched on the network queue except the pacer's own.
    private var sentFrames = 0
    private var lastProducedFrames = 0
    private var lastSentFrames = 0
    private var lastCaptureHealthAt: Date?
    /// Loudness of the sent microphone frames, reported with CAPTURE_HEALTH; network queue only.
    private var micLevel = MicLevel()
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
    /// The one start that is waiting for the input node to report a format; see start().
    private var formatWait: (host: String, port: UInt16, session: UInt32, key: Data, force: Bool)?
    /// Bounded wait for the engine's first render cycle before player.play(); see start().
    private var playerStartWait: DispatchWorkItem?
    /// True while a start waits for this iPhone's microphone; asking the Redmi again cannot help then.
    var isWaitingForInput: Bool { formatWait != nil }
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

    /// Prepares the stopped graph without changing the input unit. Voice processing stays in
    /// start(), after CallKit has activated the session, so warm-up cannot reconfigure the input
    /// before activation. Nothing captures audio here: the engine stays stopped, so there is no
    /// microphone use, no indicator and no CPU while the phone is idle.
    func warmUp() {
        guard !running else { return }
        // A start is waiting for the input format; touching the input node now is the churn that
        // kept it without one (see start()).
        guard formatWait == nil else {
            log("Audio path: warm-up skipped while a start waits for the microphone")
            return
        }
        let began = Date()
        _ = engine.inputNode
        engine.prepare()
        let ms = Int(Date().timeIntervalSince(began) * 1000)
        log("Audio path: warm-up done in \(ms) ms (voice processing deferred, engine stopped)")
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
        // Only one start may wait for the input format. On 25.10.2026 11:52 (and 22.10.2026 15:10)
        // every audio_ready, call event and retry began its own twenty-step wait; eleven ran at once,
        // each one first stopped the engine and switched voice processing on again, and the input
        // never reported a format for the rest of the call. A new request now hands its endpoint to
        // the start that is already waiting.
        if attempt == 1, let waiting = formatWait {
            formatWait = (host: host, port: port, session: session, key: key, force: force || waiting.force)
            log("Audio start already waiting for the microphone; endpoint updated (session=\(session))")
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
            let vpBegan = Date()
            do {
                // warmUp() prepares the graph, which initializes the input unit, and voice
                // processing cannot be switched on an initialized unit. The first call after every
                // launch got kAudioUnitErr_Initialized (-10849, 29.10.2026 01:27), ran without echo
                // cancellation, never rendered the player and only recovered after a 2 s wait and a
                // rebuild. Releasing what prepare() allocated first makes the first start do what
                // that working rebuild did. The node keeps voice processing on afterwards, so later
                // starts skip both steps (vp=0 ms in the 07:49 call).
                if !input.isVoiceProcessingEnabled {
                    engine.stop()
                    try input.setVoiceProcessingEnabled(true)
                }
            } catch {
                let vpError = error as NSError
                log("Voice processing could not be enabled: code=\(vpError.code) " +
                    "description=\(vpError.localizedDescription)")
            }
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
                    formatWait = (host: host, port: port, session: session, key: key, force: force)
                    if attempt == 6 || attempt == 13 { resetInputUnit(attempt: attempt) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
                        guard let self else { return }
                        guard let target = self.formatWait else {
                            self.log("Audio start wait ended: call audio was stopped meanwhile")
                            return
                        }
                        self.formatWait = nil
                        guard !self.running else {
                            self.log("Audio start wait ended: the engine is already running")
                            return
                        }
                        self.start(host: target.host, port: target.port, session: target.session, key: target.key,
                                   attempt: attempt + 1, force: target.force)
                    }
                } else {
                    formatWait = nil
                    log("Audio start gave up: the microphone reported no format in \(attempt) attempts")
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
            running = true
            startPlayerAfterFirstRender(generation: generation, vpMs: vpMs, startMs: startMs)
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
            // The first health tick of this start takes a fresh baseline (captureHealthTick);
            // stop() zeroed the pacer, so the old one gave a negative produced/s after a rebuild.
            self.lastCaptureHealthAt = nil
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

    /// Starting the player in the same turn as the engine was what aborted build 153 on every
    /// "iPhone'a al" (29.10.2026): play() raised "player did not see an IO cycle", an Objective-C
    /// exception Swift cannot catch. The node's first render time says an IO cycle has happened,
    /// and only then is play() safe. The wait is bounded and on the main queue; if no cycle comes,
    /// recovery rebuilds the graph instead of risking the exception.
    private func startPlayerAfterFirstRender(generation: UInt64, vpMs: Int, startMs: Int,
                                             attempt: Int = 0) {
        guard running, engine.isRunning else {
            playerStartWait = nil
            log("Audio player not started: the engine stopped while waiting for its first render")
            return
        }
        captureLock.lock()
        let currentGeneration = capturePacer.generation
        captureLock.unlock()
        guard currentGeneration == generation else {
            playerStartWait = nil
            log("Audio player not started: the call audio was rebuilt meanwhile")
            return
        }
        guard player.lastRenderTime != nil else {
            guard attempt < 200 else {
                playerStartWait = nil
                log("Audio player not started: no IO cycle within 2 s; asking recovery to rebuild")
                requestRecovery("the player saw no IO cycle")
                return
            }
            let work = DispatchWorkItem { [weak self] in
                self?.startPlayerAfterFirstRender(generation: generation, vpMs: vpMs,
                                                  startMs: startMs, attempt: attempt + 1)
            }
            playerStartWait = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.01, execute: work)
            return
        }
        playerStartWait = nil
        player.play()
        let vpOn = engine.inputNode.isVoiceProcessingEnabled ? 1 : 0
        log("Audio engine: vp=\(vpMs) ms vpOn=\(vpOn) start=\(startMs) ms render wait=\(attempt * 10) ms")
    }

    /// One deliberate reset of the input unit while it reports no format: a clean voice-processing
    /// unit instead of the state that repeated on/off switching left behind. Done twice per wait.
    private func resetInputUnit(attempt: Int) {
        log("Audio input still has no format at attempt \(attempt); resetting the input unit")
        // reset() alone keeps what warmUp()'s prepare() allocated, and voice processing cannot be
        // switched on an initialized unit (-10849); stop() releases it first.
        engine.stop()
        engine.reset()
        try? engine.inputNode.setVoiceProcessingEnabled(false)
        try? engine.inputNode.setVoiceProcessingEnabled(true)
        do {
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            log("Audio session could not be activated during the reset: \(error.localizedDescription)")
        }
    }

    /// Every call is on hold and CallKit took the session away: stops as start() does, keeping mute
    /// and speaker, and also drops a start still waiting for the microphone, which would otherwise
    /// bring the engine up again while nothing may play.
    func pause() {
        formatWait = nil
        stop(preservingControls: true)
    }

    func stop(preservingControls: Bool = false) {
        // start() calls this with preservingControls before it builds the graph; only a real stop
        // (the call ended, CallKit deactivated the session) cancels a start waiting for the format.
        if !preservingControls { formatWait = nil }
        playerStartWait?.cancel()
        playerStartWait = nil
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
        var frameSquares = 0.0
        var framePeak = 0
        for sample in samples {
            // Muted frames keep flowing as silence so the Redmi still sees a live iPhone.
            let value: Int16 = muted ? 0 : sample
            let bits = UInt16(bitPattern: value)
            pcm.append(UInt8(truncatingIfNeeded: bits))
            pcm.append(UInt8(truncatingIfNeeded: bits >> 8))
            let magnitude = abs(Int(value))
            if magnitude > framePeak { framePeak = magnitude }
            frameSquares += Double(value) * Double(value)
        }
        // Frames handed to the socket, muted ones as zeros, like the Redmi's uplink meter sees them.
        micLevel.add(squares: frameSquares, samples: samples.count, peak: framePeak)
        // Type 2 carries the frame before this one as well, so the Redmi can rebuild a frame that
        // was lost on the way. Type 1 stays the plain frame: an older Redmi never asks for the
        // copy, and the very first frame of a stream has nothing to repeat.
        if uplinkRedundancy, let previous = lastSentPcm, previous.count == pcm.count {
            var both = Data(capacity: previous.count + pcm.count)
            both.append(previous)
            both.append(pcm)
            if sendPacket(type: 2, pcm: both, muted: muted) {
                sentFrames += 1
                lastSentPcm = pcm
                lastPcmSentAt = Date()
            }
            return
        }
        if sendPacket(type: 1, pcm: pcm, muted: muted) {
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
            // A new start: every rate is measured from here, not from the previous run's counters.
            lastCaptureHealthAt = now
            captureLock.lock()
            lastProducedFrames = capturePacer.acceptedFrames
            captureLock.unlock()
            lastSentFrames = sentFrames
            lastReceivedFrames = receivedFrames
            lastRecoveredFrames = recoveredFrames
            lastMissingPackets = missingPackets
            micLevel = MicLevel()
            return
        }
        let elapsed = now.timeIntervalSince(last)
        guard elapsed >= 5 else { return }
        lastCaptureHealthAt = now
        captureLock.lock()
        let produced = capturePacer.acceptedFrames
        let expired = capturePacer.droppedExpired
        let overflow = capturePacer.droppedOverflow
        // Read under the lock already held here; self.isMuted would take it again and deadlock.
        let mutedNow = captureMute.isMuted
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
        let mic = micLevel.take()
        let mutedFlag = mutedNow ? 1 : 0
        log("CAPTURE_HEALTH produced=\(producedPerSecond)/s sent=\(sentPerSecond)/s " +
            "expired=\(expired) overflow=\(overflow) pending=\(pendingSends) \(mic) muted=\(mutedFlag)")
        log("DOWNLINK_HEALTH maxGap=\(playbackBuffer.maxArrivalGapMs)ms " +
            "underruns=\(playbackBuffer.underruns) dropped=\(playbackBuffer.droppedFrames) " +
            "scheduled=\(playbackBuffer.scheduledFrames) rx=\(receivedPerSecond)/s " +
            "recovered=\(recoveredPerSecond)/s missing=\(missingPerSecond)/s " +
            "short=\(shortPackets)")
    }

    @discardableResult
    private func sendPacket(type: UInt8, pcm: Data, muted: Bool? = nil) -> Bool {
        guard pendingSends < maximumPendingSends, let sealer, let socket = connection else { return false }
        txCounter &+= 1
        let hearing = lastDownlink.map { Date().timeIntervalSince($0) < 0.5 } ?? false
        var flags: UInt8 = hearing ? MediaSealer.flagHearing : 0
        // Tells the Redmi that a silent uplink is a mute, not a dead microphone. PCM frames pass the
        // state they were zeroed with, so a toggle cannot flag a frame the other way.
        if muted ?? isMuted { flags |= MediaSealer.flagMuted }
        guard let packet = sealer.seal(type: type, flags: flags,
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
                    // A current Redmi sends exactly one 20 ms frame. A Redmi that predates exact
                    // framing sends the HAL's ~19 ms read instead; playing that is still better
                    // than dropping the whole downlink, and `short=` in DOWNLINK_HEALTH shows the
                    // old gateway. Only a malformed payload is refused.
                    guard !opened.payload.isEmpty, opened.payload.count % 2 == 0,
                          opened.payload.count <= self.pcmFrameBytes else {
                        self.shortPackets += 1
                        break
                    }
                    if opened.payload.count != self.pcmFrameBytes { self.shortPackets += 1 }
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

/// Loudness of the sent microphone frames on the Redmi's PcmLevelMeter scale, so the iPhone's
/// CAPTURE_HEALTH and the Redmi's uplink level in RELAY_HEALTH can be compared directly: a frame
/// counts as speech when its own RMS is at least -50 dBFS, and silence reports -99.
struct MicLevel {
    private static let fullScale = 32768.0
    private static let speechGateDbfs = -50.0
    private var frames = 0
    private var activeFrames = 0
    private var activeSquares = 0.0
    private var activeSamples = 0
    private var peak = 0

    mutating func add(squares: Double, samples: Int, peak framePeak: Int) {
        guard samples > 0 else { return }
        frames += 1
        if framePeak > peak { peak = framePeak }
        if Self.dbfs((squares / Double(samples)).squareRoot()) >= Self.speechGateDbfs {
            activeFrames += 1
            activeSquares += squares
            activeSamples += samples
        }
    }

    /// The window as "micSpeechDbfs=.. micPeakDbfs=.. micActive=..%", then starts a new one.
    mutating func take() -> String {
        let speech = activeSamples > 0 ? Self.rounded(Self.dbfs((activeSquares / Double(activeSamples)).squareRoot())) : -99
        let peakDbfs = Self.rounded(Self.dbfs(Double(peak)))
        let active = frames > 0 ? activeFrames * 100 / frames : 0
        self = MicLevel()
        return "micSpeechDbfs=\(speech) micPeakDbfs=\(peakDbfs) micActive=\(active)%"
    }

    private static func dbfs(_ amplitude: Double) -> Double {
        amplitude > 0 ? 20 * log10(amplitude / fullScale) : -Double.infinity
    }

    private static func rounded(_ value: Double) -> Int {
        // Half up like Kotlin's Math.round, so both phones print the same number for the same level.
        value.isInfinite || value < -99 ? -99 : Int((value + 0.5).rounded(.down))
    }
}
