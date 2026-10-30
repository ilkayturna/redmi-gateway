import subprocess
import time
import os
import sys

ADB = r"C:\Users\ilkay.turna\platform-tools\adb.exe"
if not os.path.exists(ADB):
    ADB = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"

# ---------------------------------------------------------------------------
# DURDURUCU KONTROL (16.10.2026)
#
# Yollar bir ara C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\ govdesine
# isaret ediyordu. O dizindeki app-debug.apk 26.09.2026 tarihli; bu betik calistirilirsa
# ESKI APK'yi kurar ve o gunden beri yapilan duzeltmeleri (uyanma push'u, kapi loglamasi)
# SESSIZCE geri alir. Artik her sey BU depodan cozulur ve kurmadan once ne kurdugu yazilir.
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
STALE_MARKER = "elegant-borg"

MAGISK_APK = os.path.join(REPO_ROOT, "root", "Magisk.apk")
GATEWAY_APK = os.path.join(REPO_ROOT, "android", "app", "build", "outputs", "apk", "debug",
                           "app-debug.apk")
VERIFY_SCRIPT = os.path.join(SCRIPT_DIR, "verify_root.sh")
# adb install bu betikte dogrudan calismaz: 29.10.2026'da aktif arama sirasinda yapilan kurulum
# aramayi ROM'un telefon uygulamasina dusurdu ve ses iPhone'a donmedi. guvenli-kur.ps1 arama varken,
# durum okunamazken ya da birden fazla cihaz bagliyken kurmaz, kurduktan sonra dogrular.
GUARDED_INSTALL = os.path.join(SCRIPT_DIR, "device", "guvenli-kur.ps1")
LOG_FILE = os.path.join(REPO_ROOT, "docs", "autonomous_deployer.log")


def apk_is_sane(path, label):
    """Kurulacak dosya bu depodan mi, var mi, ve ne kadar eski?"""
    if STALE_MARKER in path:
        print(f"REFUSING {label}: {path}")
        print(f"          Eski proje dizininde ({STALE_MARKER}); o dosya gunler eski olabilir.")
        return False
    if not os.path.isfile(path):
        print(f"SKIPPING {label}: bulunamadi: {path}")
        return False
    age_days = (time.time() - os.path.getmtime(path)) / 86400.0
    size_mb = os.path.getsize(path) / (1024 * 1024)
    print(f"{label}: {path}")
    print(f"     {size_mb:.2f} MB, {age_days:.1f} gun once olusturuldu")
    if age_days > 7:
        print(f"UYARI: {label} {age_days:.0f} gunluk - guncel oldugundan emin ol.")
    return True

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
    if apk_is_sane(MAGISK_APK, "Magisk APK"):
        rc, out, err = run([ADB, "-s", serial, "install", "-r", MAGISK_APK])
        log(f"Magisk APK install: {out} {err}")
    else:
        log("Magisk APK not installed (path refused or missing; see message above)")

    log("--- Step 3: Installing RedmiGateway APK ---")
    if apk_is_sane(GATEWAY_APK, "Gateway APK"):
        rc, out, err = run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", GUARDED_INSTALL,
                            "-Seri", serial, "-Apk", GATEWAY_APK], timeout=240)
        log(f"Gateway APK install (exit {rc}): {out} {err}")
        if rc != 0:
            log("Gateway APK not installed; stopping before anything else touches the phone.")
            return
    else:
        log("Gateway APK NOT installed: refusing a stale or missing path. "
            "Build it first with .\\gradlew.bat assembleDebug")
        log("Continuing with permissions and service start for whatever is already installed.")
        
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
