"""root_probe_uplink.py mixer deger ayristirmasi - cevrimdisi test (cihaz gerekmez).

Neden: arac "Safely saves and restores original mixer state" diyordu, ama eski mantik
`if "1" in out` idi ve kontrol ADI "MultiMedia1" ile bitiyordu -> HER ZAMAN 1 donuyordu.
Yani orijinal deger 0 ise arac onu 1 yapip oyle birakiyordu.

Bu, root/cihaz aracidir; test edilebilir kismi saf ayristirma fonksiyonudur.
"""
import os
import sys

_CANDIDATES = [
    r"C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\scripts",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "RedmiGateway", "scripts"),
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "source", "repos",
                 "redmi-gateway", "RedmiGateway", "scripts"),
]
for _p in _CANDIDATES:
    if os.path.isfile(os.path.join(_p, "root_probe_uplink.py")):
        sys.path.insert(0, os.path.abspath(_p))
        break
else:
    sys.exit("root_probe_uplink.py bulunamadi; yol yanlis")

try:
    from root_probe_uplink import parse_mixer_value
except Exception as exc:  # pragma: no cover
    sys.exit(f"root_probe_uplink import edilemedi: {exc}")


def old_parse(out):
    """Eski (hatali) mantik - karsilastirma icin."""
    return 1 if "1" in out else 0


CASES = [
    # (tinymix ciktisi, dogru deger)
    ("Incall_Music Audio Mixer MultiMedia1: 0", 0),
    ("Incall_Music Audio Mixer MultiMedia1: 1", 1),
    ("Incall_Music_2 Audio Mixer MultiMedia9: 0", 0),
    ("Incall_Music_2 Audio Mixer MultiMedia9: 1", 1),
    ("> 1", 1),
    ("> 0", 0),
    ("Incall_Music_2 Audio Mixer MultiMedia9", None),   # deger yok -> dokunma
    ("", None),
    ("tinymix: control not found", None),
]

print("=== root_probe_uplink mixer ayristirma testi ===\n")
print(f"  {'cikti':<48} {'gercek':>6} {'eski':>6} {'yeni':>6}")
print("  " + "-" * 70)

bad_old = bad_new = 0
for out, want in CASES:
    got = parse_mixer_value(out)
    o = old_parse(out)
    if o != want:
        bad_old += 1
    if got != want:
        bad_new += 1
    fo = "OK" if o == want else "XX"
    fn = "OK" if got == want else "XX"
    print(f"  {out[:46]:<48} {str(want):>6} {str(o) + fo:>6} {str(got) + fn:>6}")

print()
print(f"  ESKI mantik yanlis : {bad_old}/{len(CASES)}")
print(f"  YENI mantik yanlis : {bad_new}/{len(CASES)}")
print()

ok = bad_new == 0 and bad_old > 0
print(f"SONUC: yeni mantik {len(CASES) - bad_new}/{len(CASES)} dogru; "
      f"eski mantik {bad_old} vakada yanlisti")
print("       (eski mantik gercekten bozuksa test AYIRT EDICI demektir)")
sys.exit(0 if ok else 1)
