#!/usr/bin/env python3
"""
Dynamic Root Downlink Audio Node Scanner for Qualcomm QDSP6 (Redmi Note 8 / ginkgo)
Executed during an active Turkcell cellular call.
Discovers and tests all ALSA PCM capture devices dynamically without assuming D10c.
"""

import os
import sys
import re
import subprocess
import math
import struct

RATES = [16000, 8000, 48000]
DURATION_SEC = 2
OUT_DIR = "/data/local/tmp/downlink_probe"
os.makedirs(OUT_DIR, exist_ok=True)

def get_capture_devices():
    """Parse /proc/asound/pcm to discover all hardware capture nodes."""
    devices = []
    try:
        with open("/proc/asound/pcm", "r") as f:
            lines = f.readlines()
        for line in lines:
            # Format example: "00-10: MultiMedia8 (*) : : capture 1"
            m = re.match(r"^(\d+)-(\d+):\s+(.+?)\s+:\s+.*capture\s+(\d+)", line)
            if m:
                card = int(m.group(1))
                device = int(m.group(2))
                name = m.group(3).strip()
                devices.append({"card": card, "device": device, "name": name})
    except Exception as e:
        print(f"Error reading /proc/asound/pcm: {e}")
    return devices

def calculate_rms_and_peak(filepath):
    """Calculates RMS energy and peak amplitude from 16-bit mono PCM."""
    if not os.path.exists(filepath) or os.path.getsize(filepath) < 100:
        return 0.0, 0
    total_sq = 0.0
    peak = 0
    count = 0
    with open(filepath, "rb") as f:
        while True:
            raw = f.read(2)
            if len(raw) < 2:
                break
            val = struct.unpack("<h", raw)[0]
            abs_v = abs(val)
            if abs_v > peak:
                peak = abs_v
            total_sq += val * val
            count += 1
    if count == 0:
        return 0.0, 0
    rms = math.sqrt(total_sq / count)
    return rms, peak

def main():
    print("=== Qualcomm ALSA Downlink Probe Scanner ===")
    devices = get_capture_devices()
    print(f"Found {len(devices)} capture-capable ALSA PCM nodes.")
    
    candidates = []

    for dev in devices:
        card = dev["card"]
        device = dev["device"]
        name = dev["name"]
        print(f"\nProbing Card {card}, Device {device} ({name})...")

        for rate in RATES:
            wav_path = os.path.join(OUT_DIR, f"cap_c{card}d{device}_{rate}hz.wav")
            pcm_path = os.path.join(OUT_DIR, f"cap_c{card}d{device}_{rate}hz.pcm")
            
            # Execute tinycap via su
            cmd = f"tinycap {wav_path} -D {card} -d {device} -c 1 -r {rate} -b 16 -T {DURATION_SEC}"
            ret = subprocess.run(["su", "-c", cmd], capture_output=True, text=True, timeout=10)
            
            if not os.path.exists(wav_path):
                continue
            
            # Skip 44-byte WAV header for RMS
            with open(wav_path, "rb") as wf, open(pcm_path, "wb") as pf:
                wf.seek(44)
                pf.write(wf.read())
            
            rms, peak = calculate_rms_and_peak(pcm_path)
            print(f"  Rate: {rate} Hz -> RMS: {rms:.2f}, Peak: {peak}, Size: {os.path.getsize(wav_path)} bytes")
            
            if peak > 200 and rms > 10.0:
                print(f"  [ACTIVE AUDIO DETECTED!] Card {card}, Device {device} at {rate} Hz")
                candidates.append({
                    "card": card,
                    "device": device,
                    "name": name,
                    "rate": rate,
                    "rms": rms,
                    "peak": peak,
                    "file": wav_path
                })
            else:
                # Purge silent files
                if os.path.exists(wav_path): os.remove(wav_path)
                if os.path.exists(pcm_path): os.remove(pcm_path)

    print("\n=== DOWNLINK CANDIDATE SUMMARY ===")
    if candidates:
        for c in candidates:
            print(f"MATCH: Card {c['card']}, Device {c['device']} ({c['name']}) @ {c['rate']}Hz - Peak: {c['peak']}, RMS: {c['rms']:.2f}")
    else:
        print("No active audio captured. Ensure an active cellular call is running during probe!")

if __name__ == "__main__":
    main()
