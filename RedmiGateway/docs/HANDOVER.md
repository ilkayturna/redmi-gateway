# Handover: build, deploy, test, roll back

## Branches and safety points

| Ref | What it is |
|---|---|
| `main` | The last state before this delivery (build 33 era). Not changed by the delivery. |
| `checkpoint/takeover-worktree-20260927` | The exact working tree found at takeover, committed as-is. |
| `baseline/16khz-uplink-20260927-1231` | The Android build installed at 12:31 on 27 Sep 2026 (16 kHz uplink baseline). |
| `delivery/phone-bridge` | This delivery (Android 2.0.1 (201), iPhone TestFlight 2.0 (47)). Merging to `main` is the owner's decision. |

## Android (Redmi)

The app is a privileged system app (`/system/priv-app/RedmiGateway`). Updates install over it.

```bash
cd RedmiGateway/android
JAVA_HOME=<JDK 17> ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start-foreground-service -a com.ilkay.redmigateway.ACTION_START -n com.ilkay.redmigateway/.service.GatewayService
```

- On Windows, build from PowerShell and check that the APK timestamp changed. A build run through
  Git Bash once reused an old APK without saying so.
- The app must be the default phone app. The phone screen shows a notice with a button when it
  is not.
- Version: `versionCode 201`, `versionName 2.0.1`.

### Vendor audio fix (SIM slot 2 in-call music)

```bash
python scripts/device/vendor_audio_fix.py status   # present / absent
python scripts/device/vendor_audio_fix.py apply
python scripts/device/vendor_audio_fix.py revert   # restores /vendor/etc/phonebridge-backup
```

- iPhone audio does not work without the fix. Settings (the gear) on the Redmi shows
  "iPhone ses kanalı (SIM 2)".
- A ROM update can replace `/vendor`; run `status` after any system update.

### Rolling Android back

Check out `baseline/16khz-uplink-20260927-1231` (or any earlier commit), build it and
`adb install -r`. Old iPhone builds (protocol 1) work again only while no keyed pairing exists. To
remove the pairing, use Settings › "iPhone eşleşmesini kaldır", or clear the app
data.

## iPhone

iOS builds only on GitHub Actions (`.github/workflows/build-ios.yml`):

```bash
gh workflow run build-ios.yml --ref delivery/phone-bridge -f release=false   # compile + simulator screenshot
gh workflow run build-ios.yml --ref delivery/phone-bridge -f release=true    # sign and upload to TestFlight
gh workflow run build-ios.yml --ref delivery/phone-bridge -f release=false -f promote_build=<N>  # add build N to the internal group
```

- The build number is the workflow run number.
- `ITSAppUsesNonExemptEncryption` is `false`. The app uses only Apple's CryptoKit and CommonCrypto
  to protect its own local traffic. Before a public App Store release, confirm the export
  compliance answer in App Store Connect.

## First run after the update

1. Install the Redmi build, then the iPhone build.
2. The iPhone finds the code saved by build 33 and pairs by itself; the Redmi then rotates its
   code. If that code had already changed, the iPhone asks for the code once. The code is under
   Redmi › Settings (the gear at the top right of the phone screen).
3. Allow the microphone, contacts and Bluetooth on the iPhone.

## Test tooling (PC)

| Tool | Purpose |
|---|---|
| `scripts/lab/iphone_emulator.py --scenario security --pair` | Protocol 2 checks: protocol 1 refused, forged proof, wrong code, replayed/unsealed/forged frames, lab hooks off. Restores the real pairing afterwards. |
| `scripts/lab/iphone_emulator.py --scenario handoff --dial 532 --pair` | A real call to the Turkcell IVR: sealed handoff, microphone loss, re-arm, one-way loss ("deaf" iPhone), manual return, call_log push. |
| `scripts/lab/iphone_emulator.py --scenario sync` | Journal replay: full sync, then an empty replay after `lastSeq`. |
| `scripts/lab/call_routing_lab.py` | HAL routing measurements (turns lab mode on over adb). |
| `scripts/lab/bridge_client.py` | Shared protocol 2 client used by the tools above. |

The owner approved automated IVR calls to Turkcell 532.

## Smart hotspot

The iPhone goes online through the Redmi. On a known Wi-Fi (one the Redmi has joined before) the
Redmi joins it and turns its hotspot off, saving mobile data; without one the hotspot comes back
within about 15 s. This Redmi's Wi-Fi chip cannot join a network while its own hotspot runs, so
when a known network appears in a scan the hotspot is turned off for up to 30 s to let Android
join it; if that fails, the hotspot returns and that network is skipped for ten minutes. Nothing
switches during a call. Settings › Hotspot turns this off. The known list is learned; it can be
seeded from the Redmi's saved networks over adb:

```bash
adb shell am broadcast -a com.ilkay.redmigateway.KNOWN_WIFI --esa ssids "Home,Office" -n com.ilkay.redmigateway/.service.KnownWifiReceiver
```

The Wi-Fi networks must be saved on the iPhone too, or it loses the internet when the hotspot
goes off.

## Away from home (Redmi at home, iPhone elsewhere)

Owner steps, done once:

1. Install **Tailscale** on both phones and sign in with the same account. The Redmi announces
   its Tailscale address, and the iPhone learns it at home and uses it when away.
2. In the Apple Developer account, open Keys, create a key with "Apple Push Notifications service"
   enabled, and download `AuthKey_XXXXXXXXXX.p8`.
3. Copy that file to the Redmi and choose it under Settings › Uzaktan kullanım.
4. Open the iPhone app once at home; it registers for push and hands its token to the Redmi.

The release pipeline enables Push Notifications on the app ID and builds with a push-capable
profile (`scripts/ensure_push_profile.py`). If that step fails, the build falls back to the old
profile, without push.

## Known limits (platform)

- iOS does not relaunch a swiped-away app for Bluetooth. The call wake iBeacon covers that case,
  and short beacon pulses bring a swiped-away app back onto Bluetooth within a few minutes, but
  both need "Always" location permission. Without it, a swiped-away app misses the call, which
  still rings on the Redmi. An app merely in the background is woken by Bluetooth.
- iOS closes a sleeping app's network socket, so the iPhone keeps only Bluetooth open while it
  sleeps. The Redmi shows this as "Bağlı · iPhone uykuda"; the network link comes back the moment
  a call rings or the app opens.
- The "Always" location permission exists only for the call wake beacon. Before a public App Store
  release, expect App Review to ask about it; the purpose strings explain it.
- iPhone and Redmi must share a local network for call audio. Bluetooth carries state and
  answer/reject/hang-up, never audio.
- Emergency numbers dialled in the iPhone app open the iPhone's own dialer and are not placed
  through the Redmi.
