# Acceptance matrix

Evidence labels:

- **CODE VERIFIED**: implemented and reviewed.
- **BUILD VERIFIED**: compiles in its real toolchain (Gradle, or Xcode on GitHub Actions).
- **RUNTIME LOG VERIFIED**: exercised on the real Redmi with the PC emulator and real IVR calls; logs kept.
- **PHYSICALLY VERIFIED**: done by a person with both phones.
- **BLOCKED BY PLATFORM**: iOS or Android does not allow it.
- **NOT VERIFIED**: not yet tested.

Builds: Android 2.0.0 (200), `delivery/phone-bridge`. iPhone 2.0, TestFlight build 37.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| P0 | 16 kHz baseline restored and later work kept | RUNTIME LOG VERIFIED | `baseline/16khz-uplink-20260927-1231`; IVR runs at 50 frames/s both ways |
| P1 | Full duplex, clear both ways | PHYSICALLY VERIFIED (protocol 1, test 2) · RUNTIME LOG VERIFIED (protocol 2, sealed) | Owner: "Hepsi doğru". Emulator: downlink 50/s, uplink 48–51/s |
| P2 | One owner at a time; no Redmi speaker or microphone leak | PHYSICALLY VERIFIED (the far end did not hear music playing next to the Redmi) · RUNTIME LOG VERIFIED | `device_mute` tx/rx applied only after two-way verification |
| P3a | Automatic handoff for calls made from the iPhone, ringback on the iPhone | PHYSICALLY VERIFIED (test 2) | Ringback and busy tones heard on the iPhone |
| P3b | Fallback: iPhone microphone lost, iPhone not receiving, link lost | RUNTIME LOG VERIFIED | `iphone_audio_lost`, `iphone_not_receiving` (about 2 s), re-arm, `manual_redmi` |
| P3c | Fallback when the iPhone's Wi-Fi turns off during a call | NOT VERIFIED | Physical test pending |
| P4a | Incoming call rings on a locked iPhone (app in background) | CODE VERIFIED · BUILD VERIFIED | BLE wake, then CallKit, then `ringing_ack`; physical test pending |
| P4b | Redmi rings quietly once the iPhone shows the call | CODE VERIFIED | `IncomingCallPresenter` silences the ringer; physical test pending |
| P4c | Incoming call after the person force-quit the iPhone app | BLOCKED BY PLATFORM | iOS does not relaunch a force-quit app for Bluetooth; the Redmi still rings |
| P5 | Redmi as a standalone phone: recents, contacts, keypad, messages, call screens, active call always reachable | BUILD VERIFIED | Call card and notification lead to the call screen; physical test pending |
| P6 | iPhone: contacts with photos, favorites, keypad, full-screen call UI, SMS conversations with send status | BUILD VERIFIED | Physical test pending |
| P7 | Missed calls and SMS survive iPhone absence (replay by sequence) | RUNTIME LOG VERIFIED | `sync` returns 60 calls and 4 SMS, then nothing after `lastSeq`; live `call_log` push with `answeredOn=iphone` |
| P8 | Reconnect after network or service restart | RUNTIME LOG VERIFIED (service restart, reinstall) | Physical network-change test pending |
| S1 | No unauthenticated LAN control or audio injection | RUNTIME LOG VERIFIED | 10/10 security checks (`iphone_emulator.py --scenario security`) |
| S2 | No numbers, message text or keys in logs | CODE VERIFIED | Log audit; SMS logged by length only, numbers masked |
| S3 | No call recording or storage | CODE VERIFIED | No audio written to disk on either phone |
| S4 | Emergency calls untouched | CODE VERIFIED | The iPhone app hands emergency numbers to the iPhone dialer; the Redmi uses Telecom unchanged |
| S5 | Forget pairing on either phone | CODE VERIFIED | Redmi: Advanced › "iPhone eşleşmesini kaldır"; iPhone: Settings › "Eşleşmeyi kaldır" |
