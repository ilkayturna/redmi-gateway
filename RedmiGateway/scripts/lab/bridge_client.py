"""Phone Bridge protocol client for the lab tools (docs/PROTOCOL.md).

Speaks protocol 2 (paired key, sealed WebSocket frames and sealed UDP media) and protocol 1
(pairing code in clear text, only accepted while no keyed pairing exists). Keys and codes are
read from the Redmi over root adb and never printed.

    client = await BridgeClient.connect(host)          # as the paired iPhone, or v1 if unpaired
    client = await BridgeClient.connect(host, pair=True)  # fresh v2 pairing with the current code
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import struct
import subprocess
import sys
import uuid

import websockets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ADB = os.environ.get("ADB", r"C:\Users\ilkay.turna\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "17df6357")
PACKAGE = "com.ilkay.redmigateway"
PREFS = f"/data/data/{PACKAGE}/shared_prefs/redmi_gateway_prefs.xml"
PREFS_BACKUP = "/data/local/tmp/phonebridge_prefs_backup.xml"
WS_AAD = b"phonebridge-ws-2"


def adb_shell(command):
    return subprocess.run([ADB, "-s", SERIAL, "shell", command], capture_output=True, text=True).stdout


def read_prefs():
    xml = adb_shell("cat " + PREFS)
    values = dict(re.findall(r'<string name="([^"]+)">([^<]*)</string>', xml))
    return values


def backup_prefs():
    adb_shell(f"cp {PREFS} {PREFS_BACKUP}")


def restore_prefs():
    """Puts the saved pairing back (lab pairings replace the real one) and restarts the gateway."""
    adb_shell(f"am force-stop {PACKAGE}")
    # cat keeps the file's owner, mode and SELinux label.
    adb_shell(f"cat {PREFS_BACKUP} > {PREFS} && rm {PREFS_BACKUP}")
    adb_shell(f"am start-foreground-service -a {PACKAGE}.ACTION_START -n {PACKAGE}/.service.GatewayService")


def enable_lab_mode(enable=True):
    adb_shell(f"am broadcast -a {PACKAGE}.LAB_MODE --ez enable {'true' if enable else 'false'} "
              f"-n {PACKAGE}/.audio.LabModeReceiver")


def hmac256(key, *parts):
    mac = hmac.new(key, digestmod=hashlib.sha256)
    for part in parts:
        mac.update(part)
    return mac.digest()


def b64(data):
    return base64.b64encode(data).decode()


class SealedMedia:
    """UDP media framing. Protocol 2: 10-byte header as AAD, AES-GCM with counter nonces."""

    def __init__(self, session, key=None):
        self.session = session
        self.key = AESGCM(key) if key else None
        self.counter = 0
        self.last_rx = 0
        self.rejected = 0

    def nonce(self, tag, counter):
        return tag + struct.pack(">II", counter & 0xFFFFFFFF, self.session)

    def packet(self, ptype, pcm=b"", hearing=False):
        """hearing sets flag bit 0: this side is receiving downlink frames (protocol 2)."""
        self.counter += 1
        if self.key is None:
            return struct.pack(">IHBB", self.session, self.counter & 0xFFFF, ptype, 0) + pcm
        header = struct.pack(">IIBB", self.session, self.counter, ptype, 1 if hearing else 0)
        return header + self.key.encrypt(self.nonce(b"UPLK", self.counter), pcm, header)

    def open(self, data):
        """Returns (type, pcm) or None."""
        if self.key is None:
            if len(data) < 8 or struct.unpack(">I", data[:4])[0] != self.session:
                return None
            return data[6], data[8:]
        if len(data) < 26 or struct.unpack(">I", data[:4])[0] != self.session:
            return None
        counter = struct.unpack(">I", data[4:8])[0]
        if counter <= self.last_rx:
            return None
        try:
            pcm = self.key.decrypt(self.nonce(b"DNLK", counter), data[10:], data[:10])
        except Exception:
            self.rejected += 1
            return None
        self.last_rx = counter
        return data[8], pcm


class BridgeClient:
    def __init__(self, ws):
        self.ws = ws
        self.protocol = 1
        self.ws_key = None
        self.media_key = None
        self.send_counter = 0
        self.last_rx = 0
        self.hello = None
        self.dropped = 0

    # ---- framing ----

    def _nonce(self, direction, counter):
        return direction + struct.pack(">Q", counter)

    async def send(self, message):
        text = json.dumps(message)
        if self.ws_key is None:
            await self.ws.send(text)
            return
        self.send_counter += 1
        body = AESGCM(self.ws_key).encrypt(self._nonce(b"C2S\0", self.send_counter), text.encode(), WS_AAD)
        await self.ws.send(json.dumps({"t": "s", "c": self.send_counter, "d": b64(body)}))

    async def send_raw(self, text):
        """Lab only: sends a frame exactly as given (forgery and replay tests)."""
        await self.ws.send(text)

    def open_frame(self, raw):
        msg = json.loads(raw)
        if self.ws_key is None:
            return msg
        if msg.get("t") != "s":
            self.dropped += 1
            return None
        counter = int(msg.get("c", -1))
        if counter <= self.last_rx:
            self.dropped += 1
            return None
        try:
            plain = AESGCM(self.ws_key).decrypt(self._nonce(b"S2C\0", counter), base64.b64decode(msg["d"]), WS_AAD)
        except Exception:
            self.dropped += 1
            return None
        self.last_rx = counter
        return json.loads(plain)

    async def recv(self, timeout=15):
        while True:
            raw = await asyncio.wait_for(self.ws.recv(), timeout)
            msg = self.open_frame(raw)
            if msg is not None:
                return msg

    async def expect(self, kind, timeout=15):
        loop = asyncio.get_running_loop()
        end = loop.time() + timeout
        while True:
            msg = await self.recv(max(0.1, end - loop.time()))
            if msg.get("type") == kind:
                return msg

    # ---- handshake ----

    async def pair(self, code, client_id, name="Lab emulator"):
        nonce_s = base64.b64decode(self.hello["nonce"])
        nonce_c = os.urandom(16)
        proof = hmac256(code.encode(), b"pair", nonce_s, nonce_c)
        await self.send({"type": "pair", "clientId": client_id, "clientName": name,
                         "nonce": b64(nonce_c), "proof": b64(proof)})
        result = await self.expect("pair_result")
        if not result.get("success"):
            return None, result
        wrap = hashlib.pbkdf2_hmac("sha256", code.encode(), nonce_s + nonce_c, 100_000, 32)
        sealed = base64.b64decode(result["key"])
        key = AESGCM(wrap).decrypt(sealed[:12], sealed[12:], b"pair")
        expected = hmac256(key, b"gw", nonce_c, nonce_s)
        if not hmac.compare_digest(expected, base64.b64decode(result["gatewayProof"])):
            raise RuntimeError("gateway proof mismatch after pairing")
        return key, result

    async def auth_v2(self, key, client_id, proof_override=None):
        nonce_s = base64.b64decode(self.hello["nonce"])
        nonce_c = os.urandom(16)
        proof = proof_override or hmac256(key, b"auth", nonce_s, nonce_c)
        await self.send({"type": "auth", "protocol": 2, "clientId": client_id,
                         "nonce": b64(nonce_c), "proof": b64(proof)})
        result = await self.expect("auth_result")
        if not result.get("success"):
            return result
        if not hmac.compare_digest(hmac256(key, b"gw", nonce_c, nonce_s), base64.b64decode(result["gatewayProof"])):
            raise RuntimeError("gateway proof mismatch")
        session = hmac256(key, b"session", nonce_s, nonce_c)
        self.protocol = 2
        self.ws_key = hmac256(session, b"ws")
        self.media_key = hmac256(session, b"media")
        return result

    async def auth_v1(self, token):
        await self.send({"type": "auth", "token": token})
        return await self.expect("auth_result")

    @classmethod
    async def open(cls, host):
        target = f"[{host}]" if ":" in host else host
        ws = await websockets.connect(f"ws://{target}:8787", ping_interval=None, max_size=None)
        client = cls(ws)
        client.hello = await client.expect("hello")
        return client

    @classmethod
    async def connect(cls, host, pair=False):
        """Authenticated client: fresh v2 pairing, the paired iPhone's key, or protocol 1."""
        client = await cls.open(host)
        prefs = read_prefs()
        if pair:
            client_id = "lab-" + uuid.uuid4().hex[:12]
            key, result = await client.pair(prefs["pairing_token"], client_id)
            if key is None:
                sys.exit(f"pairing failed: {result.get('code')}")
            await client.ws.close()
            client = await cls.open(host)
            result = await client.auth_v2(key, client_id)
        elif prefs.get("paired_client_key"):
            key = base64.b64decode(prefs["paired_client_key"])
            result = await client.auth_v2(key, prefs["paired_client_id"])
        else:
            result = await client.auth_v1(prefs["pairing_token"])
        if not result.get("success"):
            sys.exit(f"auth failed: {result.get('code') or result.get('message')}")
        return client
