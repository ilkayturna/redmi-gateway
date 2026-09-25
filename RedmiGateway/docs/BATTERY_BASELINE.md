# Battery & Power Subsystem Baseline Report (Pre-Debloat)

**Device:** Xiaomi Redmi Note 8 Global (`ginkgo` / `M1908C3JG`)  
**OS:** Stock MIUI Global 12.5.2 (Android 11, API 30, Unrooted, Locked Bootloader)  
**Date of Capture:** 2026-09-18 01:10 (Local dev session)  
**Status:** Pre-Debloat Initial Baseline (Zero components disabled or uninstalled)

---

## 1. Battery Health, Capacity & Thermal State

Extracted from `dumpsys battery` and kernel battery driver:

| Parameter | Current Value | Specification / Assessment |
| :--- | :--- | :--- |
| **Nominal Capacity** | 4,000 mAh | Typical factory rating for BN40 battery |
| **Reported Charge Counter** | 3,625,440 µAh (~3,625 mAh) | Remaining charge at this moment; it does not measure battery health or full-charge capacity. |
| **Battery Level** | 86% | Stable |
| **Battery Voltage** | 4,214 mV | Normal operating voltage |
| **Battery Temperature** | 31.5 °C | Optimal thermal baseline (idle/charging) |
| **Battery Health Flag** | 2 (`BATTERY_HEALTH_GOOD`) | Certified good condition |
| **Technology** | Li-poly | Standard lithium-polymer chemistry |

---

## 2. Power Manager & Active Wakelocks

Extracted from `dumpsys power`:

- **Wakelocks Active (Count: 1):**
  - `PARTIAL_WAKE_LOCK 'RedmiGateway::WakeLock'` (UID 10021, PID 1610, held by `com.ilkay.redmigateway.service.GatewayService`)
  - **Purpose:** Prevents Android CPU sleep while listening for incoming WebSocket connections and hotspot telemetry.
  - **Assessment:** This long-lived partial wake lock prevented CPU sleep while held and needed to be removed for battery-oriented gateway use.

---

## 3. Background Process Inventory (Pre-Debloat Baseline)

A full process inspection (`ps -A`) identified **40 persistent background daemons** running concurrently without active user engagement:

### A. Xiaomi / MIUI Background Processes (25 Daemons)
These services run continuously in background, generating wakeups and network telemetry:
1. `com.miui.cloudservice` (Xiaomi Cloud sync)
2. `com.miui.daemon` (MIUI Background telemetry & system monitoring)
3. `com.miui.msa.global` (MIUI System Ads / Analytics engine)
4. `com.xiaomi.finddevice` (Xiaomi Device locator & telemetry)
5. `com.xiaomi.mi_connect_service` (Xiaomi cross-device sharing/IoT protocol)
6. `com.xiaomi.xmsfkeeper` / `com.xiaomi.xmsf` (Xiaomi Service Framework)
7. `com.miui.powerkeeper` (MIUI proprietary battery manager)
8. `com.miui.securityadd` (MIUI security app companion)
9. `com.miui.yellowpage` (Yellow pages caller ID & online directory)
10. `com.miui.analytics` (MIUI usage telemetry)
11. `com.miui.cleanmaster` (Cheetah Mobile / Cleaner engine)
12. `com.miui.hybrid` (Quick apps / webview container)
13. `com.miui.miservice` (Services & feedback)
14. `com.miui.bugreport` (Crash log collector)
15. `com.miui.weather2` (Weather polling background sync)
16. `com.miui.player` (Mi Music background services)
17. `com.miui.micredit` (Mi Credit / Payment framework)
18. `com.miui.translation.kingsoft` (Translation services)
19. `vendor.xiaomi.hardware.misys@1.0-service`
20. `vendor.xiaomi.hardware.misys@2.0-service`
21. `vendor.xiaomi.hardware.misys@3.0-service`
22. `vendor.xiaomi.hardware.displayfeature@1.0-service`
23. `miuibooster`
24. `com.miui.home` (System launcher)
25. `com.miui.miwallpaper` (Live wallpaper engine)

### B. Google Background Processes (15 Daemons)
1. `com.google.android.googlequicksearchbox` (Google App / Assistant search engine)
2. `com.google.android.googlequicksearchbox:interactor`
3. `com.google.android.googlequicksearchbox:search`
4. `com.google.android.apps.turbo` (Device Health Services / adaptive battery predictor)
5. `com.google.android.projection.gearhead` (Android Auto companion)
6. `com.google.android.projection.gearhead:shared`
7. `com.google.android.projection.gearhead:car`
8. `com.google.process.gservices` (Google Services Framework)
9. `com.google.process.gapps` (Google Accounts & Sync)
10. `com.google.android.gms` (Google Play Services base)
11. `com.google.android.gm` (Gmail background sync)
12. `com.google.android.apps.messaging` (Google Messages SMS sync)
13. `com.google.android.permissioncontroller`
14. `com.google.android.ext.services`
15. `com.google.android.webview:sandboxed_process`

---

## 4. Android Doze & Idle Configuration

Extracted from `dumpsys deviceidle`:
- **Current State:** `mState=ACTIVE`, `mLightState=ACTIVE` (Screen active / USB power connected).
- **Settings:**
  - `light_after_inactive_to = +3m0s`
  - `light_idle_to = +15m0s`
  - `light_max_idle_to = +30m0s`
- **Whitelisted Packages:**
  - Standard system apps + Telecom provider. `com.ilkay.redmigateway` is exempt from battery optimizations when foreground service runs.

---

## 5. Debloat Optimization Roadmap (Scheduled for Later Phase)

*Per project rules, no system services or packages have been disabled or removed at this stage.*  
Any optional package removal needs a one-package-at-a-time reboot test and a recovery path:
- Disable `com.miui.msa.global`, `com.miui.analytics`, `com.miui.daemon` (eliminates background telemetry).
- **Never disable or uninstall `com.xiaomi.finddevice` on this MIUI build.** During the 2026-09-25 reboot, `SecurityManagerService` crashed repeatedly with `System error: connot find system app : com.xiaomi.finddevice`, preventing Android from booting. Restore this package before further optimization.
- Disable `com.google.android.googlequicksearchbox`, `com.google.android.projection.gearhead` (saves ~120MB RAM and periodic wakelocks).
- Battery-life improvement must be measured on the actual device with hotspot, client, and call/SMS gateway active. No percentage reduction is established by this baseline.
