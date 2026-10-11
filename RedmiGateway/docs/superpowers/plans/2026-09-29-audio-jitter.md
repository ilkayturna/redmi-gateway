# Call audio jitter implementation plan

**Goal:** Reduce avoidable speech loss during bursty delivery and restore playback buffering after a short interruption.

**Architecture:** Keep the existing sealed UDP protocol and Redmi audio ownership. Extract bounded queue policies into platform-independent helpers that the real audio engines use. Use monotonic timing and session generations for asynchronous work.

**Tech stack:** Kotlin/JVM, Android AudioTrack, Swift, AVAudioEngine, GitHub macOS builds.

## Evidence and constraints

The connected Redmi logs show repeated queue overflow/underflow and actual `iphone_audio_lost` fallbacks. The source drops frames when ten 20 ms frames arrive together at an eight-frame queue; iOS does not restore its initial buffering after an ordinary playback drain. Current evidence does not isolate Wi-Fi scheduling from capture callback batching. No screen or navigation changes are needed.

- Preserve the 900 ms local ownership watchdog, authenticated counters and replay protection.
- Preserve the tested vendor routing, handset isolation and call fallback.
- Do not persist microphone or received audio; diagnostics contain aggregate counters only.
- Bound additional queue latency and discard stale backlog after a long stall.
- Separate build/model evidence from live human speech quality.

## Tasks

- [x] Android: extract the existing uplink policy into `UplinkJitterBuffer.kt`; test normal delivery, ten-frame/200 ms bursts, underflow re-prime, overflow and reset. Observe the burst regression fail before increasing bounded burst tolerance.
- [x] Android: integrate the tested buffer into `CallAudioRelay.kt`; report actual PCM versus inserted silence, authenticated arrival gaps and burst depth. Run `:app:testDebugUnitTest :app:assembleDebug`.
- [x] iOS: extract testable outgoing pacing and incoming buffering policy; cover coalesced callbacks, short drain, stale completion after restart, bounded backlog, and scheduler stalls with a standalone Swift harness.
- [x] iOS: execute the regression harness on macOS in its failing state, then integrate bounded 20 ms pacing and generation-safe playback re-priming. Keep packet nonces independent of engine restarts.
- [x] Review: independent reviewer checks helper behavior, lifecycle cleanup, mute timing, packet freshness, and unchanged fallback/security paths.
- [x] Verify and deliver: run Swift harness and complete simulator compilation, Android unit tests/build; check idle telephony state before any device update; preserve rollback APK. Record what still needs a real iPhone conversation.

## Review focus

1. A delayed timer must not send its entire backlog at once.
2. Old player completions must not reduce a new session's queue count.
3. Muting must take effect for already-buffered outgoing speech.
4. Both ordinary gaps and overflow must keep latency bounded without claiming silence as received speech.
5. Stop/start and same-session recovery must never reset a used encryption nonce counter.

## Delivery result (2026-10-01)

Android 2.0.2 / 202 installed and hash verified after 15 passing primary tests. iOS final code 3fb6d7c passed 14 regression groups, native compile, launch, signing and upload in run 36814956967. Apple VALID build 59 and existing internal group assignment confirmed in run 36815257360. The remaining external verification is installing build 59 on the owner's iPhone and making a real two-way conversation on the original shared Wi-Fi; these build/model checks do not prove human speech quality.
