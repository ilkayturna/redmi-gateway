# Audio Subsystem Baseline & Feasibility Analysis (Phase 3 Preparation)
**Device:** Xiaomi Redmi Note 8 Global (`ginkgo` / `M1908C3JG`)  
**Chipset:** Qualcomm Snapdragon 665 (SM6125 / QDSP6 Hexagon Audio DSP)  
**OS:** Stock MIUI Global 12.5.2 (Android 11, API 30, Unrooted, Locked Bootloader)

---

## 1. Hardware & Audio HAL Overview
- **Architecture:** Qualcomm MSM/QCOM Audio HAL with QDSP6 (Hexagon DSP) offloading telephony vocoders directly to modem baseband.
- **Audio Server:** `init.svc.audioserver: running`
- **Fluence:** Dual-microphone noise cancellation (`persist.audio.fluence.voicecall: true`, `persist.audio.fluence.speaker: true`).
- **PCM VoIP Routing:** `[use.voice.path.for.pcm.voip]: [true]`
- **Deep Buffer:** `[audio.deep_buffer.media]: [true]`

---

## 2. Policy Configuration & Telephony Routing Inspection
Inspection of `/vendor/etc/audio/audio_policy_configuration.xml` reveals:
```xml
<!-- Sinks and Sources -->
<devicePort tagName="Telephony Tx" type="AUDIO_DEVICE_OUT_TELEPHONY_TX" role="sink">
<devicePort tagName="Telephony Rx" type="AUDIO_DEVICE_IN_TELEPHONY_RX" role="source">

<!-- Telephony Uplink Mixer Route -->
<route type="mix" sink="Telephony Tx" sources="voice_tx,incall_music_uplink"/>

<!-- Telephony Downlink Route -->
<route type="mix" sink="primary input" sources="Telephony Rx"/>

<!-- In-call Music Port -->
<mixPort name="incall_music_uplink" role="source" flags="AUDIO_OUTPUT_FLAG_INCALL_MUSIC">
```

### Key Architectural Insights:
1. **Telephony Tx (GSM Uplink):**
   - Telephony transmission mix route accepts `voice_tx` (microphone via Hexagon DSP voice preprocessing) and `incall_music_uplink` (`AUDIO_OUTPUT_FLAG_INCALL_MUSIC`).
2. **Telephony Rx (GSM Downlink):**
   - Cellular modem voice decoder outputs directly to Hexagon DSP and routes to `Telephony Rx` which feeds `AUDIO_DEVICE_IN_TELEPHONY_RX`.
3. **SCO / Bluetooth Subsystem:**
   - Qualcomm audio policy includes dedicated endpoints:
     - `AUDIO_DEVICE_OUT_BLUETOOTH_SCO` / `AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET`
     - `AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET`
   - Android `mScoAudioMode: SCO_MODE_VIRTUAL_CALL` is active and supported.

---

## 3. GSM Audio Bridging Feasibility on Stock (Unrooted) Android 11

On unrooted Android 11 (API 30), audio injection/extraction for live GSM calls faces OS-level permission restrictions:

| Path | API / Mechanism | Stock / Non-Root Status | Privilege Required |
| :--- | :--- | :--- | :--- |
| **Downlink Capture (Remote GSM Audio)** | `AudioRecord` with `AudioSource.VOICE_DOWNLINK` | Blocked on vanilla non-system apps | `android.permission.CAPTURE_AUDIO_OUTPUT` (Signature/System) |
| **Downlink Capture (Remote GSM Audio)** | `AudioRecord` with `AudioSource.VOICE_CALL` | Restricted on Android 10+ | Requires system or carrier privileges |
| **Uplink Injection (Send Audio into GSM)** | `AudioTrack` with `STREAM_VOICE_CALL` | Does not inject into modem uplink | Only plays to local speaker/earpiece unless routed via in-call music |
| **Uplink Injection (Send Audio into GSM)** | `incall_music_uplink` | Restricted HAL flag | AudioPolicy flags check |
| **Bluetooth SCO Gateway (HFP Bridging)** | Redmi acts as AG (Audio Gateway) or HF (Hands-Free) | **FEASIBLE & STANDARD** | Standard Bluetooth API / AudioRecord(SCO) |
| **Physical Loopback (USB Audio Dongle / 3.5mm TRRS)** | Hardware TRRS / USB Audio loopback | **100% RELIABLE & NO ROOT NEEDED** | Zero permission barriers, lossless line-level audio |
| **Speaker / Mic Acoustic Coupling (Diagnostic)** | Speakerphone + Mic | **FEASIBLE** | Standard `RECORD_AUDIO` permission |

---

## 4. Phase 3 Recommended Strategy Matrix
1. **Option A: Bluetooth SCO Relay (Fully Wireless):**
   - Use Bluetooth Hands-Free Profile (HFP/SCO) to bridge bidirectional 16kHz wideband audio (mSBC) or 8kHz (CVSD) between the Redmi and the receiver / host.
2. **Option B: USB Audio / Analog TRRS Hardware Bridge:**
   - A \$5 USB-C / 3.5mm TRRS splitter + USB sound card on the host provides crystal-clear bidirectional audio injection without any OS or vendor restrictions.
3. **Option C: Privileged InCallService Audio API Probe:**
   - In Phase 3, we will test whether running as the active `InCallService` + Default Dialer allows `AudioRecord(AudioSource.VOICE_CALL)` capture on this specific MIUI 12.5.2 build without throwing `SecurityException`.
