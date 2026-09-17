# Device Information: Xiaomi Redmi Note 8 (ginkgo)

Captured on: 2026-09-18
Connection: USB Debugging (ADB Serial: `17df6357`)

## Hardware & System Properties

| Property | Value |
|---|---|
| **Manufacturer** (`ro.product.manufacturer`) | `Xiaomi` |
| **Model** (`ro.product.model`) | `Redmi Note 8` (`M1908C3JG`) |
| **Device / Codename** (`ro.product.device`) | `ginkgo` |
| **Android Release** (`ro.build.version.release`) | `11` |
| **SDK Level** (`ro.build.version.sdk`) | `30` |
| **Fingerprint** (`ro.build.fingerprint`) | `xiaomi/ginkgo/ginkgo:11/RKQ1.201004.002/V12.5.2.0.RCOMIXM:user/release-keys` |
| **MIUI Version** | `MIUI Global 12.5.2` (Android 11) |
| **Active Network IP (`wlan0`)** | `192.168.1.108/24` |
| **Bootloader Status** | Locked |
| **Root Status** | Stock, unrooted |

---

## Telecom Subsystem (`dumpsys telecom`) Summary

- **Telephony Connection Service**: `ComponentInfo{com.android.phone/com.android.services.telephony.TelephonyConnectionService}`
- **Active Phone Accounts**:
  - `com.android.phone/...` (GSM CallProvider, MultiUser, PlaceEmerg, SimSub - Turkcell)
  - `com.google.android.gm/...` (JetpackConnectionService)
- **Current Default Dialer**: `com.google.android.dialer` (Google Phone)
- **InCallService UI Binding**: Handled via `InCallController`, supporting `BIND_INCALL_SERVICE` for the default dialer role.
- **Call Technologies**: GSM, VoLTE, VoWiFi capable via Turkcell.
- **Audio Routes**: Receiver (Earpiece), Speaker, Bluetooth, Wired Headset (`BESW`).
