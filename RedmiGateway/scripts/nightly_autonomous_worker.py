import subprocess
import time
import os
import sys

# Read-only status probe: it lists devices and asks fastboot whether the bootloader is
# unlocked. It does NOT unlock, flash or wipe anything.
#
# A MIFLASH_DIR / MIFLASH_EXE pair pointing at Documents\antigravity\elegant-borg was
# removed on 16.10.2026. It was never used, but a bootloader unlock tool path sitting in a
# script with "autonomous" in its name is not something to leave lying around; the stale
# checkout it pointed at still exists.
ADB = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"
FASTBOOT = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\fastboot.exe"

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "nightly_log.txt")

def log(msg):
    t = time.strftime("%Y-%m-%d %H:%M:%S")
    entry = f"[{t}] {msg}"
    print(entry)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(entry + "\n")

def run(cmd):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
        return p.stdout.strip() + "\n" + p.stderr.strip()
    except Exception as e:
        return str(e)

def check_devices():
    fb_out = run([FASTBOOT, "devices"])
    adb_out = run([ADB, "devices"])
    return fb_out, adb_out

log("==========================================")
log("  NIGHTLY AUTONOMOUS WORKER INITIALIZED   ")
log("==========================================")
fb, adb = check_devices()
log(f"Fastboot devices:\n{fb}")
log(f"ADB devices:\n{adb}")

if "17df6357" in fb:
    log("Device 17df6357 detected in Fastboot mode.")
    # Check lock state
    var_out = run([FASTBOOT, "getvar", "unlocked"])
    log(f"Unlocked var: {var_out}")
elif "17df6357" in adb:
    log("Device 17df6357 detected in ADB mode.")
else:
    log("Device not yet detected. Waiting for USB enumeration...")
