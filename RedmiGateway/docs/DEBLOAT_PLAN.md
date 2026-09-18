# Redmi Note 8 (ginkgo) 24/7 Cellular Gateway - Debloat & Optimization Plan

## 1. Objective
Transform the Redmi Note 8 (`ginkgo`, Snapdragon 665, 4000mAh battery) into a dedicated, rock-solid, 24/7 headless cellular call & SMS gateway.
By freezing or removing non-essential MIUI cloud services, analytics, ad frameworks, and background telemetry, we achieve:
1. **Zero thermal throttling** when operating continuously on AC power.
2. **Minimal background RAM & CPU footprint**, freeing resources for Qualcomm audio DSP and UDP socket streaming.
3. **Prevention of aggressive MIUI task-killing** that could interfere with `GatewayService` or default dialer telecom bindings.

---

## 2. Package Classification

### Category A: SAFE TO FREEZE / DISABLE (Zero Cellular Risk)
These packages provide consumer apps, telemetry, ads, and sync services. Disabling them does not affect cellular calls, SMS, ADB, or Wi-Fi hotspot functionality.

| Package Name | Description | Recommended Action |
|---|---|---|
| `com.miui.analytics` | MIUI Analytics & Tracking | `pm disable-user --user 0` |
| `com.miui.msa.global` | MIUI Ad Services (MSA) | `pm disable-user --user 0` |
| `com.miui.cloudservice` | Mi Cloud Sync Framework | `pm disable-user --user 0` |
| `com.miui.cloudbackup` | Mi Cloud Device Backup | `pm disable-user --user 0` |
| `com.xiaomi.micloud.sdk` | Mi Cloud SDK Runtime | `pm disable-user --user 0` |
| `com.miui.micloudsync` | Mi Cloud Sync Provider | `pm disable-user --user 0` |
| `com.xiaomi.midrop` | ShareMe (Wi-Fi direct file transfer) | `pm disable-user --user 0` |
| `com.miui.player` | Mi Music Player | `pm disable-user --user 0` |
| `com.miui.videoplayer` | Mi Video Player | `pm disable-user --user 0` |
| `com.android.browser` | Mi Browser | `pm disable-user --user 0` |
| `com.miui.yellowpage` | Yellow Pages / Caller ID (MIUI) | `pm disable-user --user 0` |
| `com.miui.bugreport` | MIUI Bug & Crash Reporter | `pm disable-user --user 0` |
| `com.miui.miservice` | Services & Feedback | `pm disable-user --user 0` |
| `com.miui.weather2` | MIUI Weather | `pm disable-user --user 0` |
| `com.xiaomi.joyose` | Game Turbo / Analytics daemon | `pm disable-user --user 0` |
| `com.miui.cleanmaster` | Cleaner (Tencent/Cheetah core) | `pm disable-user --user 0` |
| `com.xiaomi.glgm` | Mi Games | `pm disable-user --user 0` |
| `com.mi.globalTrendNews`| Global News Feed | `pm disable-user --user 0` |
| `com.miui.hybrid` | Quick Apps Framework | `pm disable-user --user 0` |
| `com.miui.hybrid.accessory` | Quick Apps Sub-component | `pm disable-user --user 0` |

---

### Category B: TEST CAREFULLY (Monitor Device Stability)
These system managers handle power profiles and background memory limits. In MIUI, they contain aggressive background killing logic. Disabling them helps keep `GatewayService` running, but must be verified on post-root boot.

| Package Name | Description | Testing Protocol |
|---|---|---|
| `com.miui.powerkeeper` | MIUI PowerKeeper | Freezing prevents MIUI from killing background services. Verify that battery temperature remains normal. |
| `com.miui.securityadd` | Security Component Add-ons | Test to ensure USB debugging and permission dialogs still function. |
| `com.miui.guardprovider`| Guard Provider (Anti-virus) | Can be frozen if PackageInstaller allows direct installs. |

---

### Category C: NEVER TOUCH (CRITICAL CELLULAR & HARDWARE LAYER)
Touching or disabling any of these packages will brick cellular radio, disable SIM registration, destroy ALSA audio routing, or prevent ADB communication.

| Critical Subsystem | Package Names / Components | Why It Must NOT Be Touched |
|---|---|---|
| **Cellular Radio & RIL** | `com.android.phone`, `com.android.server.telecom`, `com.android.providers.telephony` | RIL bridge to Snapdragon 665 modem, SIM authentication, call setup. |
| **Qualcomm Radio (QCRIL)**| `com.qualcomm.qti.telephony`, `com.qualcomm.qti.qcrilmsgtunnel` | Qualcomm proprietary interface between Android RIL and Hexagon modem DSP. |
| **IMS & VoLTE** | `com.qualcomm.qti.ims`, `org.codeaurora.ims` | Voice over LTE (VoLTE) Turkcell signaling and HD voice codecs (AMR-WB). |
| **Carrier Settings** | `com.android.carrierconfig` | Turkcell APN, IMS APN, emergency calling numbers. |
| **Audio Subsystem** | `android.hardware.audio`, `vendor.qti.hardware.audio` | Qualcomm ALSA HAL, mixer paths, `Incall_Music` mixer. |
| **ADB & USB** | `com.android.systemui`, `com.android.settings` | USB debugging authorization, connection keepalive. |

---

## 3. Automated Execution Script (`scripts/debloat_gateway.sh`)
Execute this via root shell or ADB once root is established:

```bash
#!/system/bin/sh
echo "Applying Safe Debloat for 24/7 Gateway Mode..."

SAFE_PACKAGES="
com.miui.analytics
com.miui.msa.global
com.miui.cloudservice
com.miui.cloudbackup
com.xiaomi.micloud.sdk
com.miui.micloudsync
com.xiaomi.midrop
com.miui.player
com.miui.videoplayer
com.android.browser
com.miui.yellowpage
com.miui.bugreport
com.miui.miservice
com.miui.weather2
com.xiaomi.joyose
com.miui.cleanmaster
com.xiaomi.glgm
com.mi.globalTrendNews
com.miui.hybrid
com.miui.hybrid.accessory
"

for pkg in $SAFE_PACKAGES; do
    echo "Disabling: $pkg"
    pm disable-user --user 0 "$pkg" 2>/dev/null
done

echo "Safe debloat complete."
```

---

## 4. Reversal / Restore Commands
To re-enable any package if needed:
```bash
pm enable <package_name>
```
