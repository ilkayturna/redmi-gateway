# Xiaomi Official Bootloader Unlock - 168-Hour Wait State & Safety Protocol

## Device & Session Verification
- **Device Model:** Xiaomi Redmi Note 8 (`ginkgo` / M1908C3JG)
- **Fastboot Serial:** `17df6357`
- **SoC:** Qualcomm Snapdragon 665 (SM6125)
- **Installed MIUI:** V12.5.2.0.RCOMIXM Global Stable (Android 11)
- **Bound Xiaomi Account:** `6402052858`
- **SIM:** Turkcell 4.5G (Slot 1)
- **Server Verification Result:** `PASS` (Green Checkmark on Mi Unlock Server)
- **Server Countdown Response:** 
  > *"Please unlock 167 hours later. And do not add your account in MIUI again, otherwise you will wait from scratch."*
- **Countdown Start:** 18 September 2026 ~01:40 (UTC+3)
- **Target Unlock Eligibility:** **25 September 2026 ~01:40 (UTC+3)**

---

## CRITICAL SAFETY RULES (PREVENT TIMER RESET)

The Xiaomi unlock server enforces a strict anti-abuse policy. Breaking any of the following rules will reset the 168-hour countdown back to day zero:

### 1. NEVER Tap "Add account and device"
- Location: `Settings` → `Additional Settings` → `Developer options` → `Mi Unlock status`.
- **DANGER:** Tapping "Add account and device" generates a new token on Xiaomi servers and instantly resets the timer to 168 hours!
- **RULE:** Do not open `Mi Unlock status` screen under any circumstances.

### 2. NEVER Log Out of the Mi Account
- Do not sign out of Mi Account `6402052858` in device settings.
- Do not change the Mi account password during the waiting period.

### 3. NEVER Remove or Swap the SIM Card
- The SIM card used to bind the device (`Turkcell`) must remain in SIM Slot 1.
- Removing or replacing the SIM triggers a security re-verification on next cloud ping.

### 4. NEVER Attempt OTA Updates
- Do not accept any MIUI system updates during the waiting period.

### 5. NEVER Attempt EDL or Unofficial Bypasses
- No test point shorting.
- No EDL flashing.
- No third-party exploit tools.
- The official path is 100% verified and guaranteed to succeed without brick risk.

### 6. Keep Device Powered On and Active
- Keep the phone connected to charger or periodically charged.
- Maintain Wi-Fi or cellular connectivity so Xiaomi cloud heartbeat remains continuous.

---

## EXACT PROCEDURE ON UNLOCK DAY (25 September 2026)

When the 168 hours expire on **25 September 2026**:

### Step 1: Boot Phone into Fastboot Mode
1. Power off the Redmi Note 8.
2. Hold **Volume Down (-) + Power** buttons simultaneously until the orange FASTBOOT logo appears.
3. Connect the phone to the Windows PC via USB cable.

### Step 2: Run Mi Unlock Tool
1. Launch `miflash_unlock.exe` or `batch_unlock.exe` from `C:\Users\ilkay.turna\Desktop\miflash_unlock-en-7.6.702.42\`.
2. Log in using Mi Account `6402052858`.
3. Verify device shows: `17df6357 - Connected`.
4. Click **Unlock** (or **Unlock (F6)**).
5. The tool will progress:
   - Verifying device: 100%
   - Unlocking bootloader: 100%
   - Success message: *"Unlocked successfully"*.

### Step 3: Device Reboots & Factory Resets
- The device will automatically reboot, wipe userdata (factory reset), and display the unlocked padlock icon at boot.
- Proceed immediately with `POST_UNLOCK_RESTORE.md` and `MAGISK_ROOT_PLAN.md`!
