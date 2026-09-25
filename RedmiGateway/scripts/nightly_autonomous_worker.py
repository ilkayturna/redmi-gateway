import subprocess
import time
import os
import sys

ADB = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"
FASTBOOT = r"C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\fastboot.exe"
MIFLASH_DIR = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\miflash_unlock"
MIFLASH_EXE = os.path.join(MIFLASH_DIR, "miflash_unlock.exe")

LOG_FILE = r"C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\RedmiGateway\docs\nightly_log.txt"

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
