"""Step through the Redmi's call-audio routing during one live cellular call.

Connects to the gateway like the iPhone does (pairing token read from the Redmi over
root adb, never printed), optionally places the call, and toggles the Telephony Rx
extraction, the Telephony Tx injection and the Telecom route in timed phases while
recording the Qualcomm HAL's routing decisions. Only statistics are kept; no audio.

usage: python call_routing_lab.py --host 192.168.1.147 [--dial NUMBER] [--out DIR]
"""
import argparse
import asyncio
import json
import os
import re
import subprocess
import sys
import time

import bridge_client
from bridge_client import BridgeClient

ADB = os.environ.get("ADB", r"C:\Users\ilkay.turna\platform-tools\adb.exe")
# USB serial by default; ADB_SERIAL (or adb's own ANDROID_SERIAL) for adb over Wi-Fi, e.g. 192.168.1.111:5555.
SERIAL = os.environ.get("ADB_SERIAL") or os.environ.get("ANDROID_SERIAL") or "17df6357"
# Running ALSA PCM substreams and their hardware parameters (read-only).
ASOUND = ("for f in /proc/asound/card0/pcm*/sub0/status; do "
          "if grep -q RUNNING $f; then d=${f%/sub0/status}; echo \"== $d $(cat $d/info | grep -E '^id:' )\"; "
          "grep -E 'rate|channels|format' $d/sub0/hw_params; fi; done")

PLANS = {
    "v1": [
        ("baseline", [], 6),
        ("downlink_open", ["downlink_open"], 7),
        ("uplink_open", ["uplink_open"], 8),
        ("route_speaker", ["route_speaker"], 7),
        ("route_earpiece", ["route_earpiece"], 7),
        ("uplink_close", ["uplink_close"], 7),
        ("uplink_reopen", ["uplink_open"], 7),
        ("close_all", ["close_all"], 4),
    ],
    "v2": [
        ("baseline", [], 3),
        ("downlink_open", ["downlink_open"], 4),
        ("uplink_silence", ["silence", "uplink_open"], 4),
        ("reapply_endpoint", ["reapply_endpoint"], 5),
        ("preferred_device", ["preferred_device"], 5),
        ("comm_device", ["comm_device"], 5),
        ("clear_comm_device", ["clear_comm_device"], 3),
        ("speaker_then_earpiece", ["route_speaker"], 3),
        ("earpiece", ["route_earpiece"], 5),
        ("proxy_silence", [], 12),
        ("proxy_tone", ["tone"], 6),
        ("proxy_silence2", ["silence"], 10),
        ("uplink_close", ["uplink_close"], 4),
        ("reapply_after_close", ["reapply_endpoint"], 5),
        ("toggle_after_close", ["route_speaker"], 2),
        ("earpiece_after_close", ["route_earpiece"], 5),
        ("close_all", ["close_all"], 2),
    ],
    "v3": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open"], 3),
        ("uplink_silence", ["silence", "uplink_open"], 2),
        ("to_speaker", ["route_speaker"], 3),
        ("to_earpiece_proxy", ["route_earpiece"], 4),
        ("voc_rec_proxy", [], 3),
        ("cap_unprocessed", ["proxy_unprocessed"], 6),
        ("cap_recognition", ["proxy_recognition"], 6),
        ("cap_mic", ["proxy_mic"], 6),
        ("cap_communication", ["proxy_communication"], 6),
        ("cap_default", ["proxy_default"], 6),
        ("cap_voice_call_src", ["voice_call_source"], 6),
        ("close_uplink", ["uplink_close"], 2),
        ("back_speaker", ["route_speaker"], 2),
        ("back_earpiece", ["route_earpiece"], 4),
        ("cap_unprocessed_handset", ["proxy_unprocessed"], 5),
        ("close_all", ["close_all"], 2),
    ],
    "v4": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open"], 3),
        ("music_silence", ["silence", "music_uplink_open"], 6),
        ("music_tone", ["tone"], 7),
        ("music_silence2", ["silence"], 5),
        ("music_tone_micmute", ["mic_mute_on", "tone"], 7),
        ("music_silence_micmute", ["silence"], 4),
        ("mic_unmute", ["mic_mute_off"], 3),
        ("music_close", ["uplink_close"], 2),
        ("proxycap_first", ["proxy_unprocessed"], 2),
        ("platform_uplink", ["uplink_open"], 2),
        ("to_speaker", ["route_speaker"], 3),
        ("to_earpiece", ["route_earpiece"], 8),
        ("close_all", ["close_all"], 1),
        ("back_speaker", ["route_speaker"], 2),
        ("back_earpiece", ["route_earpiece"], 3),
    ],
    "v5": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open"], 3),
        ("proxycap_first", ["proxy_unprocessed"], 1),
        ("platform_uplink", ["silence", "uplink_open"], 1),
        ("to_speaker", ["route_speaker"], 3),
        ("to_earpiece", ["route_earpiece"], 3),
        ("proxy_listen", [], 22),
        ("proxy_tone", ["tone"], 6),
        ("proxy_silence", ["silence"], 10),
        ("close_uplink", ["uplink_close"], 1),
        ("back_speaker", ["route_speaker"], 2),
        ("back_earpiece", ["route_earpiece"], 3),
        ("close_all", ["close_all"], 1),
    ],
    "v6": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open"], 3),
        ("asound_handset", ["!asound"], 1),
        ("proxy48_first", ["proxy48_unprocessed"], 1),
        ("platform_uplink", ["silence", "uplink_open"], 1),
        ("to_speaker", ["route_speaker"], 3),
        ("to_earpiece", ["route_earpiece"], 4),
        ("asound_proxy", ["!asound"], 1),
        ("proxy_listen", [], 18),
        ("proxy_tone", ["tone"], 5),
        ("proxy_silence", ["silence"], 6),
        ("close_uplink", ["uplink_close"], 1),
        ("back_speaker", ["route_speaker"], 2),
        ("back_earpiece", ["route_earpiece"], 2),
        ("close_all", ["close_all"], 1),
    ],
    "v7": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open", "!mixstate"], 3),
        ("platform_uplink", ["silence", "uplink_open"], 1),
        ("to_speaker", ["route_speaker"], 3),
        ("to_earpiece_proxy", ["route_earpiece"], 4),
        ("proxy_state", ["!mixstate"], 3),
        ("add_codec_rx", ['!mix set "RX_CDC_DMA_RX_0_Voice Mixer VoiceMMode2" 1 1', "!mixstate"], 10),
        ("tone", ["tone"], 6),
        ("silence", ["silence"], 10),
        ("drop_afe_rx", ['!mix set "AFE_PCM_RX_Voice Mixer VoiceMMode2" 0 0', "!mixstate"], 6),
        ("tone2", ["tone"], 5),
        ("silence2", ["silence"], 6),
        ("close_uplink", ["uplink_close"], 1),
        ("back_speaker", ["route_speaker"], 2),
        ("back_earpiece", ["route_earpiece", "!mixstate"], 3),
        ("close_all", ["close_all"], 1),
    ],
    "v8": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open"], 3),
        ("platform_uplink", ["silence", "uplink_open"], 1),
        ("to_speaker", ["route_speaker"], 3),
        ("to_earpiece_proxy", ["route_earpiece", "!mixstate"], 3),
        ("proxy_listen", ["!asound"], 12),
        ("proxy_tone", ["tone"], 7),
        ("proxy_silence", ["silence"], 12),
        ("close_uplink", ["uplink_close"], 1),
        ("back_speaker", ["route_speaker"], 2),
        ("back_earpiece", ["route_earpiece"], 3),
        ("close_all", ["close_all"], 1),
    ],
    "v9": [
        ("baseline", [], 2),
        ("voc_rec_handset", ["downlink_open"], 3),
        ("music_silence", ["silence", "music_uplink_open"], 5),
        ("music_tone", ["tone"], 7),
        ("music_silence2", ["silence"], 7),
        ("music_tone_micmute", ["mic_mute_on", "tone"], 7),
        ("music_silence_micmute", ["silence"], 8),
        ("music_tone_micmute2", ["tone"], 6),
        ("mic_unmute", ["silence", "mic_mute_off"], 3),
        ("close_all", ["close_all"], 1),
    ],
    "v10": [
        ("baseline", [], 2),
        ("music_silence", ["silence", "music_uplink_open"], 2),
        ("ulrec_silence_micopen", ["ulrec_open"], 4),
        ("ulrec_tone_micopen", ["tone"], 4),
        ("ulrec_tone_globalmute", ["mic_mute_on"], 4),
        ("ulrec_silence_globalmute", ["silence"], 3),
        ("globalmute_off", ["mic_mute_off"], 2),
        ("ulrec_silence_devmute", ["device_mute_on"], 4),
        ("ulrec_tone_devmute", ["tone"], 4),
        ("devmute_off_tone", ["device_mute_off"], 3),
        ("devmute_off_silence", ["silence"], 3),
        ("voc_rec_dl_check", ["downlink_open"], 3),
        ("close_all", ["close_all"], 1),
    ],
    "v12": [
        ("baseline", [], 2),
        ("dl", ["downlink_open"], 3),
        ("music", ["silence", "music_uplink_open"], 3),
        ("global_micmute", ["mic_mute_on"], 5),
        ("voice_stream_mute", ["voice_stream_mute_on"], 6),
        ("voice_stream_unmute", ["voice_stream_mute_off"], 3),
        ("rx_devmute", ["rx_devmute_on"], 6),
        ("rx_devmute_off", ["rx_devmute_off"], 3),
        ("mic_unmute", ["mic_mute_off"], 2),
        ("close_all", ["close_all"], 1),
    ],
}


def adb(*args, **kw):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, **kw)


ROUTE_RE = re.compile(r"(apply mixer and update path: (?:voicemmode\d-call|incall).*|LAB_OP \S+|LAB_STEP .*|start_(?:input|output)_stream: enter.*|select_devices for use case \((?:incall|afe)[^)]*\)|Capture patch.*|[Mm]ute.*)")


async def tail_routes(path):
    """Print the HAL's voice-call path changes as they happen."""
    pos = 0
    while True:
        await asyncio.sleep(0.25)
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                fh.seek(pos)
                chunk = fh.read()
                pos = fh.tell()
        except FileNotFoundError:
            continue
        for line in chunk.splitlines():
            m = ROUTE_RE.search(line)
            if m:
                print("      HAL", line[6:18], m.group(1))


async def bargein_trials(request, record):
    """Far-end check: does the IVR stop talking when our injected tone reaches it?"""
    async def op(name):
        return await request({"type": "lab", "op": name}, "lab_result")

    async def rms():
        st = (await op("stats")).get("status", {})
        return st.get("dlRms", 0.0), st.get("dlFrames", 0)

    await op("downlink_open")
    await op("silence")
    await op("music_uplink_open")
    for condition, on, off in (("mic_open", None, None),
                               ("global_mute", "mic_mute_on", "mic_mute_off"),
                               ("device_mute", "device_mute_on", "device_mute_off"),
                               ("mic_open_again", None, None)):
        if on:
            await op(on)
        results = []
        for trial in range(3):
            # wait for the IVR to be talking
            talking = 0
            deadline = time.time() + 20
            await rms()
            while time.time() < deadline and talking < 2:
                await asyncio.sleep(0.25)
                level, _ = await rms()
                talking = talking + 1 if level > 1500 else 0
            if talking < 2:
                results.append("no-speech")
                continue
            await op("tone")
            series = []
            for _ in range(8):
                await asyncio.sleep(0.25)
                series.append(round((await rms())[0]))
            await op("silence")
            quiet = sum(1 for v in series[2:] if v < 300)
            verdict = "BARGE-IN" if quiet >= 4 else "continued"
            results.append(verdict)
            record("bargein", condition=condition, trial=trial, series=series, verdict=verdict)
            print(f"  {condition:15s} trial {trial}: {verdict:9s} rms after tone: {series}")
            await asyncio.sleep(2.5)
        if off:
            await op(off)
        print(f"== {condition}: {results}")
    await op("close_all")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.1.147")
    ap.add_argument("--dial")
    ap.add_argument("--out", default=".")
    ap.add_argument("--plan", default="v2")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    events_path = os.path.join(args.out, f"lab-{stamp}-events.jsonl")
    hal_path = os.path.join(args.out, f"lab-{stamp}-logcat.txt")

    adb("logcat", "-G", "16M")
    adb("logcat", "-c")
    logcat = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "threadtime"],
                              stdout=open(hal_path, "w", encoding="utf-8", errors="replace"),
                              stderr=subprocess.DEVNULL)
    events = open(events_path, "w", encoding="utf-8")

    def record(kind, **data):
        row = {"t": round(time.time(), 3), "kind": kind, **data}
        events.write(json.dumps(row) + "\n")
        events.flush()
        return row

    # The lab hooks are off unless switched on over adb; they expire on their own.
    bridge_client.enable_lab_mode(True)
    client = await BridgeClient.connect(args.host)
    async with client.ws:
        async def request(payload, want):
            await client.send(payload)
            while True:
                msg = await client.recv(10)
                if msg.get("type") == "call":
                    record("call", state=msg.get("state"))
                if msg.get("type") == want:
                    return msg

        print(f"authenticated (protocol {client.protocol})")

        if args.dial:
            await client.send({"type": "dial", "number": args.dial})
            print("dial sent")
        print("waiting for an active call ...")
        deadline = time.time() + 90
        while time.time() < deadline:
            state = await request({"type": "get_state"}, "state")
            call = state.get("call") or {}
            if call.get("state") == "active":
                break
            await asyncio.sleep(1)
        else:
            sys.exit("no active call")
        record("call_active")
        print("call active; running phases")
        await asyncio.sleep(2)

        tail = asyncio.create_task(tail_routes(hal_path))
        if args.plan == "bargein":
            await bargein_trials(request, record)
            PLAN = []
        else:
            PLAN = PLANS[args.plan]
        for name, ops, seconds in PLAN:
            record("phase", name=name)
            for op in ops:
                if op.startswith("!mix "):
                    out = adb("shell", "/data/local/tmp/mixctl " + op[5:]).stdout.strip()
                    record("mix", phase=name, cmd=op[5:], out=out)
                    print("      MIX", out)
                    continue
                if op == "!mixstate":
                    out = adb("shell", "/data/local/tmp/mixctl list VoiceMMode2 | grep -v '= 0 0'").stdout.strip()
                    record("mixstate", phase=name, out=out)
                    print("      MIXSTATE", out.replace(chr(10), " | "))
                    continue
                if op == "!asound":
                    dump = adb("shell", ASOUND).stdout
                    record("asound", phase=name, dump=dump)
                    print(dump)
                    continue
                res = await request({"type": "lab", "op": op}, "lab_result")
                record("op", op=op, ok=res.get("ok"), error=res.get("error"))
                print(f"[{name}] {op}: ok={res.get('ok')} err={res.get('error')}")
            for _ in range(seconds):
                await asyncio.sleep(1)
                res = await request({"type": "lab", "op": "stats"}, "lab_result")
                st = res.get("status", {})
                record("stats", phase=name, **st)
                print(f"  {name:15s} dl={st.get('dlFrames'):>3} zero={st.get('dlZeroFrames'):>3} "
                      f"rms={st.get('dlRms', 0):7.1f} tone={st.get('toneRatio', 0):4.2f} ul={st.get('ulFrames'):>3} "
                      f"maxW={st.get('ulMaxWriteMs'):>3} routed={st.get('ulRoutedType')} "
                      f"mode={st.get('mode')} call={st.get('callState')}")
                if st.get("callState") in ("idle", "disconnected"):
                    break

        if args.dial:
            state = await request({"type": "get_state"}, "state")
            call = state.get("call") or {}
            if call.get("callId"):
                await client.send({"type": "hangup", "callId": call["callId"]})
                record("hangup_sent")
                print("hangup sent")
        await asyncio.sleep(2)

        tail.cancel()
    logcat.terminate()
    events.close()
    print("events:", events_path)
    print("logcat:", hal_path)


if __name__ == "__main__":
    asyncio.run(main())
