import subprocess
import time
import os

ADB = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"
FASTBOOT = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\fastboot.exe"
LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "nightly_log.txt")

def log(msg):
    t = time.strftime("%Y-%m-%d %H:%M:%S")
    entry = f"[{t}] {msg}"
    print(entry, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(entry + "\n")

log("Autonomous Device Watcher Daemon Started.")

last_fb = None
last_adb = None

while True:
    try:
        fb_p = subprocess.run([FASTBOOT, "devices"], capture_output=True, text=True, timeout=5)
        fb_out = fb_p.stdout.strip()
        
        adb_p = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=5)
        adb_out = adb_p.stdout.strip()

        if "17df6357" in fb_out and fb_out != last_fb:
            log(f"EVENT: Device 17df6357 detected in FASTBOOT mode:\n{fb_out}")
            last_fb = fb_out
            # Check lock status
            var_p = subprocess.run([FASTBOOT, "getvar", "unlocked"], capture_output=True, text=True)
            log(f"Lock status: {var_p.stdout.strip()} {var_p.stderr.strip()}")
            
        elif "17df6357" in adb_out and "device" in adb_out and adb_out != last_adb:
            log(f"EVENT: Device 17df6357 detected in ADB mode:\n{adb_out}")
            last_adb = adb_out
            # Eskiden burada kendiliginden `adb reboot bootloader` vardi: Redmi kabloya takildigi
            # anda, arama surerken bile telefonu yeniden baslatirdi. Izleyici yalniz kayit tutar.

    except Exception as e:
        log(f"Poll error: {e}")

    time.sleep(3)
