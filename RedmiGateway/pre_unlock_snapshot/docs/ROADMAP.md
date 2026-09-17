# Redmi Cellular Gateway Roadmap

This roadmap outlines the evolution of the Redmi Note 8 GSM gateway for iOS.

---

### PHASE 1 — Android Telecom Call Detection and Control (Current Prototype)
- Implement default dialer role on Redmi Note 8 (Android 11, MIUI 12.5.2).
- Bind `GatewayInCallService` via `android.telecom.InCallService`.
- Track live GSM call state transitions (`RINGING`, `DIALING`, `ACTIVE`, `HOLDING`, `DISCONNECTED`).
- Extract caller and dialed telephone numbers from Telecom details.
- Implement programmatic call operations: Answer, Reject, Disconnect/Hangup, and Outgoing `placeCall()`.
- Host embedded local WebSocket server on `0.0.0.0:8787` with pairing token authentication.
- Foreground service with notification to prevent background OS termination.

---

### PHASE 2 — iPhone CallKit Client (Current Scaffold)
- SwiftUI client connecting to local WebSocket on Redmi gateway.
- Map WebSocket call events to native iOS `CallKit` framework (`CXProvider`, `CXCallController`).
- Display full-screen native incoming call UI on iPhone with caller ID.
- Hook into native Answer, Reject, and Hang up actions from lock screen / CallKit heads-up banner.
- Initiate outgoing GSM calls from iPhone through CallKit `CXStartCallAction`.
- Note: Call signaling and controls only; audio bridge not active in this phase.

---

### PHASE 3 — Two-Way GSM Call Audio Bridge Between Redmi and iPhone
- Capture downstream cellular audio from GSM call on Redmi and stream with low-latency (Opus / WebRTC / raw PCM UDP) to iPhone earpiece/speaker.
- Capture iPhone microphone audio and inject upstream into Redmi GSM call audio uplink.
- Explore audio routing methods on stock Android 11:
  - `AudioRecord` with `MediaRecorder.AudioSource.VOICE_CALL` / `VOICE_COMMUNICATION` / `VOICE_DOWNLINK` / `VOICE_UPLINK`.
  - Bluetooth HFP / SCO proxy emulation (presenting Redmi as a Bluetooth headset to a second link or virtual audio route).
  - USB Audio gadget / Accessory mode or ALSA-level routing.

---

### PHASE 4 — SMS Receive / Send Relay
- Register SMS Broadcast Receiver on Redmi for `android.provider.Telephony.SMS_RECEIVED`.
- Relay incoming SMS to iPhone over WebSocket / Push.
- Support sending SMS from iPhone through Redmi `SmsManager.sendTextMessage()`.

---

### PHASE 5 — PushKit / APNs Background Incoming-Call Support
- Integrate Apple Push Notification service (APNs) and `PushKit` (VoIP pushes) on iOS.
- Wake suspended iPhone on incoming GSM ring even when the app is terminated or in deep sleep.
- Trigger CallKit banner instantaneously upon PushKit notification arrival.

---

### PHASE 6 — Battery and Reconnect Optimization
- Optimize wake-lock management and Wi-Fi power-save modes on Redmi.
- Zero-latency reconnection logic on local network / hotspot handshake.
- Battery reporting and health status reporting in gateway status packet.

---

### PHASE 7 — Optional ROM / System-Level Modifications
- Only if stock MIUI audio SELinux / kernel limits prevent clean two-way cellular audio injection in Phase 3.
- Options: Custom AOSP/LineageOS build or Magisk module for audio HAL bypass (strictly postponed until Phase 3 evaluation).
