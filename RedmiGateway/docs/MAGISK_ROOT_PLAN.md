# Magisk Root Workflow & Recovery Plan (Redmi Note 8 / ginkgo)

- **Target Architecture:** ARM64-v8a
- **Kernel Version:** Linux 4.14.186 (Snapdragon 665 / SM6125)
- **OS Version:** Android 11 (MIUI Global V12.5.2.0.RCOMIXM, API 30)
- **Target Magisk Version:** **Magisk v27.0 Stable** (or v26.4)
- **Execution Date:** After official unlock timer expiration (~25 September 2026 01:40)
- **Status:** PREPARED & VALIDATED — DO NOT EXECUTE YET

---

## 1. Minimal-Touch Root Philosophy

To preserve stock MIUI 12.5.2 system integrity, Turkcell VoLTE/IMS telephony profiles, and Qualcomm QDSP6 Hexagon audio HAL stability:
1. **DO NOT flash custom recovery (TWRP/OrangeFox):** Stock recovery is preserved to avoid vendor encryption discrepancies.
2. **DO NOT patch vbmeta initially:** On Snapdragon 665 / \ginkgo\, an unlocked bootloader accepts a Magisk-patched stock \oot.img\ without disabling vbmeta. Disabling verity unnecessarily can trip Google Play integrity or break MIUI telephony daemons.
3. **DO NOT modify system partitions:** Magisk operates in systemless mode via rootfs overlay in \oot.img\ ramdisk.

---

## 2. Step-by-Step Magisk Patching Workflow

### Step 2.1 — Download Official Magisk APK
Obtain official Magisk v27.0 from GitHub:
- Release: \https://github.com/topjohnwu/Magisk/releases/download/v27.0/Magisk-v27.0.apk\
- Save to: \RedmiGateway/root/Magisk-v27.0.apk\

### Step 2.2 — Install Magisk App on Device
`powershell
$adb = "C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"
& $adb install -r "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\Magisk-v27.0.apk"
`

### Step 2.3 — Push Stock boot.img & Patch via App
`powershell
# Push stock boot.img to device storage
& $adb push "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\stock\boot.img" /sdcard/Download/boot.img
`

1. Open Magisk app on Redmi Note 8.
2. Under Magisk card, tap **Install**.
3. Select **"Select and Patch a File"**.
4. Browse to \/sdcard/Download/boot.img\.
5. Tap **LET'S GO**.
6. Magisk unpacks \oot.img\, patches the ramdisk, injects \magiskinit\, and repacks.
7. Output file: \/sdcard/Download/magisk_patched-[random_strings].img\.

### Step 2.4 — Pull Patched Image to Host
`powershell
& $adb pull /sdcard/Download/magisk_patched_*.img "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\magisk_patched.img"
`

---

## 3. Fastboot Flashing Procedure

Once the bootloader is unlocked and device is in Fastboot mode:

`powershell
$fastboot = "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\miflash_unlock\fastboot.exe"

# 1. Verify connection
& $fastboot devices

# 2. Flash patched boot directly to boot partition
& $fastboot flash boot "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\magisk_patched.img"

# 3. Reboot into rooted system
& $fastboot reboot
`

---

## 4. Fallback & Recovery Procedure (Zero-Risk Guarantee)

If the device hangs, enters a bootloop, or fails to boot with the patched boot image:

`powershell
# 1. Force device into Fastboot mode (Hold Volume Down + Power for 10s)
# 2. Flash back the virgin stock boot.img immediately:
& $fastboot flash boot "c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\stock\boot.img"
& $fastboot reboot
`
The device immediately boots back into 100% stock unrooted MIUI with zero data loss or partition corruption.
