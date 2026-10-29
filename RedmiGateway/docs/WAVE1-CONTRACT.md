# Wave 1 contract — resilience, diagnostics and native-feel fixes (29.10.2026)

This is the shared contract for the parallel work packages of wave 1. Every package codes against
it; a change to anything here is a contract change and goes through the coordinator.

## Why

The owner's iPhone has no cellular service until about the end of February 2027. The Redmi (SIM in
slot 2) stays in a pocket, screen off, and carries calls, SMS and internet for the iPhone. The owner:

- never wants to paste logs from the iPhone again: everything the iPhone can report must reach the
  Redmi, and the PC pulls it from the Redmi over wireless debugging;
- wants the setup to survive updates (ROM OTA, TestFlight expiry, app updates) or, where it cannot,
  to say so on the iPhone at once;
- wants the iPhone app to behave like Apple's Phone and Messages apps (links, text selection,
  notifications) and the Redmi to stay silent in the pocket.

## Ground rules (from AGENTS.md; read sections 0.4-0.6, 1.7-1.8, 2.1-2.3, 6.1-6.2 and 8.1)

- No device access, no `git push`, no GitHub API calls. Local commits on your own branch only.
- Audio-path files are off limits in wave 1 packages: `AudioEngine.swift`, `CallKitManager.swift`,
  `CallAudioTiming.swift`, `CallAudioRelay.kt`, `UplinkJitterBuffer.kt`, `GatewayInCallService.kt`.
  Reading them is fine. The coordinator does the audio changes in a separate build.
- Every `guard ... else { return }` / early return on a failure path logs why (AGENTS 6.1.2).
- Log on change, not per evaluation (AGENTS 8.1). No log line inside a 20 ms audio loop.
- Do not bump `versionCode`/`versionName` and do not edit `project.pbxproj`; list new Swift files in
  your final report and the coordinator registers them (AGENTS 2.2).
- iOS deployment target is 15.0 and the only device runs iOS 27. Use `if #available` for newer API
  and keep a working path for 15-16. There is no macOS here: Swift cannot be compiled locally, so
  write it carefully, prefer simple constructs, and put pure logic in Foundation-only files with a
  standalone swiftc test (pattern: `RedmiGateway/ios/Tests/CallAudioTimingTests.swift`).
- Android: JUnit4 + real org.json, pure helpers tested on the JVM. Run
  `gradlew.bat -p RedmiGateway\android :app:testDebugUnitTest` with
  `JAVA_HOME=C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\jdk\jdk-17.0.19+10` and
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\pb_no_unix_sockets`. Baseline: 380 tests green.
- No new privileged Android permission: the on-device allowlist cannot change without recovery and
  an unlisted privileged permission can stop the boot (`ro.control_privapp_permissions=enforce`).
- UI text is Turkish, matching the existing strings. Code comments are English, like the code.
- Commit messages: `type(scope): kisa turkce ozet` in ASCII (see `git log`), body optional, and end
  with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Write the message to a file and
  use `git commit -F` (AGENTS 1.1).

## Device facts (measured 29.10.2026)

- ROM /e/OS 4.4.1-a16 (LineageOS based, Android 16), `ro.build.type=user`, no root, no addon.d.
- DIALER role = `com.ilkay.redmigateway`; SMS role = `foundation.e.message`, whose notifications are
  already blocked (`importance=NONE userSet=true`), so it does not need silencing.
- Telecom's `MissedCallNotifierImpl` posts "Cevapsız çağrılar — 4 cevapsız çağrı" because the app
  has no `SHOW_MISSED_CALLS_NOTIFICATION` receiver, and re-posts them after a reboot.
- iPhone app is build 155; Redmi app 2.0.17 (217) as priv-app.

## Protocol additions (all optional, protocol stays 2)

Old peers keep working: Android answers an unknown type with `error unknown_command`, iOS ignores
unknown types, unknown fields are ignored on both sides.

### 1. `client_status` (iPhone -> Redmi) — more keys

Existing keys stay. New keys, all strings unless noted, all optional:

| key | example | source |
|---|---|---|
| `os` | `"27.0"` | `UIDevice.current.systemVersion` |
| `model` | `"iPhone16,2"` | `utsname.machine` |
| `buildDate` | `"2026-10-28T22:17:33Z"` | Info.plist `RGBuildDate` (written by CI) |
| `tfDaysLeft` | `88` (Int) | `90 - days since buildDate`, omitted when unknown |
| `callkit` | `"calls=1 audio=activated"` | CallKitManager read-only state |
| `audio` | `"engine=running muted=0 speaker=0 route=Receiver"` | AudioEngine read-only props + `AVAudioSession.currentRoute` |
| `notify` | `"authorized"` | Notifier authorization |
| `ble` | `"on connected=1 seen=12s"` | BLECentralManager |
| `network` | `"link=local wifi=1 viable=1"` | GatewayConnection |
| `location` | `"always"` | CallWakeMonitor authorization |

iOS also sends `client_status` on call start/end and on audio start/stop, not only every 60 s.

Android writes every key it receives: the old `STATUS_KEYS` first in their order, then the
remaining keys sorted; each value capped at 200 chars. It keeps the latest status JSON in memory with
the receive time and writes it atomically to `diagnostics/iphone-status.json`.

### 2. `client_diag` (iPhone -> Redmi) — large diagnostics in parts

```json
{"type":"client_diag","id":"<uuid>","kind":"metrickit-crash","createdAt":"2026-10-29T08:00:00Z",
 "part":0,"parts":3,"enc":"base64","data":"<base64 of at most 6000 raw bytes>"}
```

- `kind`: `metrickit-crash`, `metrickit-hang`, `metrickit-cpu`, `metrickit-disk`,
  `metrickit-metric`, `nsexception`, `exit-unclean`, `log-tail`.
- Size: 6000 raw bytes -> 8000 base64 chars -> about 8.3 KB inner JSON -> about 11.1 K sealed chars,
  safely under the Redmi's 16,384-char frame gate.
- Redmi validates `id` (`^[A-Za-z0-9-]{8,64}$`), `1 <= parts <= 64`, `0 <= part < parts`, stores
  each part, joins them when complete into `diagnostics/ios-diag/<id>.<kind>.json`, keeps the folder
  under 2 MB and 50 files (oldest deleted first), writes one summary line
  `iPhone-diag: id=... kind=... bytes=... parts=... stored` through `IphoneDiagnostics.record`,
  and replies `{"type":"ack","action":"client_diag","id":"...","part":N,"success":true}` (or
  `success:false,"error":"..."`). A repeated part overwrites (idempotent).
- iPhone keeps each payload in `Documents/diag-outbox/` until the ack of its last part; sends one part
  at a time and waits for its ack (10 s timeout); sends only while authenticated, on a local link
  (`!isRemoteLink`) and with no CallKit call; caps the outbox at 20 files / 2 MB (oldest dropped);
  stops for the rest of the connection after an `error unknown_command` for `client_diag`.

### 3. `client_log` hardening (iPhone only)

- Batch by size as well as count: the JSON-escaped lines of one batch stay under 9000 chars.
- A line longer than 3500 chars is split into `[i/n]` pieces before it enters the ring.
- Timestamps `HH:mm:ss.SSS`.
- A crash text (`last-crash.txt`) goes as a `client_diag` `nsexception`, not as one huge log line.
- Unsent lines survive a crash: lines not yet handed to the socket are kept in a small on-disk
  outbox and sent first after the next launch, tagged `[prev]`.

Android logs a dropped oversized frame (`iPhone-link: dropped oversized frame chars=N`), at most
once a minute, instead of returning silently.

### 4. `health` (Redmi -> iPhone)

```json
{"type":"health","at":1793104931960,
 "problems":[{"code":"vendorFix","audioCritical":true,"title":"...","detail":"...","fix":"..."}],
 "rom":{"build":"1792673413|...","previous":"...","changedAt":1793100000000},
 "battery":{"level":45,"charging":false},
 "updater":{"present":true,"enabled":false},
 "adbWifi":true}
```

- Android builds the snapshot in `HealthReporter` and publishes it through `server/HealthBus.kt`
  (already on main) with a change key made of the problem codes, the ROM build, the updater state and
  the battery band (`ok` > 20 %, `low` <= 20 %, `critical` <= 10 %, `charging`).
- Health is re-evaluated at service start, every 15 minutes, and on battery band changes.
- The WebSocket server sends `HealthBus.latest()` to every protocol-2 iPhone right after it
  authenticates, and forwards every `HealthBus` change to the connected ones.
- When an audio-critical problem appears (change key transition), Android also sends an APNs alert
  (`threadId "health"`, `collapseId "health"`, 24 h expiration) so an iPhone without a socket learns it.
- The Redmi's own health notification becomes silent (low importance): nobody looks at the Redmi.
- iPhone: decodes leniently (every field optional), keeps `redmiHealth`, shows a banner when an
  audio-critical problem exists or the Redmi battery is low and not charging, shows a "Redmi durumu"
  section in Settings, and posts one local notification per new audio-critical code set.

### 5. Media flags bit 1 (coordinator, audio build)

UDP header byte 9 bit 1 (`0x02`) = iPhone microphone muted. Android logs transitions and appends
`iphoneMuted=0|1` and the uplink level (`uplinkSpeechDbfs`, `uplinkPeakDbfs`, `uplinkActive`) to
`RELAY_HEALTH`. Coordinator only.

### 6. APNs expiration

`ApnsSender.post` gets an expiration parameter. VoIP / call-wake pushes keep `0`. SMS, missed-call
and health alerts use 24 hours, so a briefly offline iPhone still gets them.

## Work packages and file ownership

| Package | Owns |
|---|---|
| A-quiet-health (Android) | `AndroidManifest.xml`, `telecom/MissedCallsReceiver.kt` (new), `telecom/CallUiNotifier.kt`, `telecom/CallManager.kt` (only if needed), `service/HealthReporter.kt`, `service/HealthCheck.kt`, `service/UpdateWatch.kt`, `service/GatewayService.kt`, `service/BootReceiver.kt`, `push/ApnsSender.kt`, `push/PushNotifier.kt`, `sms/SmsJournal.kt`, `sms/SmsReceiver.kt`, `journal/CallJournal.kt` and their tests |
| A-diag-protocol (Android) | `server/GatewayWebSocketServer.kt`, `server/DiagnosticsReport.kt`, `journal/IphoneDiagnostics.kt`, `journal/IosDiagStore.kt` (new), `docs/PROTOCOL.md` and their tests |
| I-ui (iOS) | `MessagesView.swift`, `MessageText.swift` (new), `ContentView.swift`, `PhoneContactPicker.swift`, `HistoryStore.swift`, `Notifier.swift`; `GatewayConnection.swift` only in SMS/history/call functions, never in the telemetry section |
| I-telemetry (iOS) | `GatewayConnection.swift` telemetry section (`log`, `appendTelemetryLine`, `flushTelemetry`, `sendClientStatus`, `startTelemetry`, `stopTelemetry`, `LogFile`) plus `health` decoding, `GatewayMessage.swift`, `RedmiGatewayApp.swift`, `SettingsView.swift`, new `MetricKitReporter.swift`, `DiagnosticsFormat.swift`, `DiagnosticsOutbox.swift`, `RedmiHealthBanner.swift`, `ios/Tests/DiagnosticsFormatTests.swift` |
| C-ci-pc | `.github/workflows/*.yml`, `scripts/*.py`, `RedmiGateway/scripts/quality/*.ps1` (new `redmi-tanilama-cek.ps1`), `RedmiGateway/scripts/device/*` (read-only helpers only) |
| Coordinator | `HealthBus.kt`, audio files, `project.pbxproj`, version bumps, merging, the one-line banner hook in `ContentView.swift` |

Two packages must not edit the same file. If your fix needs a file you do not own, describe the
change in your report instead of making it.
