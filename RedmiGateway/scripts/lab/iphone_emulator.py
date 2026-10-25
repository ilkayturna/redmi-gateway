"""Emulate the iPhone's side of the Phone Bridge against the real Redmi gateway.

Authenticates like the iPhone app (keys and codes read from the Redmi over root adb, never
printed), optionally dials, requests call audio, streams 20 ms 16 kHz PCM frames over UDP and
counts the downlink frames it receives. Scenarios exercise handoff, fallback, re-arm, sync and
the protocol 2 security checks. Only counters are kept; no audio is stored.

--pair makes a fresh protocol 2 pairing for the run and restores the Redmi's previous pairing
afterwards, so the real iPhone keeps working.

usage: python iphone_emulator.py --dial 532 --scenario handoff [--pair]
       python iphone_emulator.py --scenario security --pair
       python iphone_emulator.py --scenario sync
"""
import argparse
import asyncio
import base64
import json
import math
import os
import socket
import struct
import sys
import time

import bridge_client
from bridge_client import BridgeClient, SealedMedia


class Media:
    def __init__(self, host, port, session, key):
        self.addr = (host, port)
        self.framing = SealedMedia(session, key)
        self.sending = False
        self.tone = False
        self.deaf = False  # keep sending but stop reporting received downlink
        self.last_rx_at = 0.0
        self.rx = 0
        self.rx_nonzero = 0
        self.phase = 0.0
        self.sock = socket.socket(socket.AF_INET6 if ":" in host else socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setblocking(False)

    def frame(self):
        if not self.tone:
            return bytes(640)
        out = bytearray()
        for _ in range(320):
            out += struct.pack("<h", int(3000 * math.sin(self.phase)))
            self.phase += 2 * math.pi * 440 / 16000
        return bytes(out)

    async def run(self):
        next_at = time.perf_counter()
        last_keepalive = 0
        while True:
            now = time.perf_counter()
            hearing = not self.deaf and now - self.last_rx_at < 0.5
            if self.sending:
                self.sock.sendto(self.framing.packet(1, self.frame(), hearing), self.addr)
            elif now - last_keepalive > 1:
                self.sock.sendto(self.framing.packet(0, b"", hearing), self.addr)
                last_keepalive = now
            try:
                while True:
                    opened = self.framing.open(self.sock.recv(2048))
                    if opened and opened[0] == 1:
                        self.rx += 1
                        self.last_rx_at = time.perf_counter()
                        if any(opened[1]):
                            self.rx_nonzero += 1
            except BlockingIOError:
                pass
            next_at += 0.02
            await asyncio.sleep(max(0, next_at - time.perf_counter()))


class Session:
    """Reader task plus a small inbox with a stash for early messages."""

    def __init__(self, client):
        self.client = client
        self.inbox = asyncio.Queue()
        self.events = []
        self.stash = {}
        self.task = asyncio.create_task(self.reader())

    async def reader(self):
        async for raw in self.client.ws:
            msg = self.client.open_frame(raw)
            if msg is None:
                continue
            if msg.get("type") == "audio_state":
                print(f"  <- audio_state owner={msg.get('owner')} running={msg.get('running')} detail={msg.get('detail')}")
                self.events.append(msg)
            elif msg.get("type") == "call":
                print(f"  <- call {msg.get('state')} direction={msg.get('direction')}")
            elif msg.get("type") == "call_log":
                item = msg.get("item", {})
                print(f"  <- call_log seq={item.get('seq')} type={item.get('type')} answeredOn={item.get('answeredOn')}")
                self.events.append(msg)
            await self.inbox.put(msg)

    async def wait_for(self, kind, timeout=15):
        if kind in self.stash:
            return self.stash.pop(kind)
        end = time.time() + timeout
        while time.time() < end:
            try:
                msg = await asyncio.wait_for(self.inbox.get(), max(0.1, end - time.time()))
            except asyncio.TimeoutError:
                break
            if msg.get("type") == kind:
                return msg
            if msg.get("type") in ("audio_ready", "error"):
                self.stash[msg["type"]] = msg
        return None


def check(label, ok):
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}")
    return ok


async def security(host):
    """Protocol 2 must refuse wrong codes, forged proofs, protocol 1 and forged or replayed frames."""
    results = []
    prefs = bridge_client.read_prefs()
    key = base64.b64decode(prefs["paired_client_key"])
    client_id = prefs["paired_client_id"]

    c = await BridgeClient.open(host)
    results.append(check("hello says paired", c.hello.get("paired") is True and c.hello.get("protocol") == 2))
    r = await c.auth_v1(prefs["pairing_token"])
    results.append(check("protocol 1 auth refused once paired", not r.get("success") and r.get("code") == "update_required"))
    await c.ws.close()

    c = await BridgeClient.open(host)
    await c.send({"type": "get_state"})
    msg = await c.expect("error")
    results.append(check("commands before auth refused", msg.get("code") == "unauthorized"))
    r = await c.auth_v2(key, client_id, proof_override=os.urandom(32))
    results.append(check("forged auth proof refused", not r.get("success") and r.get("code") == "bad_proof"))
    await c.ws.close()

    c = await BridgeClient.open(host)
    _, r = await c.pair("ZZZZZZ" if prefs["pairing_token"] != "ZZZZZZ" else "YYYYYY", "lab-wrong")
    results.append(check("wrong pairing code refused", r.get("code") == "wrong_code"))
    await c.ws.close()

    c = await BridgeClient.open(host)
    r = await c.auth_v2(key, client_id)
    results.append(check("paired key authenticates", r.get("success") is True))
    await c.send({"type": "get_state"})
    results.append(check("sealed command answered", (await c.expect("state")).get("type") == "state"))
    # A captured frame replayed must be ignored.
    c.send_counter += 1
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    body = AESGCM(c.ws_key).encrypt(b"C2S\0" + struct.pack(">Q", c.send_counter), b'{"type":"get_state"}', b"phonebridge-ws-2")
    frame = json.dumps({"t": "s", "c": c.send_counter, "d": base64.b64encode(body).decode()})
    await c.send_raw(frame)
    await c.expect("state")
    await c.send_raw(frame)
    await c.send_raw(json.dumps({"type": "get_state"}))  # unsealed after auth
    forged = bytearray(body)
    forged[0] ^= 1
    await c.send_raw(json.dumps({"t": "s", "c": c.send_counter + 5, "d": base64.b64encode(bytes(forged)).decode()}))
    try:
        extra = await c.expect("state", timeout=2)
    except asyncio.TimeoutError:
        extra = None
    results.append(check("replayed, unsealed and forged frames ignored", extra is None))
    await c.send({"type": "diagnostics"})
    diag = await c.expect("diagnostics_result")
    text = json.dumps(diag)
    results.append(check("diagnostics carry no key or code",
                         prefs["pairing_token"] not in text and prefs["paired_client_key"] not in text))
    await c.send({"type": "lab", "op": "status"})
    results.append(check("lab hooks off by default", (await c.expect("error")).get("code") == "lab_disabled"))
    await c.ws.close()
    return all(results)


async def sync(host):
    client = await BridgeClient.connect(host)
    await client.send({"type": "sync", "callsAfter": 0, "smsAfter": 0})
    calls = await client.expect("sync_calls")
    sms = await client.expect("sync_sms")
    print(f"  sync_calls items={len(calls['items'])} hasMore={calls['hasMore']} lastSeq={calls['lastSeq']}")
    print(f"  sync_sms items={len(sms['items'])} hasMore={sms['hasMore']} lastSeq={sms['lastSeq']}")
    # A journal longer than one page answers hasMore; read to the end before the replay check.
    last_calls, last_sms = calls["lastSeq"], sms["lastSeq"]
    while calls["hasMore"] or sms["hasMore"]:
        await client.send({"type": "sync", "callsAfter": last_calls, "smsAfter": last_sms})
        calls = await client.expect("sync_calls")
        sms = await client.expect("sync_sms")
        last_calls, last_sms = calls["lastSeq"], sms["lastSeq"]
    print(f"  read to the end: calls lastSeq={last_calls} sms lastSeq={last_sms}")
    await client.send({"type": "sync", "callsAfter": last_calls, "smsAfter": last_sms})
    again = await client.expect("sync_calls")
    ok = check("replay after lastSeq returns nothing new", len(again["items"]) == 0 and again["lastSeq"] == last_calls)
    fields = calls["items"][0].keys() if calls["items"] else []
    print(f"  call fields: {sorted(fields)}")
    await client.ws.close()
    return ok


async def call_scenario(args):
    client = await BridgeClient.connect(args.host)
    print(f"authenticated (protocol {client.protocol})")
    s = Session(client)
    if args.dial:
        await client.send({"type": "dial", "number": args.dial})
    if args.scenario == "race":
        # iPhone build 33 requests audio before Telecom has created the call.
        await client.send({"type": "audio_start"})
    while True:
        msg = await s.wait_for("call", 60)
        if msg is None:
            sys.exit("no call")
        if msg.get("state") in ("dialing", "active"):
            call_id = msg.get("callId")
            break
    if args.scenario != "race":
        await client.send({"type": "audio_start"})
    ready = await s.wait_for("audio_ready")
    if ready is None:
        sys.exit(f"no audio_ready: {s.stash.get('error')}")
    media = Media(args.host, ready["port"], ready["session"], client.media_key if ready.get("sealed") else None)
    mtask = asyncio.create_task(media.run())
    print(f"audio_ready port={ready['port']} sealed={ready.get('sealed', False)}")

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
    if args.scenario == "reconnect":
        # iOS replaces its control socket while the call audio keeps flowing on UDP. The new socket
        # of the same iPhone must still be able to move the audio back (2.0.9).
        await client.ws.close()
        s.task.cancel()
        await report("socket_closed_audio_flowing", 3)
        client = await BridgeClient.connect(args.host)
        s = Session(client)
        print("reconnected on a new control socket")
        await client.send({"type": "audio_to_redmi"})
        ack = await s.wait_for("ack", 5)
        ok_ack = check("audio_to_redmi accepted from the new socket", bool(ack) and ack.get("action") == "audio_to_redmi" and ack.get("success") is True)
        await report("after_manual_redmi", 3)
        owners = [(e.get("owner"), e.get("detail")) for e in s.events if e.get("type") == "audio_state"]
        check("audio back on the Redmi", any(o == "redmi" and d == "manual_redmi" for o, d in owners))
        if not ok_ack:
            print("  ack:", ack)
    if args.scenario in ("handoff", "fallback"):
        media.sending = False  # iPhone microphone stops; keepalives continue
        await report("iphone_silent", 4)
        print("re-requesting audio")
        await client.send({"type": "audio_start"})
        await s.wait_for("audio_ready")
        media.sending = True
        await report("rearmed", 6)
        if ready.get("sealed"):
            media.deaf = True  # one-way loss: the Redmi must take the call back
            await report("iphone_deaf", 5)
            media.deaf = False
            await client.send({"type": "audio_start"})
            await s.wait_for("audio_ready")
            await report("rearmed_again", 5)
        await client.send({"type": "audio_to_redmi"})
        await report("manual_redmi", 3)
    await client.send({"type": "hangup", "callId": call_id})
    await asyncio.sleep(5)  # the call log entry follows the hangup
    mtask.cancel()
    s.task.cancel()
    print(f"downlink rejected packets: {media.framing.rejected}")
    print("owners:", [(e.get("owner"), e.get("detail")) for e in s.events if e.get("type") == "audio_state"])
    await client.ws.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.1.147")
    ap.add_argument("--dial")
    ap.add_argument("--scenario", default="handoff")
    ap.add_argument("--pair", action="store_true", help="fresh protocol 2 pairing, restored afterwards")
    args = ap.parse_args()

    if args.pair:
        bridge_client.backup_prefs()
        client = await BridgeClient.connect(args.host, pair=True)
        print("paired (protocol 2)")
        await client.ws.close()
        await asyncio.sleep(1.5)  # SharedPreferences are written asynchronously
    ok = True
    try:
        if args.scenario == "security":
            ok = await security(args.host)
        elif args.scenario == "sync":
            ok = await sync(args.host)
        else:
            await call_scenario(args)
    finally:
        if args.pair:
            bridge_client.restore_prefs()
            print("previous pairing restored")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    asyncio.run(main())
