# Phone Bridge protocol (version 2)

The Redmi (Android gateway) and the iPhone talk only on the local network and over Bluetooth LE.
There is no cloud relay, and call audio is never stored.

| Channel | Transport | Carries |
|---|---|---|
| Control | WebSocket `ws://<redmi>:8787` | pairing, authentication, calls, SMS, journals, audio ownership |
| Media | UDP `<redmi>:8791` | 20 ms frames of 16 kHz mono PCM16, both directions |
| Wake-up | BLE GATT service `A67D0001-2056-427E-971D-3C9A6F4E8D10` | call state for a locked iPhone, answer/reject/hang up, current address |
| Discovery | Bonjour `_redmigateway._tcp` | address; TXT `id`, `v=2`, `ble`, `audio=relay_udp_pcm` |

The implementation is `server/GatewayWebSocketServer.kt`, `security/*`, `audio/CallAudioRelay.kt`
and `ble/BleServerManager.kt` on Android, and `GatewayConnection.swift`, `BridgeCrypto.swift`,
`AudioEngine.swift` and `BLECentralManager.swift` on iOS. `scripts/lab/bridge_client.py` is a
third implementation used for tests.

## Addresses

A Redmi can have several local addresses at once, for example its home Wi-Fi and its own
hotspot.

- `hello` lists them in `addresses`, and Bluetooth device info lists them in `ips`.
- The iPhone tries every known address at the same time: manual first, then Bluetooth, Bonjour,
  the Redmi's own list and the last working one. The first to answer wins and the other attempts
  end.
- The Redmi binds the call audio socket to the local address of the iPhone's control link. Audio
  replies therefore always leave from the address the iPhone sends to; the iPhone drops packets
  from any other address.

## Versions and compatibility

`hello` announces `protocol` (the Redmi's version) and `minProtocol` (the oldest client it accepts).

| Situation | Result on the iPhone |
|---|---|
| `minProtocol` > 2 | INCOMPATIBLE: "update the iPhone app" |
| `protocol` < 2 | INCOMPATIBLE: "update the Redmi app" |
| `protocol` = 2 | SUPPORTED |
| `protocol` > 2 and `minProtocol` ≤ 2 | DEGRADED: works, suggests updating the iPhone app |

The Redmi accepts protocol 1 clients (the pairing code sent in clear text, iPhone builds up to
1.0 (33)) **only until the first keyed pairing exists**. After that, a protocol 1 `auth` gets
`{"type":"auth_result","success":false,"code":"update_required"}`. Protocol 1 clients never
receive the `preparing_iphone` audio state, the journals or the `sms` push.

## Cryptography

All primitives are standard: HMAC-SHA256, AES-256-GCM (16-byte tag), PBKDF2-HMAC-SHA256.
`derive(K, label) = HMAC(K, utf8(label))`. `|` means byte concatenation. Base64 is standard with
padding.

### Pairing (once per iPhone)

The Redmi shows a 6-character code (alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`) under
Settings (the gear on the phone screen).

1. Redmi → `hello` with a fresh 16-byte `nonce` (nS).
2. iPhone → `{"type":"pair","clientId","clientName","nonce":nC,"proof"}` where
   `proof = HMAC(utf8(code), "pair" | nS | nC)`.
3. Redmi checks the proof in constant time. It creates a random 32-byte key K and stores it with
   the client id. It replies `{"type":"pair_result","success":true,"key","gatewayId","gatewayProof"}`,
   where `key = AES-GCM(PBKDF2(code, salt = nS | nC, 100 000 rounds), K, aad "pair")` in the form
   nonce‖ciphertext‖tag, and `gatewayProof = HMAC(K, "gw" | nC | nS)`.
4. The iPhone derives the wrapping key, opens K and checks `gatewayProof`. It stores K in the
   Keychain with `AfterFirstUnlockThisDeviceOnly`.
5. The Redmi rotates the code. A new pairing replaces the old one, and protocol 1 sessions are
   closed.

Failures: `wrong_code`, `malformed`, and `locked_out` (5 failures from one address → 60 s).

The code is never sent in protocol 2, so seeing a pairing exchange on the network does not reveal
K. Guessing the code online is bounded by the lockout.

**Migration from build 33:** the old app stored the code as its token. The new app finds it and
pairs with it automatically. If the Redmi's code has changed since, it asks the person once.

### Authentication (every connection)

1. Redmi → `hello` with a fresh nS.
2. iPhone → `{"type":"auth","protocol":2,"clientId","nonce":nC,"proof":HMAC(K,"auth"|nS|nC)}`.
3. Redmi → `{"type":"auth_result","success":true,"protocol":2,"gatewayProof":HMAC(K,"gw"|nC|nS)}`.
   Both sides now hold `S = HMAC(K, "session" | nS | nC)`.
4. The iPhone drops the connection if `gatewayProof` is wrong. A device that does not hold K
   cannot impersonate the Redmi.

Failures: `not_paired` or `bad_proof` (the iPhone forgets its pairing and asks for the code),
`locked_out`, `malformed`.

### Sealed WebSocket frames

After step 3, every frame in both directions is `{"t":"s","c":<counter>,"d":"<base64>"}`:

- `d = AES-GCM(derive(S,"ws"), json)`, with ciphertext and tag only.
- The nonce is the direction tag (`"C2S\0"` iPhone→Redmi, `"S2C\0"` Redmi→iPhone) followed by the
  counter as an 8-byte big-endian number.
- AAD is `"phonebridge-ws-2"`.
- Counters start at 1 and must strictly increase; a frame with a lower counter is dropped
  (replay).
- The Redmi seals and queues under one lock per connection, so frames leave in counter order.
- Unsealed frames after authentication are ignored.

### Frame size limits

Both limits apply to iPhone → Redmi frames and are checked on the raw frame before it is opened,
so they cannot be relaxed per message type.

- **16,384 chars: the inbound gate.** The Redmi drops a longer frame without opening it and
  without a reply. It writes `iPhone-link: dropped oversized frame chars=N` to
  `iphone-diagnostics.log`, at most once a minute; `suppressed=K` counts the drops in between,
  and drops still held back when a connection closes are written then as
  `iPhone-link: dropped oversized frames suppressed=K (not yet noted)`.
  A dropped frame does not advance the receive counter. With the sealed envelope
  (`21 + digits(c) + 4·ceil((P + 16) / 3)` chars) the inner JSON P can be about 12,250 UTF-8
  bytes. Senders stay well below that: a `client_log` batch keeps its escaped lines under 9000
  chars, and a `client_diag` part carries at most 4200 raw bytes (at most about 15.3 K sealed chars, because
  JSONSerialization escapes every `/` of the base64 as `\/`).
- **64 KB: the WebSocket frame limit.** Java-WebSocket refuses a frame above 64 KB (also one built
  from continuation fragments) and closes the connection with code **1009**. A control socket
  closed while the Redmi prepares the iPhone's audio returns the call audio to the Redmi, so an
  oversized frame must never be sent, least of all during a call.
- Redmi → iPhone frames have room: the iPhone accepts up to 1 MiB.

### Sealed media (UDP 8791)

```
[0..3] session id   [4..7] counter (uint32 BE)   [8] type   [9] flags   [10..] ciphertext‖tag
```

- The key is `derive(S, "media")`. The whole 10-byte header is the AAD.
- The nonce is `"UPLK"` (iPhone→Redmi) or `"DNLK"` (Redmi→iPhone), then the counter, then the
  session id.
- Type 0 is a keepalive (empty payload); type 1 is PCM (640 bytes).
- Flags from the iPhone: bit 0 (`0x01`) means "I am receiving downlink frames" (a valid frame
  arrived within the last 500 ms). Bit 1 (`0x02`) means "my microphone is muted"; a muted iPhone
  keeps sending frames of silence, so without this bit a mute looks like a dead microphone. The
  Redmi logs its transitions and adds `iphoneMuted=0|1` to `RELAY_HEALTH`; a Redmi without that
  build ignores it. Other bits are 0. The Redmi sends flags 0.
- **Counters belong to the session, not to a media start.** Both sides keep counting when audio
  restarts within the same session, so a nonce never repeats under one key. Receivers drop
  anything at or below the last counter.
- The Redmi also drops packets from any address other than the authenticated WebSocket client's.

Protocol 1 sessions use the old clear layout: `[0..3] session [4..5] seq [6] type [7] 0 [8..] PCM`.

### Bluetooth LE

The Bluetooth key is `derive(K, "ble")`. Sealed values are `0x02 | nonce | ciphertext | tag`.

- **State characteristic** (`…0003`, read and notify): JSON
  `{"s":state,"id":callId,"d":"i"|"o","q":seq,"t":epochMs,"n"?:number,"k":callsSeq,"m":smsSeq}`,
  AAD `"phonebridge-ble-state"`.
  - `k` and `m` are the newest journal sequences. The state is notified again whenever they
    change, so a sleeping iPhone wakes up for a new SMS or missed call, fetches it and posts a
    notification.
  - The iPhone accepts it only if `t` is within 120 s of its own clock and newer than the last one
    accepted. Unsealed state is ignored.
  - Continuation reads of a long value return the same bytes.
- **Control characteristic** (`…0004`, write with response and notify): the iPhone writes the
  sealed JSON `{"a":action,"id":callId,"q":seq,"t":epochMs}`, AAD `"phonebridge-ble-control"`.
  - Actions: `answer`, `reject`, `hangup`, `ringing_ack`.
  - The Redmi requires `q` above the last accepted value and `t` within 60 s. The iPhone uses
    milliseconds for `q`, so it keeps increasing across restarts.
  - The reply `{"ack","callId","seq","success","error"?}` is notified sealed the same way.
- **Device info** (`…0002`, read and notify): plain `{"id","ip","ips","port","v":2,"ble"}`, with
  no call data. `ips` lists every reachable local IPv4 address (Wi-Fi, the Redmi's own hotspot,
  tethering, VPN) and never includes mobile data. It is notified to every connected iPhone when
  the list changes.
- **Call wake beacon:** while an incoming call rings and the iPhone has not sent `ringing_ack`, the
  Redmi advertises a non-connectable iBeacon (Apple company id `0x004C`, UUID = first 16 bytes of
  `derive(K, "beacon")`, major 1, minor 1). The iPhone monitors it with Core Location, so iOS
  relaunches the app even after it was swiped away. The beacon carries no call data.
- **Reconnect pulse:** while a keyed pairing exists and the iPhone has neither a network link nor
  a Bluetooth link, the Redmi shows the same beacon for 10 s, 20 s after the link is lost and
  then every 3 minutes (every 10 minutes after the first half hour). A swiped-away app is
  relaunched by the region entry, reconnects over Bluetooth and sleeps again; the pulses stop as
  soon as a link exists. The pause lets iOS register the region as left before the next pulse.
- Call state notifications go to every connected central. A link can outlive a restart of the
  Redmi app, and the iPhone keeps listening on the same handle.
- Bluetooth never carries audio.

## Messages after authentication

### Commands (iPhone → Redmi)

| type | fields | reply |
|---|---|---|
| `get_state` | — | `state` |
| `dial` | `number` | `ack`; with one call in progress it becomes a second call and Telecom holds the first (2.0.12) |
| `hold_call` | `callId`, `hold` (bool) | `ack` |
| `swap_calls` | — | `ack`; the active call goes on hold and the held one resumes |
| `merge_calls` | — | `ack`; the network builds the conference, which can take ~20 s |
| `answer`, `reject`, `hangup` | `callId` | `ack` |
| `dtmf` | `digit`, `callId`? | `ack` |
| `ringing_ack` | `callId` | `ack`; the Redmi silences its ringer if the "ring quietly" setting is on |
| `audio_start` | — | `audio_ready {session, port, sampleRate, frameSamples, sealed}` or `error {code: no_call \| audio_busy}` |
| `audio_stop` | — | `ack` |
| `audio_to_redmi` | — | `ack` |
| `send_sms` | `recipient`, `message`, `requestId` | `sms_send_result {requestId, success, error?}` after the radio's result |
| `sync` | `callsAfter`, `smsAfter` | `sync_calls` then `sync_sms` |
| `diagnostics` | — | `diagnostics_result` (no numbers, message text or keys) |
| `unpair` | — | `ack`, then the Redmi closes the connection |
| `push_token` | `token` (hex), `environment` | — (PushKit token; the Redmi rings the iPhone through Apple when it has no link) |
| `push_alert_token` | `token` (hex), `environment` | — (ordinary notification token; SMS and missed-call alerts through Apple) |
| `client_log` | `appVersion`, `appBuild`, `dropped`, `lines` | — (see "iPhone telemetry") |
| `client_status` | `appVersion`, `appBuild`, … | — (see "iPhone telemetry") |
| `client_diag` | `id`, `kind`, `createdAt`, `part`, `parts`, `enc`, `data` | `ack {action: "client_diag", id, part, success, error?}` |

`audio_start` is accepted up to 10 s after this client's own `dial`, before Telecom has created the
call.

### Events (Redmi → iPhone)

- `call {state, callId?, number?, direction: incoming|outgoing}`. States: `ringing`, `dialing`,
  `connecting`, `active`, `holding`, `disconnecting`, `disconnected`, `idle`.
- `audio_state {owner: redmi|preparing_iphone|iphone, running, detail}`. The Redmi is the only
  authority on who carries the conversation; see AUDIO_ARCHITECTURE.md for the reasons.
- `call_log {item}` and `sms {item}` are live journal entries.
- `call_waiting {state: ringing|ended, callId, number?}` is a second incoming call during the
  current one. `answer` with its `callId` ends the current call and answers it; with
  `holdCurrent: true` (2.0.13, Bluetooth `"h": true`) it answers while Telecom puts the current call
  on hold, and the two calls then show in `calls` like an added call. `reject` or
  `hangup` with its `callId` declines it. When the current call ends, the waiting call becomes
  the current call under the same `callId`.
- `calls {primaryId, merging, items: [{callId, state, number, direction, conference, parentId, waiting}]}`
  (2.0.12, protocol 2 only) lists every call Telecom holds, sent before each `call` event and after
  authentication; an empty list means no call is left. `call` events still name only the primary
  call. A merge replaces the merged calls with a conference call that has a new `callId`; the
  children carry it in `parentId`. The Redmi waits 0.7 s (3 s while `merging`) before it reports
  the end of the last call, because a merge leaves no live call for a few milliseconds.
- `sync_calls` / `sync_sms` `{items, hasMore, lastSeq}`.
- `health` (protocol 2 only) is the Redmi's own state; see "Redmi health". It is sent right after
  authentication and again whenever it changes.

### Journals and replay

The Redmi mirrors its CallLog and SMS provider into two SQLite journals.

- Every new row, or change such as `sending → sent` or read state, gets the next sequence number.
- Call items: `{seq, id, number, type, date, duration, answeredOn: iphone|redmi|null}`.
  `type` is one of `incoming`, `outgoing`, `missed`, `declined`, `blocked`, `voicemail`,
  `answered_elsewhere`, `other`.
- SMS items: `{seq, id, address, body, date, box: inbox|sent|sending|failed|other, read}`.
  - An SMS that no SMS app stored is kept with a negative synthetic id.

After every authentication the iPhone asks for `sync` with the last sequence numbers it stored,
and repeats while `hasMore`. Nothing that happened while the iPhone was away is lost.

- Live pushes only advance the stored position when they are contiguous; anything else is picked
  up by the next sync and merged by id.
- The first import on the Redmi keeps 30 days of history and is not announced as new.

### iPhone telemetry (iPhone → Redmi)

The iPhone has no USB debugging, so it reports about itself over the sealed socket. Everything
lands in the Redmi's `Android/data/com.ilkay.redmigateway/files/diagnostics/` folder, next to
`call-diagnostics.log`, and is pulled from there with adb. All of it is sent only while the
iPhone is authenticated.

**`client_log`** `{appVersion, appBuild, dropped, lines: [string]}` — the iPhone's own log lines.
The Redmi writes each line to `iphone-diagnostics.log` as `MM-dd HH:mm:ss.SSS iPhone[<app>/<build>]: <line>`
(Redmi receive time; at most 200 lines per frame, each cut at 4000 chars) and, when
`dropped` > 0, one `telemetry dropped N lines before this batch` line. The file rotates once at
512 KB to `iphone-diagnostics.1.log`. No reply.
- The iPhone keeps a batch's JSON-escaped lines under 9000 chars, splits a line longer than
  3500 chars into `[i/n]` pieces, stamps lines `HH:mm:ss.SSS` in iPhone time, and tags lines kept
  over a crash with `[prev]`.

**`client_status`** — a snapshot of the iPhone's state, sent on authentication, every 60 s, on
foreground and background changes, and on call and audio start and stop. Every key is optional;
strings unless noted.

| key | example | meaning |
|---|---|---|
| `appVersion`, `appBuild` | `"2.0"`, `"155"` | the iPhone app |
| `appState` | `"active"` | `active`, `inactive`, `background` |
| `mic` | `"granted"` | microphone permission |
| `battery` | `"80% charging"` | the iPhone's battery |
| `thermal` | `"nominal"` | thermal state |
| `lowPower` | `false` (bool) | Low Power Mode |
| `callkit` | `"calls=1 audio=activated"` | CallKit state |
| `audio` | `"engine=running muted=0 speaker=0 route=Receiver"` | audio engine and route |
| `notify` | `"authorized"` | notification permission |
| `ble` | `"on connected=1 seen=12s"` | Bluetooth link to the Redmi |
| `network` | `"link=local wifi=1 viable=1"` | the socket's path |
| `os` | `"27.0"` | iOS version |
| `model` | `"iPhone16,2"` | hardware model |
| `buildDate` | `"2026-10-28T22:17:33Z"` | when CI built the app |
| `tfDaysLeft` | `88` (int) | days until the TestFlight build expires; absent when unknown |
| `location` | `"always"` | location permission (call wake beacon) |

Build 155 sends only the first seven keys (up to `lowPower`). The Redmi keeps **every** key it
receives except `type`, so a new key needs no Redmi update:
- one `iPhone-status` line: the keys above from `appVersion` to `network` first, in the old order
  `appVersion appBuild appState callkit audio mic notify ble battery thermal lowPower network`,
  then all other keys sorted, as `key=value` separated by spaces. Each value is cut at 200 chars;
  control characters become spaces; null and blank values are left out. A line from build 155
  reads exactly as before.
- Key names: at most 64 keys per frame (the rest are dropped), each name cut to 40 chars, and every
  character outside `[A-Za-z0-9_.-]` replaced with `_`. Two keys that become the same name keep
  only the first. The Redmi says so in logcat (`client_status keys adjusted: ...`), not in the file;
  keys that already fit these rules are never touched.
- `iphone-status.json`, replaced atomically on every snapshot:
  `{"receivedAt": <ms>, "receivedAtText": "yyyy-MM-dd HH:mm:ss.SSS +zzzz", "status": {...}}`.
- the latest snapshot in memory, shown as `iphone {status, receivedAt, ageMs}` in
  `diagnostics_result`.
- No reply.

**`client_diag`** — a large report (MetricKit, crash text, a log tail) in parts, because one frame
cannot exceed the 16,384-char gate:

```json
{"type":"client_diag","id":"<uuid>","kind":"metrickit-crash","createdAt":"2026-10-29T08:00:00Z",
 "part":0,"parts":3,"enc":"base64","data":"<base64 of at most 4200 raw bytes>"}
```

- `kind`: `metrickit-crash`, `metrickit-hang`, `metrickit-cpu`, `metrickit-disk`,
  `metrickit-metric`, `nsexception`, `exit-unclean`, `log-tail`. The Redmi also stores a newer kind
  that matches `^[a-z][a-z0-9-]{0,31}$`.
- The Redmi checks `id` against `^[A-Za-z0-9-]{8,64}$`, `1 ≤ parts ≤ 64`, `0 ≤ part < parts`,
  `enc` = `base64` (the default) and 1 to 8192 decoded bytes per part: a report is cut into
  `ceil(size / 4200)` parts, so a part with no data (empty or missing `data`) is refused as
  `bad_data` rather than acked, and an empty report is not sent at all. It writes each part to
  disk (flushed to the storage device), then replies
  `{"type":"ack","action":"client_diag","id","part","success":true}`. A repeated
  part overwrites the earlier copy, and a part of a report that is already joined is acked again
  without writing.
- When every part is present, the Redmi joins the decoded bytes in part order into
  `ios-diag/<id>.<kind>.json` (atomically) and writes one line
  `iPhone-diag: id=... kind=... bytes=... parts=... stored` to `iphone-diagnostics.log`.
- `ios-diag/` stays within 50 entries and 2 MB, the oldest deleted first; the parts of a report
  that was never finished count as one entry and are deleted after 7 days.
- Failures reply `success:false` with `error`: `bad_id`, `bad_kind`, `bad_parts`, `bad_part`,
  `bad_enc`, `bad_data`, `too_large`, `missing_parts` (the last part arrived but earlier ones are
  not on the Redmi; send the report again from part 0), `io_error`, `no_storage`, `busy`.
  `missing_parts`, `io_error`, `no_storage` and `busy` depend on the Redmi's state and can succeed
  on a later attempt; the others are about the frame itself and fail the same way every time it is
  sent unchanged.
- Every failure goes to logcat. The first failure of each error for an id is also written to
  `iphone-diagnostics.log` as `iPhone-diag: rejected id=... part=... error=...` (characters outside
  `[A-Za-z0-9-]` in the id shown as `?`); the Redmi remembers the last 32 id/error pairs, so a
  report the iPhone resends on every connection adds one line, not one per attempt.
- The iPhone keeps each report in `Documents/diag-outbox/` until the ack of its last part. It
  sends one part at a time and waits up to 10 s for its ack, only while authenticated, on a local
  link and with no CallKit call. The outbox holds at most 20 files and 2 MB, oldest dropped. After
  `error {code: unknown_command, command: "client_diag"}` (an older Redmi) it stops for the rest of
  the connection.

### Redmi health (Redmi → iPhone)

The Redmi stays in a pocket, so its problems are told to the iPhone:

```json
{"type":"health","at":1793104931960,
 "problems":[{"code":"vendorFix","audioCritical":true,"title":"...","detail":"...","fix":"..."}],
 "rom":{"build":"1792673413|...","previous":"...","changedAt":1793100000000},
 "battery":{"level":45,"charging":false},
 "updater":{"present":true,"enabled":false},
 "adbWifi":true}
```

- Problem codes are stable (`vendorFix`, `privApp`, `privilegedPermissions`, `defaultDialer`, …);
  `title`, `detail` and `fix` are Turkish text for the person.
- The Redmi re-evaluates its health when the service starts, every 15 minutes and when the battery
  band changes (`ok` above 20 %, `low` at 20 % or less, `critical` at 10 % or less, `charging`).
- It sends the latest snapshot to every protocol 2 iPhone right after authentication, and each
  change to the iPhones connected at the time. A change is a different set of problem codes, ROM
  build, updater state or battery band; a battery level that moves within its band is not one.
  The last `health` an iPhone receives on a connection is always the newest snapshot, also when a
  change arrives during its authentication; the same snapshot is not sent twice on one connection.
- `diagnostics_result` carries the same snapshot as `health`.
- When an audio-critical problem appears, the Redmi also sends an APNs alert (`open: "health"`,
  see "Alert payload"), so an iPhone without a socket learns it.
- Every field is optional; an iPhone decodes it leniently. An older iPhone ignores the type.

## Away from home (Apple push)

When the iPhone is not near the Redmi, the network link runs through Tailscale; the Redmi
announces its VPN address with the others. Wake-ups come through Apple:

- **Incoming call.** If the iPhone has not confirmed within 1.5 s that it shows the call, the
  Redmi sends a VoIP push (`apns-push-type: voip`, topic `<bundle>.voip`, expiration 0).
  - Its payload is `{"p": base64(AES-GCM(derive(K,"push"), {"id","n","t"}, aad "phonebridge-push"))}`,
    so Apple sees only ciphertext.
  - iOS launches the app even after it was swiped away, and the app reports the call to CallKit
    before returning.
- **SMS and missed calls.** If no connected iPhone received a new journal entry within 8 s, the
  Redmi sends an ordinary alert. The SMS text is included unless the owner turns that off.
  An audio-critical health problem is sent the same way.
- **Expiration.** VoIP pushes use `apns-expiration: 0` (a late call is worse than none). SMS,
  missed-call and health alerts expire after 24 hours, so a briefly offline iPhone still gets them.
- **Credentials.** The Redmi talks to `api.push.apple.com` directly with the owner's APNs key
  (`.p8`, imported on its Advanced screen), signing ES256 provider tokens. No server of ours is
  involved.

### Alert payload

```json
{"aps":{"alert":{"title":"...","body":"..."},"sound":"default","thread-id":"sms-5321234567",
        "category":"SMS"},
 "open":"sms",
 "p":"<base64 BridgeCrypto.seal(derive(K,\"push\"), {\"address\":\"...\",\"gid\":\"<journal id>\"}, \"phonebridge-push\")>"}
```

- `open` (plain text) says which screen to open: `sms`, `recents` or `health`.
- `p` is sealed exactly like the VoIP payload (same key derivation and AAD):
  `{"address","gid"}` for an SMS, `{"number","logId"}` for a missed call, absent for health. The
  phone number never travels in plain text inside Apple's payload.
- `aps.category`: `SMS` (actions: reply with text input, mark read), `MISSED` (call back, send a
  message), `HEALTH` (none). The iPhone registers these categories at launch.
- `aps.thread-id`: `sms-<key>` for an SMS, `missed` for a missed call, `health` for health.
  `<key>` is the iPhone's `PhoneIdentity.key(address)`: no digits → trimmed and uppercased;
  12 digits starting `90` or 11 digits starting `0` → the last 10 digits; otherwise all digits.
  Both sides test the same vectors: `+90 532 123 45 67` → `5321234567`, `05321234567` →
  `5321234567`, `5321234567` → `5321234567`, `TURKCELL` → `TURKCELL`, ` Garanti BBVA ` →
  `GARANTI BBVA`, `+44 20 7946 0958` → `442079460958`.
- Health alerts use `collapseId` `health`, so a newer one replaces the older one.
- `aps.badge` is never set by the Redmi; the iPhone keeps its own badge.
- A journal entry the Redmi alerted for carries `"pushed": true` in `sms {item}`, `call_log {item}`
  and the sync items. The iPhone stores it without a second sound or banner.

## Error codes

`unauthorized` (a command before authentication), `unknown_command`, `internal_error`,
`no_call`, `audio_busy`, `lab_disabled`.

New message types and fields are added without a protocol bump. An iPhone → Redmi type the Redmi
does not know is answered with `{"type":"error","code":"unknown_command","command":<type>}`; a
Redmi → iPhone type the iPhone does not know is ignored, and both sides ignore unknown fields.

Lab measurement hooks (`lab`) only work after
`adb shell am broadcast -a com.ilkay.redmigateway.LAB_MODE --ez enable true -n com.ilkay.redmigateway/.audio.LabModeReceiver`.
That broadcast needs the adb shell's DUMP permission, and lab mode expires after 2 hours.
