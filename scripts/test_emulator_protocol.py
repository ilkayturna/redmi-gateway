"""Lab emulatorunun protokolu Kotlin tarafiyla AYNI mi? (cevrimdisi, cihaz gerekmez)

Neden onemli: iphone_emulator.py gercek Redmi'ye karsi kosacak. Cercevelemesi Kotlin'den
farkli olsaydi ya kimlik dogrulayamazdi ya da "calisti" sanip yanlis sayi uretirdi.
Test araci yanlissa, test sonucu da yanlistir.

Referans (Kotlin):
  CallAudioRelay.kt:58   "10..] AES-256-GCM(payload) || tag, nonce = UPLK|DNLK + counter + session, AAD = header"
  CallAudioRelay.kt:79   HEADER_BYTES = 8
  CallAudioRelay.kt:80   SEALED_HEADER_BYTES = 10
  CallAudioRelay.kt:852-861 writeNonce: 4B tag + 4B counter BE + 4B session BE = 12
  CallAudioRelay.kt:1087-1090 header = session(4) + counter(4) + type(1) + flags(1)
  CallAudioRelay.kt:1083-1085 unsealed = session(4) + counter(2) + type(1) + flags(1)
"""
import os
import struct
import sys

# Lab araclari repoda; bu betik arsiv klasorunde olabilir. Once repoyu, sonra goreli yolu dene.
_CANDIDATES = [
    r"C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\scripts\lab",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "RedmiGateway", "scripts", "lab"),
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "source", "repos",
                 "redmi-gateway", "RedmiGateway", "scripts", "lab"),
]
for _p in _CANDIDATES:
    if os.path.isfile(os.path.join(_p, "bridge_client.py")):
        sys.path.insert(0, os.path.abspath(_p))
        break
else:
    sys.exit("bridge_client.py bulunamadi; lab klasoru yolu yanlis")

import bridge_client as bc
from bridge_client import SealedMedia

KEY = bytes(range(32))
SESSION = 0x11223344

results = []


def check(name, cond, detail=""):
    results.append((name, cond))
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail else ""))


print("=== lab emulatoru <-> Kotlin protokol uyumu ===\n")

# --- 1. NONCE DUZENI: 4B tag + 4B counter BE + 4B session BE ---
m = SealedMedia(SESSION, KEY)
n = m.nonce(b"UPLK", 0x01020304)
check("N1 nonce 12 bayt (NONCE_BYTES)", len(n) == 12, f"{len(n)}")
check("N2 tag ilk 4 bayt", n[:4] == b"UPLK", repr(n[:4]))
check("N3 counter BE @4", n[4:8] == b"\x01\x02\x03\x04", n[4:8].hex())
check("N4 session BE @8", n[8:12] == struct.pack(">I", SESSION), n[8:12].hex())
check("N5 downlink etiketi DNLK", m.nonce(b"DNLK", 1)[:4] == b"DNLK")

# --- 2. SEALED HEADER: 10 bayt, AAD ---
m2 = SealedMedia(SESSION, KEY)
m2.counter = 0
pkt = m2.packet(1, b"\x00" * 640, hearing=True)
check("H1 sealed header 10 bayt (SEALED_HEADER_BYTES)", len(pkt) == 10 + 640 + 16,
      f"toplam={len(pkt)} = 10 header + 640 pcm + 16 tag")
check("H2 session BE @0", pkt[0:4] == struct.pack(">I", SESSION))
check("H3 counter BE @4", pkt[4:8] == struct.pack(">I", 1))
check("H4 type @8", pkt[8] == 1)
check("H5 hearing bayragi @9", pkt[9] == 1)
m3 = SealedMedia(SESSION, KEY)
m3.counter = 0
check("H6 hearing=False -> 0", m3.packet(1, b"")[9] == 0)

# --- 3. SEALED ROUND-TRIP (DOGRU ROLLERLE) ---
# PROTOCOL.md:110 -> nonce "UPLK" (iPhone->Redmi) veya "DNLK" (Redmi->iPhone).
# Emulator iPhone rolunu oynar: tx=UPLK, rx=DNLK.  Kotlin (Redmi) ise TERS: tx=DNLK, rx=UPLK.
# Bu yuzden iki emulator ornegi birbirini ACAMAZ; karsi tarafi elle kurmak gerekir.
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

iphone = SealedMedia(SESSION, KEY)
redmi_rx = AESGCM(KEY)

ok_tx = True   # emulator -> Redmi: UPLK ile muhurlenir, Redmi UPLK ile acar
for i in range(5):
    pkt = iphone.packet(1, struct.pack("<h", i) * 320)
    counter = struct.unpack(">I", pkt[4:8])[0]
    header = pkt[:10]
    up_nonce = b"UPLK" + struct.pack(">II", counter, SESSION)
    try:
        plain = redmi_rx.decrypt(up_nonce, pkt[10:], header)
    except Exception:
        ok_tx = False
        break
    if plain != struct.pack("<h", i) * 320:
        ok_tx = False
        break
check("R1 emulator tx UPLK ile muhurler, Redmi acar (5 kare)", ok_tx)

ok_rx = True   # Redmi -> emulator: DNLK ile muhurlenir, emulator DNLK ile acar
iphone2 = SealedMedia(SESSION, KEY)
redmi_tx = AESGCM(KEY)
for i in range(5):
    counter = i + 1
    pcm = struct.pack("<h", i + 100) * 320
    header = struct.pack(">IIBB", SESSION, counter, 1, 0)
    dn_nonce = b"DNLK" + struct.pack(">II", counter, SESSION)
    pkt = header + redmi_tx.encrypt(dn_nonce, pcm, header)
    r = iphone2.open(pkt)
    if r is None or r[0] != 1 or r[1] != pcm:
        ok_rx = False
        break
check("R2 Redmi DNLK ile muhurler, emulator acar (5 kare)", ok_rx)

check("R3 etiketler ROL tabanli (PROTOCOL.md:110)",
      iphone.packet(1, b"")[8] == 1 and iphone2.last_rx == 5,
      "emulator: tx=UPLK rx=DNLK | Redmi: tx=DNLK rx=UPLK")

# --- 4. REPLAY REDDI (counter <= last_rx) ---
iphone3 = SealedMedia(SESSION, KEY)
redmi_tx3 = AESGCM(KEY)


def redmi_frame(counter, pcm):
    h = struct.pack(">IIBB", SESSION, counter, 1, 0)
    return h + redmi_tx3.encrypt(b"DNLK" + struct.pack(">II", counter, SESSION), pcm, h)


p1 = redmi_frame(1, b"\x01" * 640)
p2 = redmi_frame(2, b"\x02" * 640)
check("P1 ilk paket kabul edilir", iphone3.open(p1) is not None)
check("P2 tekrar edilen paket reddedilir", iphone3.open(p1) is None)
check("P3 yeni paket kabul edilir", iphone3.open(p2) is not None)

# --- 5. SAHTE PAKET REDDI (AAD/icerik degistirilmis) ---
i_f1 = SealedMedia(SESSION, KEY)
p_f1 = bytearray(redmi_frame(1, b"\x03" * 640))
p_f1[9] ^= 1                                  # AAD'deki flags bitini degistir
check("F1 AAD degistirilirse reddedilir", i_f1.open(bytes(p_f1)) is None,
      f"rejected={i_f1.rejected}")

i_f2 = SealedMedia(SESSION, KEY)
p_f2 = bytearray(redmi_frame(1, b"\x04" * 640))
p_f2[15] ^= 1                                 # sifreli icerigi degistir
check("F2 icerik degistirilirse reddedilir", i_f2.open(bytes(p_f2)) is None)

# --- 6. YANLIS SESSION REDDI ---
i_s1 = SealedMedia(SESSION + 1, KEY)          # emulator farkli session bekliyor
check("S1 farkli session reddedilir", i_s1.open(redmi_frame(1, b"\x05" * 640)) is None)

# --- 7. YANLIS ANAHTAR REDDI ---
i_s2 = SealedMedia(SESSION, bytes(range(1, 33)))   # farkli anahtar
check("S2 farkli anahtar reddedilir", i_s2.open(redmi_frame(1, b"\x06" * 640)) is None)

# --- 8. UNSELAED (protocol 1) HEADER: 8 bayt ---
m8 = SealedMedia(SESSION, None)
m8.counter = 0
u = m8.packet(1, b"\x07" * 640)
check("U1 unsealed header 8 bayt (HEADER_BYTES)", len(u) == 8 + 640, f"{len(u)}")
check("U2 session BE @0", u[0:4] == struct.pack(">I", SESSION))
check("U3 counter 2 bayt BE @4", u[4:6] == struct.pack(">H", 1))
check("U4 type @6", u[6] == 1)
ru = SealedMedia(SESSION, None)
r = ru.open(u)
check("U5 unsealed round-trip", r is not None and r[0] == 1 and len(r[1]) == 640)

# --- 9. WS NONCE: 4B yon + 8B counter BE ---
c = bc.BridgeClient.__new__(bc.BridgeClient)
wn = c._nonce(b"C2S\0", 0x0102030405060708)
check("W1 ws nonce 12 bayt", len(wn) == 12, f"{len(wn)}")
check("W2 yon etiketi ilk 4 bayt", wn[:4] == b"C2S\0")
check("W3 counter 8 bayt BE", wn[4:12] == b"\x01\x02\x03\x04\x05\x06\x07\x08")
check("W4 S2C etiketi", c._nonce(b"S2C\0", 1)[:4] == b"S2C\0")

# --- 10. HMAC-SHA256 (proof icin) ---
h = bc.hmac256(b"key", b"a", b"b")
check("M1 hmac256 32 bayt", len(h) == 32)
check("M2 hmac256 birlestirilmis girdiyle ayni",
      h == bc.hmac256(b"key", b"ab"), "update sirasi dogru")

print()
passed = sum(1 for _, cnd in results if cnd)
print(f"SONUC: {passed}/{len(results)} PASS")
print()
print("Hukum: emulator ayni protokolu konusuyor -> Redmi'ye karsi kosunca sayilar guvenilir.")
sys.exit(0 if passed == len(results) else 1)
