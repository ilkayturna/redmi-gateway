import subprocess
import time
import os
import sys

ADB = r"C:\Users\ilkay.turna\platform-tools\adb.exe"
if not os.path.exists(ADB):
    ADB = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"

MAGISK_APK = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\root\Magisk.apk"
GATEWAY_APK = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\android\app\build\outputs\apk\debug\app-debug.apk"
VERIFY_SCRIPT = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\scripts\verify_root.sh"
LOG_FILE = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\docs\autonomous_deployer.log"

def log(msg):
    t = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{t}] {msg}"
    print(line, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")

def run(cmd, timeout=30):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout)
        return p.returncode, p.stdout.strip(), p.stderr.strip()
    except Exception as e:
        return -1, "", str(e)

def wait_for_adb():
    log("Waiting for ADB device authorization...")
    while True:
        rc, out, err = run([ADB, "devices"])
        lines = [l.strip() for l in out.splitlines() if l.strip()]
        devs = [l for l in lines[1:] if "\t" in l or " " in l]
        
        for d in devs:
            parts = d.split()
            serial, state = parts[0], parts[1]
            if state == "device":
                log(f"Device ready in ADB mode: {serial}")
                return serial
            elif state == "unauthorized":
                log(f"Device {serial} is UNAUTHORIZED. Waiting for user to tap 'Allow' on phone...")
            elif state == "offline":
                log(f"Device {serial} is offline. Re-enumerating...")
                
        time.sleep(2)

def deploy():
    log("==================================================")
    log("  REDMI NOTE 8 AUTONOMOUS DEPLOYER STARTED        ")
    log("==================================================")
    
    serial = wait_for_adb()
    
    log("--- Step 1: Testing Root Status ---")
    rc, out, err = run([ADB, "-s", serial, "shell", "su -c id"])
    log(f"su -c id output: {out} (err: {err})")
    
    if "uid=0" in out:
        log("ROOT SUCCESSFUL: Device has active root shell (uid=0)!")
    else:
        log(f"Root direct test: {out}. Installing Magisk Manager...")
    
    log("--- Step 2: Installing Magisk Manager APK ---")
    if os.path.exists(MAGISK_APK):
        rc, out, err = run([ADB, "-s", serial, "install", "-r", MAGISK_APK])
        log(f"Magisk APK install: {out} {err}")
    else:
        log(f"WARNING: Magisk APK not found at {MAGISK_APK}")
        
    log("--- Step 3: Installing RedmiGateway APK ---")
    if os.path.exists(GATEWAY_APK):
        rc, out, err = run([ADB, "-s", serial, "install", "-r", "-g", GATEWAY_APK])
        log(f"Gateway APK install: {out} {err}")
    else:
        log(f"WARNING: Gateway APK not found at {GATEWAY_APK}")
        
    log("--- Step 4: Granting Permissions & Default Dialer ---")
    perms = [
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS"
    ]
    for p in perms:
        rc, out, err = run([ADB, "-s", serial, "shell", "pm", "grant", "com.ilkay.redmigateway", p])
        log(f"pm grant {p}: {out} {err}")
        
    # Set default dialer
    rc, out, err = run([ADB, "-s", serial, "shell", "telecom", "set-default-dialer", "com.ilkay.redmigateway"])
    log(f"telecom set-default-dialer: {out} {err}")
    
    # Battery optimization whitelist
    rc, out, err = run([ADB, "-s", serial, "shell", "dumpsys", "deviceidle", "whitelist", "+com.ilkay.redmigateway"])
    log(f"deviceidle whitelist: {out} {err}")
    
    # Background execution appops
    run([ADB, "-s", serial, "shell", "cmd", "appops", "set", "com.ilkay.redmigateway", "RUN_IN_BACKGROUND", "allow"])
    run([ADB, "-s", serial, "shell", "cmd", "appops", "set", "com.ilkay.redmigateway", "RUN_ANY_IN_BACKGROUND", "allow"])
    run([ADB, "-s", serial, "shell", "am", "set-inactive", "com.ilkay.redmigateway", "false"])
    
    log("--- Step 5: Executing Root Audio & ALSA Verification ---")
    if os.path.exists(VERIFY_SCRIPT):
        run([ADB, "-s", serial, "push", VERIFY_SCRIPT, "/data/local/tmp/verify_root.sh"])
        run([ADB, "-s", serial, "shell", "chmod +x /data/local/tmp/verify_root.sh"])
        rc, out, err = run([ADB, "-s", serial, "shell", "sh /data/local/tmp/verify_root.sh"])
        log(f"verify_root output: {out} {err}")
        
    log("--- Step 6: Starting Gateway Service ---")
    rc, out, err = run([ADB, "-s", serial, "shell", "am", "start-foreground-service", "-n", "com.ilkay.redmigateway/.service.GatewayService"])
    log(f"start-foreground-service: {out} {err}")
    
    time.sleep(3)
    rc, out, err = run([ADB, "-s", serial, "shell", "netstat -tuln"])
    log(f"Gateway listening check: {out}")
    
    log("==================================================")
    log("  ALL RESTORATION STEPS COMPLETED AUTOMATICALLY!  ")
    log("==================================================")

if __name__ == "__main__":
    deploy()
