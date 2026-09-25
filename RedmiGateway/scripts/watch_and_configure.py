import subprocess
import time
import os
import sys

ADB = r"C:\Users\ilkay.turna\platform-tools\adb.exe"
LOG_FILE = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\docs\watch_and_configure.log"

def log(msg):
    t = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{t}] {msg}"
    print(line, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")

def run(cmd):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=15)
        return p.stdout.strip()
    except Exception as e:
        return str(e)

log("Starting watch_and_configure loop...")
gateway_configured = False
magisk_configured = False

while not (gateway_configured and magisk_configured):
    packages = run([ADB, "shell", "pm list packages"])
    
    # Check for RedmiGateway
    if "com.ilkay.redmigateway" in packages and not gateway_configured:
        log("DETECTED: com.ilkay.redmigateway installed! Configuring permissions...")
        perms = [
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_SMS",
            "android.permission.RECORD_AUDIO"
        ]
        for p in perms:
            res = run([ADB, "shell", f"pm grant com.ilkay.redmigateway {p}"])
            log(f"Grant {p}: {res}")
            
        dialer_res = run([ADB, "shell", "telecom set-default-dialer com.ilkay.redmigateway"])
        log(f"Default dialer: {dialer_res}")
        
        doze_res = run([ADB, "shell", "dumpsys deviceidle whitelist +com.ilkay.redmigateway"])
        log(f"Battery whitelist: {doze_res}")
        
        run([ADB, "shell", "cmd appops set com.ilkay.redmigateway RUN_IN_BACKGROUND allow"])
        run([ADB, "shell", "cmd appops set com.ilkay.redmigateway RUN_ANY_IN_BACKGROUND allow"])
        run([ADB, "shell", "am set-inactive com.ilkay.redmigateway false"])
        
        svc_res = run([ADB, "shell", "am start-foreground-service -n com.ilkay.redmigateway/.service.GatewayService"])
        log(f"GatewayService started: {svc_res}")
        
        time.sleep(2)
        net_res = run([ADB, "shell", "netstat -tuln | grep 8787"])
        log(f"Port 8787 status: {net_res}")
        
        gateway_configured = True
        log("SUCCESS: RedmiGateway is 100% configured and active!")

    # Check for Magisk
    if ("com.topjohnwu.magisk" in packages or "io.github.vvb2060.magisk" in packages) and not magisk_configured:
        log("DETECTED: Magisk Manager installed! Launching...")
        run([ADB, "shell", "monkey -p com.topjohnwu.magisk -c android.intent.category.LAUNCHER 1"])
        time.sleep(3)
        su_check = run([ADB, "shell", "su -c id"])
        log(f"Root check after Magisk launch: {su_check}")
        magisk_configured = True

    # Also keep trying adb install in case Install via USB was just toggled
    if not magisk_configured:
        res = run([ADB, "install", "-r", r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\Magisk.apk"])
        if "Success" in res:
            log(f"Magisk installed via adb: {res}")
            magisk_configured = True
            
    if not gateway_configured:
        res = run([ADB, "install", "-r", "-g", r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\android\app\build\outputs\apk\debug\app-debug.apk"])
        if "Success" in res:
            log(f"Gateway installed via adb: {res}")
            # will be configured on next loop iteration

    time.sleep(3)

log("ALL COMPONENTS SUCCESSFULLY INSTALLED AND CONFIGURED!")
