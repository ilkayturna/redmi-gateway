"""Emulate the iPhone's media side of the Phone Bridge against the real Redmi gateway.

Authenticates like the iPhone app (pairing token read from the Redmi over root adb, never
printed), optionally dials, requests call audio, streams 20 ms 16 kHz PCM frames over UDP and
counts the downlink frames it receives. Scenarios exercise handoff, fallback and re-arm.
Only counters are kept; no audio is stored.

usage: python iphone_emulator.py --host 192.168.1.147 --dial 532 --scenario handoff
"""
import argparse
import asyncio
import json
import math
import os
import re
import socket
import struct
import subprocess
import sys
import time

import websockets

ADB = os.environ.get("ADB", r"C:\Users\ilkay.turna\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "17df6357")
PREFS = "/data/data/com.ilkay.redmigateway/shared_prefs/redmi_gateway_prefs.xml"


def pairing_token():
    xml = subprocess.run([ADB, "-s", SERIAL, "shell", "cat " + PREFS], capture_output=True, text=True).stdout
    m = re.search(r'name="pairing_token">([^<]+)<', xml)
    if not m:
        sys.exit("pairing token not readable")
    return m.group(1)


class Media:
    def __init__(self, host, port, session):
        self.addr = (host, port)
        self.session = session
        self.seq = 0
        self.sending = False
        self.tone = False
        self.rx = 0
        self.rx_nonzero = 0
        self.phase = 0.0
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setblocking(False)

    def packet(self, ptype, pcm=b""):
        hdr = struct.pack(">IHBB", self.session, self.seq & 0xFFFF, ptype, 0)
        self.seq += 1
        return hdr + pcm

    def frame(self):
        if not self.tone:
            return bytes(640)
        out = bytearray()
        for _ in range(320):
            out += struct.pack("<h", int(3000 * math.sin(self.phase)))
            self.phase += 2 * math.pi * 440 / 16000
        return bytes(out)

    async def run(self):
        loop = asyncio.get_running_loop()
        next_at = time.perf_counter()
        last_keepalive = 0
        while True:
            now = time.perf_counter()
            if self.sending:
                self.sock.sendto(self.packet(1, self.frame()), self.addr)
            elif now - last_keepalive > 1:
                self.sock.sendto(self.packet(0), self.addr)
                last_keepalive = now
            try:
                while True:
                    data = self.sock.recv(2048)
                    if len(data) >= 8 and data[6] == 1:
                        self.rx += 1
                        if any(data[8:]):
                            self.rx_nonzero += 1
            except BlockingIOError:
                pass
            next_at += 0.02
            await asyncio.sleep(max(0, next_at - time.perf_counter()))


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.1.147")
    ap.add_argument("--dial")
    ap.add_argument("--scenario", default="handoff")
    args = ap.parse_args()

    events = []
    async with websockets.connect(f"ws://{args.host}:8787", ping_interval=None) as ws:
        inbox = asyncio.Queue()

        async def reader():
            async for raw in ws:
                msg = json.loads(raw)
                if msg.get("type") == "audio_state":
                    print(f"  <- audio_state owner={msg.get('owner')} running={msg.get('running')} detail={msg.get('detail')}")
                    events.append(msg)
                elif msg.get("type") == "call":
                    print(f"  <- call {msg.get('state')}")
                await inbox.put(msg)

        stash = {}

        async def wait_for(kind, timeout=15):
            if kind in stash:
                return stash.pop(kind)
            end = time.time() + timeout
            while time.time() < end:
                try:
                    msg = await asyncio.wait_for(inbox.get(), max(0.1, end - time.time()))
                except asyncio.TimeoutError:
                    break
                if msg.get("type") == kind:
                    return msg
                if msg.get("type") == "audio_ready":
                    stash["audio_ready"] = msg
            return None

        task = asyncio.create_task(reader())
        await wait_for("hello")
        await ws.send(json.dumps({"type": "auth", "token": pairing_token()}))
        if not (await wait_for("auth_result") or {}).get("success"):
            sys.exit("auth failed")
        print("authenticated")
        if args.dial:
            await ws.send(json.dumps({"type": "dial", "number": args.dial}))
        if args.scenario == "race":
            # iPhone build 33 requests audio before Telecom has created the call.
            await ws.send(json.dumps({"type": "audio_start"}))
        while True:
            msg = await wait_for("call", 60)
            if msg is None:
                sys.exit("no call")
            if msg.get("state") in ("dialing", "active"):
                call_id = msg.get("callId")
                break
        if args.scenario != "race":
            await ws.send(json.dumps({"type": "audio_start"}))
        ready = await wait_for("audio_ready")
        media = Media(args.host, ready["port"], ready["session"])
        mtask = asyncio.create_task(media.run())
        print(f"audio_ready session ok port={ready['port']}")

        async def report(label, seconds):
            for _ in range(seconds):
                r0, n0 = media.rx, media.rx_nonzero
                await asyncio.sleep(1)
                print(f"  [{label}] downlink {media.rx - r0}/s nonzero {media.rx_nonzero - n0}/s sending={media.sending}")

        media.sending = True
        await report("handoff", 6)
        if args.scenario == "race":
            await report("race_hold", 20)
        if args.scenario == "soak":
            for i in range(12):
                media.tone = (i % 2 == 0)
                await report("soak", 5)
        media.tone = True
        await report("iphone_tone", 5)
        if args.scenario in ("handoff", "fallback"):
            media.sending = False  # iPhone microphone stops; keepalives continue
            await report("iphone_silent", 4)
            print("re-requesting audio")
            await ws.send(json.dumps({"type": "audio_start"}))
            await wait_for("audio_ready")
            media.sending = True
            await report("rearmed", 6)
            await ws.send(json.dumps({"type": "audio_to_redmi"}))
            await report("manual_redmi", 3)
        await ws.send(json.dumps({"type": "hangup", "callId": call_id}))
        await asyncio.sleep(2)
        mtask.cancel()
        task.cancel()
    print("owners:", [(e.get("owner"), e.get("detail")) for e in events])


if __name__ == "__main__":
    asyncio.run(main())
