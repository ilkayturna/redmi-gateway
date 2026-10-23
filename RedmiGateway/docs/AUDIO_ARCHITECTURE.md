# Call audio architecture

This page explains how the conversation of a cellular call on the Redmi reaches the iPhone and
why this device needs this audio route. The routing and vendor conclusions below come from
earlier real-device measurements using automated calls to the Turkcell 532 IVR (`scripts/lab`).
The timing and mute changes dated 2026-10-01 have regression-model, build and Android installation
evidence described below. Those checks do not establish human conversation quality on the
computer's shared Wi-Fi; that comparison still needs a real two-way call.

## The device, as it really is

- Redmi Note 8 (ginkgo), Qualcomm SM6125, running an **Android 16 community ROM** (SDK 36), not
  MIUI. `adb shell` is root, `/vendor` is writable, and SELinux is enforcing.
- The gateway is a privileged system app (`/system/priv-app/RedmiGateway`) holding
  `CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT` and `MODIFY_PHONE_STATE`.
- The SIM is in slot 2, so the HAL uses the `VoiceMMode2` session (vsid `0x11DC5000`). Calls are
  usually VoWiFi.

## The path that works

```
cell network ─▶ modem ─▶ [downlink tap: incall-rec-downlink] ─▶ AudioRecord ─▶ seal ─▶ UDP ─▶ iPhone speaker
iPhone mic ─▶ UDP ─▶ open ─▶ AudioTrack (USAGE_MEDIA → TELEPHONY) ─▶ [INCALL_MUSIC uplink2] ─▶ modem ─▶ network
```

| Part | How | Why this and not something else |
|---|---|---|
| Downlink | `AudioManager.getCallDownlinkExtractionAudioRecord` at 16 kHz mono | The HAL serves it from `incall-rec-downlink`, which only returns real audio while the call stays on the handset path. |
| Uplink | A 48 kHz stereo `AudioTrack` with `USAGE_MEDIA`/`CONTENT_TYPE_SPEECH` and `setPreferredDevice(TYPE_TELEPHONY)`, fed by a 3× polyphase upsampler (`Upsampler3x.kt`) | The track lands on the INCALL_MUSIC output (`incall_music_uplink2`), which mixes into the uplink without moving the call off the handset path. |
| Vendor fix | `scripts/device/vendor_audio_fix.py` maps `USECASE_INCALL_MUSIC_UPLINK2` to PCM 27 and uses the `Incall_Music_2 Audio Mixer MultiMedia9` path | Without it, `pcm_prepare` fails and the uplink sounds like the old "helicopter" audio. It is reversible (`status`, `apply` and `revert`, backups in `/vendor/etc/phonebridge-backup`) and was applied with the owner's approval. Diagnostics show whether it is present. |
| Isolation | `setParameters("device_mute=true;direction=tx")` and `direction=rx` | This mutes the Redmi's own microphone and earpiece while the iPhone owns the call, without touching the stream the iPhone feeds or reads. |
| Android uplink pacing | Blocking writes pace 20 ms frames. `UplinkJitterBuffer` primes 12 frames (240 ms) on the first start locally, 16 (320 ms) remotely, then 4–24 frames (80–480 ms) depending on the measured target; it holds at most 32 frames (640 ms) locally or 48 remotely, trims one frame when depth stays two frames above the adaptive target for more than 500 ms, and discards PCM more than 800 ms old (1,100 ms remote) before playback. The target ceiling is 24 frames (480 ms) locally and 36 (720 ms) remotely; the target is the worst recent need over a ten second window. | Build 123 measured a 439 ms gap followed by a 26-frame burst on the iPhone→Redmi path and dropped 34 frames at the old 240 ms ceiling. Build 126 underran 9–28 times because the first prime (4 frames) happened before the adaptive target had seen the 160–206 ms opening gaps; the startup prime now covers them. The target uses the gap plus two frames of slack and a burst plus one; ten seconds keep the reserve through the repeat pattern. The hard queue and age limits are memory/watchdog guards above the target, not the latency policy. The cost is up to about 0.5 s of walkie-talkie delay while the link is this bad. See `RELAY_HEALTH` and diagnostics. |

## iPhone timing and capture privacy (2026-10-01)

The network format remains 20 ms of 16 kHz mono PCM16. Timing helpers in
`ios/RedmiGateway/CallAudioTiming.swift` operate on monotonic timestamps and session generations;
`AudioEngine` supplies the real capture, network and playback events.

- **Capture and send:** the tap converts to 16 kHz and deposits complete frames into a bounded
  capture queue under a lock. A one-shot timer sends at most one frame per deadline, after
  priming 3 frames. Capacity is 24 frames; captured PCM older than 480 ms is discarded. The tap
  stamps capture time before conversion or dispatch, using the hardware host timestamp when
  available, and accounts for each frame's position within a batch. Queueing a delayed callback
  therefore does not make old microphone samples fresh.
- **Timer phase:** lateness below 2 ms preserves the nominal 20 ms phase. A late wake with only
  the working reserve advances to the next future slot instead of restarting a full interval from
  actual time; restarting sent 48 frames/s while the microphone produced 50/s, and the surplus
  became the measured `overflow=20`. When more than eight frames (160 ms) are queued, the pacer
  drains at 5 ms catch-up slots, one packet per wake, until the reserve is back to normal.
  Backpressure bounds outstanding sends to 4; an empty or blocked queue schedules another future
  wake-up rather than spinning on an expired deadline.
- **Playback:** 16 received frames (320 ms) are scheduled initially and the player is kept at that
  depth for the whole call: scheduling every received frame immediately let the player drain at the
  measured 263 ms arrival gaps (build 126: underruns 2, 5, 7 and 10 in one 30 s call). Every
  `.dataPlayedBack` completion refills the reserve from the receive queue. At most 26 frames
  (520 ms) may be outstanding, covering the 26-frame burst measured on the reverse path. A real
  underrun (three frame periods with no scheduled buffer and a gap above 60 ms) re-primes with a
  reserve that covers the gap that caused it, capped at 26 frames; a receive gap above 500 ms
  clears unscheduled reserve and rebuilds the same gap-sized reserve. `DOWNLINK_HEALTH` reports the
  largest arrival gap, underruns, dropped frames and scheduled depth. Session generations prevent
  old completions or receives from entering the following call.
- **Mute privacy:** a mute transition clears pending capture and increments a mute generation.
  The tap's generation and captured-while-muted snapshot, together with the latest transition's
  monotonic timestamp, mask delayed or partially accumulated PCM from before or across unmute.
  Muted frames continue as zero PCM to preserve the live audio transport. Send-time mute is an
  additional check; it cannot by itself protect samples captured earlier and delivered later.

Android ownership still measures complete successful track writes, including substituted
silence, without changing its fallback thresholds. Diagnostics separately report received PCM
written in full, inserted silence and incomplete writes. Received PCM may naturally contain
silent samples; these counters do not measure whether someone is speaking. Authenticated
full-PCM arrival metrics record maximum gap and maximum burst length (successive arrivals within
5 ms). Drop, expired-frame and underrun counters survive a stop/fallback for inspection and reset
at the next media start. No audio content is added to these logs.

## Measured failures (do not retry)

| Approach | What happened |
|---|---|
| `getCallUplinkInjectionAudioTrack` (the "Telephony Tx" helper) | It routes to `afe-proxy-playback`. Any routing event then moves the call to `voicemmode2-call afe-proxy`, where the downlink tap reads zeros. |
| `AudioManager.MODE_CALL_REDIRECT` | It maps to call-proxy paths that do not exist on this device, and the relay disarms. |
| INCALL_MUSIC before the vendor fix | `pcm_prepare -1` produced broken, periodic uplink audio. |
| Telecom route toggles while the iPhone owns the call | These are routing events: the call leaves the handset path and the downlink dies. |
| Global microphone mute (`setMicrophoneMute`) | It also silences the in-call music mix, which kills the uplink. |
| Guessing sample rates | Downlink and uplink rates are fixed and asserted: 16 kHz for extraction and the network, 48 kHz stereo for the track. |

## Ownership: one authority, verified both ways

The Redmi's `CallAudioRelay` is the only authority on who carries the conversation. It is a state
machine: `REDMI → PREPARING_IPHONE → IPHONE`.

1. **PREPARING_IPHONE**: `audio_start` arms a session. Media starts only once the call is live and
   the iPhone sends at least 38 frames per second.
2. **Verification**: the Redmi then waits for one full window of 1.2 s, and moves ownership only if
   all of these hold:
   - the uplink writes at least 38 frames/s;
   - the downlink reads at least 38 frames/s;
   - iPhone frames are fresh;
   - for protocol 2, the iPhone's "hearing" flag says the downlink is arriving there.

   Only then does it apply isolation and announce `owner=iphone`.
3. **Fallback** (`owner=redmi`), which always clears isolation first so the Redmi's earpiece and
   microphone come back, happens when:
   - iPhone audio stops for 900 ms (`iphone_audio_lost`);
   - the iPhone reports for 2 s that it is not receiving (`iphone_not_receiving`);
   - the uplink or downlink rate stays low for **two** consecutive windows (one window is tolerated,
     because call setup dips);
   - the iPhone disconnects;
   - the person chooses it on either phone (`manual_redmi`).
4. The iPhone retries once, quietly, after a transient fallback. After that the person decides with
   "iPhone'a al".

Connection is not ownership: an authenticated iPhone owns nothing until the Redmi has verified
audio in both directions.

## Handoff flows

- **Call made from the iPhone:** CallKit starts the call and the Redmi dials. Audio is requested at
  `dialing`, so ringback, busy tones and operator announcements are heard on the iPhone. This was
  physically verified.
- **Call answered on the iPhone:** CallKit Answer sends `answer` then `audio_start` over the network,
  or `answer` over Bluetooth while the network link is down; audio follows when it reconnects.
- **Call answered on the Redmi:** the iPhone stops ringing (`answeredElsewhere`). "iPhone'a al" later
  creates a CallKit call for it and moves the audio.
- **Link lost mid-call:** the Redmi falls back at once. The iPhone keeps its CallKit call for 20 s
  so a quick reconnect resumes audio there; after that it lets go, and the call continues on the
  Redmi.

## Call waiting

A second incoming call during a call is tracked apart from the current call. Every audio,
Bluetooth and notification listener keeps seeing only the current call, so the verified
single-call audio path is untouched.

- The waiting call shows on both phones: CallKit's call-waiting screen on the iPhone, and a card
  on the Redmi call screen.
- It can be declined, or taken with "end current and answer". The current call ends, the waiting
  call becomes current under the same id, and it is answered; its audio then moves to the iPhone
  like any answered call.
- Holding two calls at once is not offered.

## Incoming call on a locked iPhone

1. The Redmi rings. Its Bluetooth state characteristic notifies a sealed `ringing` state.
2. iOS wakes the suspended app. It is relaunched in the background by Bluetooth state restoration
   if the system had ended it, but not after the person force-quit it. The app reports the call to
   CallKit, so the lock screen rings.
3. The app sends `ringing_ack` over Bluetooth, or over the network if connected. With "ring quietly"
   on (the default), the Redmi silences its ringer. The Redmi rings normally if the iPhone never
   shows the call.
4. The app reconnects the network link in the background, ready for Answer.

**Swiped-away app:** iOS does not relaunch a swiped-away app for Bluetooth events, but it does for
iBeacon region monitoring.

- While the call rings unacknowledged, the Redmi also advertises an iBeacon. Its UUID is the first
  16 bytes of `derive(K, "beacon")`, with major 1 and minor 1. It stops as soon as the iPhone
  acknowledges.
- The iPhone monitors that region (needs "Always" location permission; only proximity is used).
- Entering the region relaunches the app, which connects and presents the call. Physically
  verified with build 41.
- The same beacon is pulsed briefly now and then while the iPhone has no link at all, so a
  swiped-away app comes back to "connected, asleep" by itself (see PROTOCOL.md, reconnect pulse).

**Away from home:** with the owner's APNs key imported on the Redmi, the Redmi sends a VoIP push
straight to Apple when the iPhone has not shown a ringing call within 1.5 s; PushKit wakes the app,
which reports the call to CallKit and connects over Tailscale. There is no server of ours.

**What the Redmi shows:** "Bağlı" while the network link is up, "Bağlı · iPhone uykuda" while only
Bluetooth is connected (the normal state of a locked iPhone at home), "Uzakta · ulaşılabilir" when
only Apple push can reach it, and "Bağlı değil" otherwise.

## Security and privacy of audio

- Audio is sealed with a per-connection key, and packets from any address other than the
  authenticated client's are dropped.
- Nothing is recorded, transcribed or stored on either phone. The logs contain only counters.

## Validation and handoff (2026-10-01)

- **Regression evidence:** deliberate RED runs
  [36813156080](https://github.com/ilkayturna/redmi-gateway/actions/runs/36813156080) and
  [36814171069](https://github.com/ilkayturna/redmi-gateway/actions/runs/36814171069) expose the
  timing/mute regressions. Intermediate GREEN
  [36813663867](https://github.com/ilkayturna/redmi-gateway/actions/runs/36813663867) is provisional
  evidence for that intermediate revision. Final run
  [36814654278](https://github.com/ilkayturna/redmi-gateway/actions/runs/36814654278) passed all 14 timing tests and native compilation, then was cancelled before signing to include the required API manifest;
  final release [36814956967](https://github.com/ilkayturna/redmi-gateway/actions/runs/36814956967), code revision `3fb6d7c`, passed 14 timing regressions, native compilation, bundled privacy manifest checks, simulator launch, signed archive/export and Apple upload. Apple reported build 59 `VALID`, and [36815257360](https://github.com/ilkayturna/redmi-gateway/actions/runs/36815257360) verified it is assigned to the existing owner internal TestFlight group.
- **Android local validation:** the current primary checkout passed 10 audio regression tests
  and 5 existing `CallManager` tests. The audio cases cover lossless 200 ms bursts, bounded excess
  backlog, priming, paced latency trimming, stale-frame expiry and retention of failure counters.
  The full primary debug APK is versionCode 202 / versionName 2.0.2, SHA256
  `DB98790B4806D442E30680E07200883B31609E67440D3372867D28667F864BFC`. It preserves the current
  conference changes and was installed while the Redmi was idle; the installed hash was
  verified. The foreground service restarted and the phone activity was opened.
- **Rollback:** the APK pulled immediately before that installation is
  `scratch/audio-diagnosis-20261001/redmi-installed-before-v202-latest.apk`, SHA256
  `96C5FE5C6BC9C5FB288485432CCF18CD49EEA46604F1BE916EBAACCB07F2A4F7`. Keep that exact artifact
  available until the new build is verified in conversation.
- **Remaining device evidence:** the Redmi's Wi-Fi was disabled when installation was verified, so no
  improvement in human call quality has been established. Reconnect both phones on the intended
  Wi-Fi and compare a real two-way call using the new PCM/silence, drop/underrun, arrival-gap and
  burst diagnostics. Installation of the matching iOS binary and a two-way speech test remain
  separate from Android installation and synthetic timing checks.
