# Acceptance matrix

Evidence labels:

- **CODE VERIFIED**: implemented and reviewed.
- **BUILD VERIFIED**: compiles in its real toolchain (Gradle, or Xcode on GitHub Actions).
- **RUNTIME LOG VERIFIED**: exercised on the real Redmi with the PC emulator and real IVR calls; logs kept.
- **PHYSICALLY VERIFIED**: done by a person with both phones.
- **BLOCKED BY PLATFORM**: iOS or Android does not allow it.
- **NOT VERIFIED**: not yet tested.

Builds: Android 2.0.1 (201), `delivery/phone-bridge`. iPhone 2.0, TestFlight build 47 (with push).
Physical tests on 27 Sep 2026, by the owner with both phones.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| P0 | 16 kHz baseline restored and later work kept | RUNTIME LOG VERIFIED | `baseline/16khz-uplink-20260927-1231`; IVR runs at 50 frames/s both ways |
| P1 | Full duplex, clear both ways | PHYSICALLY VERIFIED (protocol 1 and protocol 2) · RUNTIME LOG VERIFIED | Owner: "Hepsi doğru" (protocol 1) and "her şey çok güzel" (protocol 2, 19:22: moved to iPhone at 49/48 frames/s, manual return, moved back). Emulator: 50 frames/s both ways |
| P2 | One owner at a time; no Redmi speaker or microphone leak | PHYSICALLY VERIFIED (the far end did not hear music playing next to the Redmi) · RUNTIME LOG VERIFIED | `device_mute` tx/rx applied only after two-way verification |
| P3a | Automatic handoff for calls made from the iPhone, ringback on the iPhone | PHYSICALLY VERIFIED (test 2) | Ringback and busy tones heard on the iPhone |
| P3b | Fallback: iPhone microphone lost, iPhone not receiving, link lost | RUNTIME LOG VERIFIED | `iphone_audio_lost`, `iphone_not_receiving` (about 2 s), re-arm, `manual_redmi` |
| P3c | Fallback when the iPhone's Wi-Fi turns off during a call | NOT VERIFIED | Covered in the emulator (`iphone_audio_lost`, `iphone_not_receiving`); not done physically |
| P4a | Incoming call rings on a locked iPhone (app in background) | PHYSICALLY VERIFIED · RUNTIME LOG VERIFIED | Owner: "ekrana düştü". Log 19:38:24: BLE state sent, iPhone's `ringing_ack` over BLE 0.1 s later |
| P4b | Redmi rings quietly once the iPhone shows the call | RUNTIME LOG VERIFIED | "Incoming call is showing on the iPhone", after which the ringer is silenced |
| P4c | Incoming call after the person swiped the iPhone app away | PHYSICALLY VERIFIED (build 41, "Always" location permission) | iOS will not relaunch a swiped-away app for Bluetooth; the call wake iBeacon relaunches it. Owner: "çalıyor … kapalı olsa bile". Without the permission, only the Redmi rings |
| P4d | Redmi shows the iPhone as connected while it sleeps; a swiped-away app reconnects by itself | CODE VERIFIED · BUILD VERIFIED | Status counts the Bluetooth link and Apple push, not only the network socket; reconnect beacon pulses while no link exists. Physical test pending |
| P5 | Redmi as a standalone phone: recents, contacts, keypad, messages, call screens, active call always reachable | BUILD VERIFIED | Rebuilt phone screen: iPhone status on top, tab bar, round-key keypad, grouped recents with missed-call badge, contacts with photos and search, conversations with a composer. Physical test pending |
| P6 | iPhone: contacts with photos, favorites, keypad, full-screen call UI, SMS conversations with send status | BUILD VERIFIED · partly PHYSICALLY VERIFIED | The keypad, call screen and transfer were used in the tests; SMS sending not done physically |
| P7 | Missed calls and SMS survive iPhone absence (replay by sequence) | RUNTIME LOG VERIFIED | `sync` returns 60 calls and 4 SMS, then nothing after `lastSeq`; live `call_log` push with `answeredOn=iphone` |
| P8 | Reconnect after network or service restart | RUNTIME LOG VERIFIED (service restart, reinstall) | Physical network-change test pending |
| N1 | Redmi on Wi-Fi and its own hotspot at once (this morning's failure) | RUNTIME LOG VERIFIED | Root cause: UDP replies left from another Redmi address. Audio now binds to the control-link address; sealed IVR handoff passes over IPv4 and over a non-default IPv6 address |
| N2 | iPhone finds the Redmi on any network (office, cafe, PC hotspot) | CODE VERIFIED · BUILD VERIFIED | All known addresses raced; Bluetooth address list notified live; re-read on network change. Physical test pending |
| N3 | Calls over 4G (VoLTE) move to the iPhone | RUNTIME LOG VERIFIED | Wi-Fi Calling turned off for the test: `call_type=LTE`, handoff confirmed, 50 frames/s both ways; switch to WLAN mid-call did not break it |
| F1 | Notifications for new SMS and missed calls on the iPhone (app in background) | CODE VERIFIED · BUILD VERIFIED | Bluetooth state carries journal positions; the iPhone wakes, fetches and notifies. Physical test pending |
| F2 | Call waiting | CODE VERIFIED · BUILD VERIFIED | Shown on both phones; decline or end-and-answer. Needs two callers for a physical test |
| F3 | Calling from iOS Recents / Contacts | CODE VERIFIED · BUILD VERIFIED | `INStartCallIntent` dials through the Redmi, even before the link is up |
| R1 | Redmi at home, iPhone away: calls, SMS, missed calls | CODE VERIFIED · BUILD VERIFIED | Tailscale for the link; VoIP push for calls and alerts for SMS/missed calls, straight from the Redmi to Apple. Needs the owner's Tailscale login and APNs key |
| R2 | Remote link tolerance | RUNTIME LOG VERIFIED (local limits unchanged) | Wider limits and 3 retries only on VPN or non-local addresses; home Wi-Fi addresses stay local |
| S1 | No unauthenticated LAN control or audio injection | RUNTIME LOG VERIFIED | 10/10 security checks (`iphone_emulator.py --scenario security`) |
| S2 | No numbers, message text or keys in logs | CODE VERIFIED | Log audit; SMS logged by length only, numbers masked |
| S3 | No call recording or storage | CODE VERIFIED | No audio written to disk on either phone |
| S4 | Emergency calls untouched | CODE VERIFIED | The iPhone app hands emergency numbers to the iPhone dialer; the Redmi uses Telecom unchanged |
| S5 | Forget pairing on either phone | CODE VERIFIED | Redmi: Advanced › "iPhone eşleşmesini kaldır"; iPhone: Settings › "Eşleşmeyi kaldır" |
