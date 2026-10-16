#!/usr/bin/env python3
"""
Root Uplink Audio Injection Test for Qualcomm QDSP6 (Redmi Note 8 / ginkgo)
Injects a test tone into the in-call music mixer control during an active cellular call.
Safely saves and restores original mixer state immediately after playback.

IMPORTANT - SIM slot: this phone runs its SIM in slot 2, so the HAL uses the VoiceMMode2
session and the uplink lands on the SECOND in-call music path. The controls differ:
    slot 1: "Incall_Music Audio Mixer MultiMedia1"   -> PCM 1
    slot 2: "Incall_Music_2 Audio Mixer MultiMedia9" -> PCM 27   <- this device
The vendor fix (scripts/device/vendor_audio_fix.py) maps slot 2 to PCM 27 precisely because
it is otherwise unmapped and every write fails pcm_prepare, restarting the stream about
fourteen times a second. Defaults below target slot 2; override with --control/--device.
"""

import argparse
import os
import subprocess
import sys
import time

DEFAULT_CONTROL = "Incall_Music_2 Audio Mixer MultiMedia9"
DEFAULT_DEVICE = 27          # MultiMedia9, matching the vendor fix
CARD = 0
TONE_WAV = "/data/local/tmp/test_tone_1000hz.wav"


def parse_mixer_value(out):
    """Read the value from tinymix output, or None when it is not readable.

    tinymix prints "<Control Name>: 0" or "> 1" for the active value. The old check was
    `if "1" in out`, which always matched because the control NAME ends in MultiMedia1 -
    so the tool saved 1 no matter what, then "restored" 1 over the real value. Reading
    from the end avoids the name entirely, and None means "do not touch it".
    """
    tokens = out.replace(":", " ").replace(">", " ").split()
    for tok in reversed(tokens):
        if tok in ("0", "1"):
            return int(tok)
    return None


def get_mixer_value(control):
    """Query current tinymix control value, or None when it cannot be read."""
    cmd = f"tinymix -D {CARD} '{control}'"
    res = subprocess.run(["su", "-c", cmd], capture_output=True, text=True)
    out = res.stdout.strip()
    print(f"Current mixer state for [{control}]: {out}")
    return parse_mixer_value(out)


def set_mixer_value(control, val):
    """Set tinymix control value."""
    cmd = f"tinymix -D {CARD} '{control}' {val}"
    subprocess.run(["su", "-c", cmd], check=True)
    print(f"Set [{control}] -> {val}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--control", default=DEFAULT_CONTROL,
                    help="mixer control; default targets SIM slot 2 (this device)")
    ap.add_argument("--device", type=int, default=DEFAULT_DEVICE,
                    help="PCM device for tinyplay; default 27 matches the vendor fix")
    args = ap.parse_args()
    control, device = args.control, args.device

    print("=== Qualcomm Cellular Uplink Injection Test ===")
    print(f"    control=[{control}]  pcm device={device}")
    if not os.path.exists(TONE_WAV):
        print(f"Error: {TONE_WAV} not found on device! Push it first.")
        sys.exit(1)

    initial_val = get_mixer_value(control)
    if initial_val is None:
        print("Error: could not read the mixer value, so it cannot be restored safely. "
              "Refusing to change it.")
        sys.exit(2)
    print(f"    original value = {initial_val} (will be restored)")

    try:
        print("\n1. Enabling the in-call music route...")
        set_mixer_value(control, 1)

        current = get_mixer_value(control)
        if current != 1:
            print(f"Warning: mixer control did not confirm value 1 (read {current})")

        print(f"\n2. Playing test tone into Card {CARD}, Device {device} via tinyplay...")
        play_cmd = f"tinyplay {TONE_WAV} -D {CARD} -d {device}"
        t0 = time.time()
        ret = subprocess.run(["su", "-c", play_cmd], capture_output=True, text=True)
        elapsed = time.time() - t0
        print(f"Playback finished in {elapsed:.2f}s. Exit code: {ret.returncode}")
        if ret.stdout:
            print("stdout:", ret.stdout.strip())
        if ret.stderr:
            print("stderr:", ret.stderr.strip())

    finally:
        print(f"\n3. Restoring original mixer state -> {initial_val}...")
        set_mixer_value(control, initial_val)
        verify_val = get_mixer_value(control)
        print(f"Mixer state restored: {verify_val == initial_val} "
              f"(expected {initial_val}, read {verify_val})")

    print("\nUplink injection test completed. Check whether the remote caller heard the tone.")


if __name__ == "__main__":
    main()
