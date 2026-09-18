# Post-Unlock Factory Reset Recovery Checklist

This runbook documents the complete sequence required to restore the \RedmiGateway\ cellular signaling environment after Xiaomi Bootloader Unlock performs the mandatory userdata wipe.

---

## 1. Environment & Package Metadata

- **Package Name:** \com.ilkay.redmigateway\
- **Application Class:** \com.ilkay.redmigateway.RedmiGatewayApp\
- **Foreground Service:** \com.ilkay.redmigateway.service.GatewayService\
- **InCallService:** \com.ilkay.redmigateway.telecom.GatewayInCallService\
- **WebSocket Port:** \8787\
- **Local Source Directory:** \c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\android\
- **Build Output:** \RedmiGateway/android/app/build/outputs/apk/debug/app-debug.apk\

---

## 2. Fast Step-by-Step Restoration Procedure

### Step 2.1 — Initial Device Setup after Unlock
1. Boot into clean MIUI 12.5.2.
2. Complete the initial MIUI setup wizard (skip Google/Mi account sign-in if desired).
3. Connect to local development Wi-Fi.
4. Verify Turkcell SIM is active and Mobile Data / VoLTE is enabled.
5. Enable Developer Options:
   - Tap **MIUI version** 7 times under *Settings -> About phone*.
   - In *Additional settings -> Developer options*:
     - Enable **USB debugging**.
     - Enable **Install via USB**.
     - Enable **USB debugging (Security settings)**.

---

### Step 2.2 — Compile & Install Fresh Gateway APK
From the host Windows machine:

`powershell
cd c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\android
.\gradlew.bat assembleDebug
& "C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe" install -r -g app\build\outputs\apk\debug\app-debug.apk
`

---

### Step 2.3 — Set Default Dialer & Grant System Permissions
Execute the following automated ADB command sequence:

`powershell
$adb = "C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"

# 1. Set as Default Dialer (Crucial for InCallService binding)
& $adb shell telecom set-default-dialer com.ilkay.redmigateway

# 2. Grant all Telephony and SMS permissions
& $adb shell pm grant com.ilkay.redmigateway android.permission.READ_PHONE_STATE
& $adb shell pm grant com.ilkay.redmigateway android.permission.CALL_PHONE
& $adb shell pm grant com.ilkay.redmigateway android.permission.READ_CALL_LOG
& $adb shell pm grant com.ilkay.redmigateway android.permission.WRITE_CALL_LOG
& $adb shell pm grant com.ilkay.redmigateway android.permission.RECEIVE_SMS
& $adb shell pm grant com.ilkay.redmigateway android.permission.SEND_SMS
& $adb shell pm grant com.ilkay.redmigateway android.permission.READ_SMS
& $adb shell pm grant com.ilkay.redmigateway android.permission.RECORD_AUDIO

# 3. Whitelist from Doze / Battery Optimization
& $adb shell dumpsys deviceidle whitelist +com.ilkay.redmigateway

# 4. Prevent MIUI App Sleep / Background Restraints
& $adb shell cmd appops set com.ilkay.redmigateway RUN_IN_BACKGROUND allow
& $adb shell cmd appops set com.ilkay.redmigateway RUN_ANY_IN_BACKGROUND allow
& $adb shell am set-inactive com.ilkay.redmigateway false
`

---

### Step 2.4 — Start Gateway Service & Verify WebSocket
`powershell
& $adb shell am start-foreground-service -n com.ilkay.redmigateway/.service.GatewayService
`

Verify service is bound and listening on port 8787:
`powershell
& $adb shell netstat -tuln | Select-String "8787"
`

---

### Step 2.5 — Re-Pair iOS CallKit Client
1. Open RedmiGateway on the iPhone.
2. In the connection bar, enter:
   - Host: Redmi Note 8 local IP (e.g. \192.168.1.x\)
   - Port: \8787\
   - Pairing Token: Displayed on the RedmiGateway Android UI screen.
3. Tap **Connect**.
4. Status will show **Connected** and initial \state\ sync is returned.
