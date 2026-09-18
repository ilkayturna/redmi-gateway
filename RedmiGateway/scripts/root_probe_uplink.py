#!/usr/bin/env python3
"""
Root Uplink Audio Injection Test for Qualcomm QDSP6 (Redmi Note 8 / ginkgo)
Injects a test tone into MultiMedia1 / Incall_Music mixer control during an active cellular call.
Safely saves and restores original mixer state immediately after playback.
"""

import subprocess
import time
import sys
import os

MIXER_CONTROL = "Incall_Music Audio Mixer MultiMedia1"
CARD = 0
DEVICE = 1  # MultiMedia1
TONE_WAV = "/data/local/tmp/test_tone_1000hz.wav"

def get_mixer_value(control):
    """Query current tinymix control value."""
    cmd = f"tinymix -D {CARD} '{control}'"
    res = subprocess.run(["su", "-c", cmd], capture_output=True, text=True)
    out = res.stdout.strip()
    print(f"Current mixer state for [{control}]: {out}")
    # Example output: "Incall_Music Audio Mixer MultiMedia1: 0" or "> 0"
    if "1" in out:
        return 1
    return 0

def set_mixer_value(control, val):
    """Set tinymix control value."""
    cmd = f"tinymix -D {CARD} '{control}' {val}"
    subprocess.run(["su", "-c", cmd], check=True)
    print(f"Set [{control}] -> {val}")

def main():
    print("=== Qualcomm Cellular Uplink Injection Test ===")
    if not os.path.exists(TONE_WAV):
        print(f"Error: {TONE_WAV} not found on device! Push it first.")
        sys.exit(1)

    initial_val = get_mixer_value(MIXER_CONTROL)
    
    try:
        print("\n1. Enabling Incall_Music route to MultiMedia1...")
        set_mixer_value(MIXER_CONTROL, 1)
        
        # Verify
        current = get_mixer_value(MIXER_CONTROL)
        if current != 1:
            print("Warning: Mixer control did not confirm value 1!")

        print(f"\n2. Playing test tone into Card {CARD}, Device {DEVICE} (MultiMedia1) via tinyplay...")
        play_cmd = f"tinyplay {TONE_WAV} -D {CARD} -d {DEVICE}"
        t0 = time.time()
        ret = subprocess.run(["su", "-c", play_cmd], capture_output=True, text=True)
        elapsed = time.time() - t0
        print(f"Playback finished in {elapsed:.2f}s. Exit code: {ret.returncode}")
        if ret.stdout: print("stdout:", ret.stdout.strip())
        if ret.stderr: print("stderr:", ret.stderr.strip())

    finally:
        print(f"\n3. Restoring original mixer state -> {initial_val}...")
        set_mixer_value(MIXER_CONTROL, initial_val)
        verify_val = get_mixer_value(MIXER_CONTROL)
        print(f"Mixer state restored: {verify_val == initial_val}")

    print("\nUplink injection test completed successfully. Check if remote caller heard the 1000Hz tone!")

if __name__ == "__main__":
    main()
