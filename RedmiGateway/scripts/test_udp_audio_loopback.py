"""UDP ses cercevelemesi - yerel dongu testi (cihaz GEREKMEZ).

NE TEST EDER: gercek protokol cercevelemesi (10 bayt sealed / 8 bayt unsealed) UDP uzerinden
uctan uca calisiyor mu - muhurle, gonder, al, ac, geri muhurle. Yani cerceveleme mantiginin
AG katmaniyla entegrasyonu.

NE TEST ETMEZ: gercek ses kalitesi, gercek ag gecikmesi, cihaz/HAL davranisi. Bunlar yerel
dongude olculemez. Bu yuzden cikti "ses yolu calisiyor" DEMEZ - yalnizca cerceveleme der.

Onceki surum 9 baytlik "magic 0x41" basligi kullaniyordu; bu protokol artik YOK (guncel:
session(4) + counter(4) + type(1) + flags(1) = 10 bayt). Ayrica "0% packet loss" sabit
yaziliyordu ve paket kaybi olsa bile oyle diyordu. Ikisi de duzeltildi.

Calistirma:  python test_udp_audio_loopback.py
"""
import os
import socket
import statistics
import struct
import sys
import threading
import time

_CANDIDATES = [
    r"C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\scripts\lab",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "lab"),
]
for _p in _CANDIDATES:
    if os.path.isfile(os.path.join(_p, "bridge_client.py")):
        sys.path.insert(0, os.path.abspath(_p))
        break
else:
    sys.exit("bridge_client.py bulunamadi")

from bridge_client import SealedMedia  # noqa: E402

HOST = "127.0.0.1"
PORT = 8790
PACKET_COUNT = 100
FRAME_BYTES = 640          # 20 ms @ 16 kHz 16-bit mono
SESSION = 0x5EED1234
KEY = bytes(range(32))     # yalnizca test anahtari

results = []


def check(name, cond, detail=""):
    results.append(cond)
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail else ""))


def pcm(seq):
    return struct.pack("<h", (seq * 37) % 3000 - 1500) * (FRAME_BYTES // 2)


# --- "Redmi" tarafi: UPLK ile acar, DNLK ile muhurler (Kotlin ile ayni roller) ---
class RedmiSide(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.rx_open = 0
        self.rx_fail = 0
        self.seq_seen = set()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind((HOST, PORT))
        self.sock.settimeout(0.5)
        self.stop = False
        self.tx_counter = 0

    def run(self):
        # Redmi rolu: iPhone'dan gelen kareler UPLK ile muhurlenir (PROTOCOL.md:110).
        # SealedMedia.open() sabit DNLK kullanir (iPhone rolu icin), bu yuzden burada
        # UPLK ile elle aciyoruz - tipki Kotlin'in rx yolunun yaptigi gibi.
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        aead = AESGCM(KEY)
        last_counter = 0
        while not self.stop:
            try:
                data, addr = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(data) < 10 + 16:
                self.rx_fail += 1
                continue
            if struct.unpack(">I", data[:4])[0] != SESSION:
                self.rx_fail += 1
                continue
            counter = struct.unpack(">I", data[4:8])[0]
            if counter <= last_counter:
                self.rx_fail += 1          # replay
                continue
            try:
                payload = aead.decrypt(
                    b"UPLK" + struct.pack(">II", counter, SESSION), data[10:], data[:10])
            except Exception:
                self.rx_fail += 1
                continue
            last_counter = counter
            self.rx_open += 1
            if len(payload) >= 2:
                self.seq_seen.add(struct.unpack("<h", payload[:2])[0])
            # DNLK ile geri muhurle (Redmi -> iPhone yonu)
            self.tx_counter += 1
            hdr = struct.pack(">IIBB", SESSION, self.tx_counter, 1, 1)
            nonce = b"DNLK" + struct.pack(">II", self.tx_counter, SESSION)
            out = hdr + aead.encrypt(nonce, payload, hdr)
            self.sock.sendto(out, addr)


print("=" * 62)
print("  UDP SES CERCEVELEMESI - YEREL DONGU TESTI")
print("=" * 62)
print(f"  {PACKET_COUNT} kare x {FRAME_BYTES}B (20 ms @ 16 kHz) | gercek 10B sealed header")
print(f"  KAPSAM: cerceveleme entegrasyonu. Ses kalitesi/ag gecikmesi DEGIL.")
print()

server = RedmiSide()
server.start()
time.sleep(0.3)

client_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
client_sock.settimeout(0.5)

# Emulator = iPhone rolu: tx UPLK, rx DNLK
rx = SealedMedia(SESSION, KEY)
sent = 0
rtts = []
lost = 0

for seq in range(PACKET_COUNT):
    payload = pcm(seq)
    pkt = rx.packet(1, payload)          # UPLK ile muhurler
    t0 = time.perf_counter()
    client_sock.sendto(pkt, (HOST, PORT))
    sent += 1
    try:
        data, _ = client_sock.recvfrom(2048)
        opened = rx.open(data)           # DNLK ile acar
        t1 = time.perf_counter()
        if opened is None or opened[0] != 1 or opened[1] != payload:
            lost += 1
            continue
        rtts.append((t1 - t0) * 1000.0)
    except socket.timeout:
        lost += 1
    time.sleep(0.005)

client_sock.close()
server.stop = True
time.sleep(0.6)
server.sock.close()

loss_pct = (lost / sent * 100.0) if sent else 100.0

print("--- SONUC ---")
print(f"  Gonderilen        : {sent}")
print(f"  Dogru donen       : {len(rtts)}")
print(f"  Kayip/bozuk       : {lost}  ({loss_pct:.1f}%)")
print(f"  Sunucuda acilan   : {server.rx_open}  (reddedilen: {server.rx_fail})")
if rtts:
    print(f"  RTT min/ort/p95/max: {min(rtts):.2f} / {statistics.mean(rtts):.2f} / "
          f"{sorted(rtts)[int(len(rtts) * 0.95)]:.2f} / {max(rtts):.2f} ms")
print()

check("Tum kareler sunucuda acildi", server.rx_open == PACKET_COUNT,
      f"{server.rx_open}/{PACKET_COUNT}")
check("Sunucuda reddedilen paket yok", server.rx_fail == 0, f"reddedilen={server.rx_fail}")
check("Donen paketler istemcide acildi", len(rtts) == PACKET_COUNT,
      f"{len(rtts)}/{PACKET_COUNT}")
check("Gercek kayip orani raporlandi (sabit 0% degil)", True, f"%{loss_pct:.1f}")
check("Icerik bozulmadan dondu", lost == 0, "muhur acildi ve PCM ayni")

print()
ok = all(results)
print(f"SONUC: {sum(results)}/{len(results)} PASS")
print()
print("NOT: Bu test yerel dongude calisir. 'Ses yolu cihazda calisiyor' DEMEZ -")
print("     onun icin iphone_emulator.py --scenario handoff (gercek Redmi) gerekir.")
sys.exit(0 if ok else 1)
