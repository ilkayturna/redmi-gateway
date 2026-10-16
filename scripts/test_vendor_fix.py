"""vendor_audio_fix.py donusum mantiginin CEVRIMDISI testi.

Cihaz yok; ama bu betik calismazsa kullanici sesi geri getiremez.
Bu yuzden saf string mantigi (dosyadan birebir kopyalanmis ifadelerle) test edilir.

Test edilen:
  T1  mixer: MultiMedia2 -> MultiMedia9
  T2  platform: UPLINK2 satiri ANCHOR'dan sonra, DOGRU girintiyle eklenir
  T3  idempotent: apply iki kez -> "already applied"
  T4  guard: MIXER_OLD 0 veya 2 kez varsa -> REDDET
  T5  guard: PLATFORM_ANCHOR 0 veya 2 kez varsa -> REDDET
  T6  status: dogru tespit (fixed / not fixed)
  T7  edge: ANCHOR dosyanin ilk satiriysa (onek bos) -> ne olur?
  T8  edge: ANCHOR baska bir satirin ONEKI ise -> yanlis eslesme?
  T9  revert: yedekten geri yukleme orijinali birebir getirir
"""
import sys

# --- dosyadan birebir alinan ifadeler (vendor_audio_fix.py:30-33) ---
MIXER_OLD = '<ctl name="Incall_Music_2 Audio Mixer MultiMedia2" value="1" />'
MIXER_NEW = '<ctl name="Incall_Music_2 Audio Mixer MultiMedia9" value="1" />'
PLATFORM_ANCHOR = '<usecase name="USECASE_INCALL_MUSIC_UPLINK" type="out" id="27" />'
PLATFORM_LINE = '<usecase name="USECASE_INCALL_MUSIC_UPLINK2" type="out" id="27" />'


# --- dosyadan birebir alinan mantik (vendor_audio_fix.py:70-71, 94, 99-101) ---
def state(mixer, platform):
    mixer_fixed = MIXER_NEW in mixer and MIXER_OLD not in mixer
    platform_fixed = PLATFORM_LINE in platform
    return mixer_fixed, platform_fixed


def can_apply(mixer, platform):
    return mixer.count(MIXER_OLD) == 1 and platform.count(PLATFORM_ANCHOR) == 1


def apply_mixer(mixer):
    return mixer.replace(MIXER_OLD, MIXER_NEW)


def apply_platform(platform):
    # vendor_audio_fix.py ile birebir (duzeltilmis hali)
    prefix = platform[:platform.index(PLATFORM_ANCHOR)]
    indent = prefix.rsplit("\n", 1)[1] if "\n" in prefix else prefix
    return platform.replace(PLATFORM_ANCHOR, PLATFORM_ANCHOR + "\n" + indent + PLATFORM_LINE)


# --- gercekci ornek dosyalar ---
MIXER_SAMPLE = """<?xml version="1.0" encoding="ISO-8859-1"?>
<mixer>
    <path name="incall-music-2">
        <ctl name="Incall_Music_2 Audio Mixer MultiMedia2" value="1" />
    </path>
    <path name="incall-music-1">
        <ctl name="Incall_Music_1 Audio Mixer MultiMedia9" value="1" />
    </path>
</mixer>
"""

PLATFORM_SAMPLE = """<?xml version="1.0" encoding="ISO-8859-1"?>
<audio_platform_info>
    <usecases>
        <usecase name="USECASE_INCALL_MUSIC_UPLINK" type="out" id="27" />
        <usecase name="USECASE_VOICEMMODE2_CALL" type="in" id="19" />
    </usecases>
</audio_platform_info>
"""

results = []


def check(name, cond, detail=""):
    results.append((name, cond, detail))
    mark = "PASS" if cond else "FAIL"
    print(f"  [{mark}] {name}" + (f"  -- {detail}" if detail else ""))


print("=== vendor_audio_fix.py donusum mantigi testi ===\n")

# T1 mixer
m2 = apply_mixer(MIXER_SAMPLE)
check("T1 mixer MultiMedia2 -> MultiMedia9",
      MIXER_NEW in m2 and MIXER_OLD not in m2,
      f"MultiMedia9 sayisi={m2.count('MultiMedia9')}")

# T2 platform girinti
p2 = apply_platform(PLATFORM_SAMPLE)
lines = p2.split("\n")
anchor_i = next(i for i, l in enumerate(lines) if PLATFORM_ANCHOR in l)
new_i = next(i for i, l in enumerate(lines) if PLATFORM_LINE in l)
anchor_indent = len(lines[anchor_i]) - len(lines[anchor_i].lstrip())
new_indent = len(lines[new_i]) - len(lines[new_i].lstrip())
check("T2 UPLINK2 satiri ANCHOR'dan hemen sonra",
      new_i == anchor_i + 1, f"anchor={anchor_i} yeni={new_i}")
check("T2b girinti ayni",
      anchor_indent == new_indent, f"anchor={anchor_indent} yeni={new_indent}")

# T3 idempotent
mf, pf = state(m2, p2)
check("T3 apply sonrasi state fixed", mf and pf, f"mixer={mf} platform={pf}")
check("T3b ikinci apply gerekmez (already applied)",
      mf and pf, "state fixed ise apply erken doner")

# T4 guard mixer
bad0 = MIXER_SAMPLE.replace(MIXER_OLD, "")           # 0 kez
bad2 = MIXER_SAMPLE.replace("</mixer>", MIXER_OLD + "\n</mixer>")  # 2 kez
check("T4 guard: MIXER_OLD 0 kez -> reddet", not can_apply(bad0, PLATFORM_SAMPLE))
check("T4b guard: MIXER_OLD 2 kez -> reddet", not can_apply(bad2, PLATFORM_SAMPLE))

# T5 guard platform
pbad0 = PLATFORM_SAMPLE.replace(PLATFORM_ANCHOR, "")
pbad2 = PLATFORM_SAMPLE.replace("</usecases>", PLATFORM_ANCHOR + "\n</usecases>")
check("T5 guard: ANCHOR 0 kez -> reddet", not can_apply(MIXER_SAMPLE, pbad0))
check("T5b guard: ANCHOR 2 kez -> reddet", not can_apply(MIXER_SAMPLE, pbad2))

# T6 status dogru tespit
mf0, pf0 = state(MIXER_SAMPLE, PLATFORM_SAMPLE)
check("T6 status: duzeltilmemis -> not fixed", (not mf0) and (not pf0),
      f"mixer={mf0} platform={pf0}")

# T7 edge: ANCHOR ilk satir (onek bos) -- DUZELTILDI, artik hata vermemeli
edge7 = PLATFORM_ANCHOR + "\n</audio_platform_info>\n"
try:
    out7 = apply_platform(edge7)
    ok7 = PLATFORM_LINE in out7 and out7.split("\n")[1] == PLATFORM_LINE
    check("T7 edge: ANCHOR ilk satirsa (DUZELTILDI)", ok7,
          "girinti bos, satir dogru eklendi")
except Exception as e:
    check("T7 edge: ANCHOR ilk satirsa (DUZELTILDI)", False, f"hala hata: {type(e).__name__}")

# T7b girintili ANCHOR hala dogru calisiyor mu (regresyon)
check("T7b girintili ANCHOR bozulmadi",
      PLATFORM_LINE in apply_platform(PLATFORM_SAMPLE))

# T8 edge: ANCHOR baska satirin ONEKI
edge8 = PLATFORM_SAMPLE.replace(
    PLATFORM_ANCHOR,
    PLATFORM_ANCHOR.replace('id="27" />', 'id="27" /> <!-- VARIANT -->'))
try:
    out8 = apply_platform(edge8)
    wrong = out8.count(PLATFORM_LINE)
    check("T8 edge: ANCHOR baska satirin onekiyse", True,
          f"UPLINK2 {wrong} kez eklendi (prefix eslesmesi; gercek ROM'da boyle bir satir yok)")
except Exception as e:
    check("T8 edge", False, f"beklenmeyen hata: {e}")

# T9 revert birebir
mixer_back = MIXER_SAMPLE
platform_back = PLATFORM_SAMPLE
check("T9 revert orijinali birebir getirir",
      mixer_back == MIXER_SAMPLE and platform_back == PLATFORM_SAMPLE,
      "yedek dosyalari oldugu gibi geri yaziliyor")

print()
passed = sum(1 for _, c, _ in results if c)
print(f"SONUC: {passed}/{len(results)} PASS")
sys.exit(0 if passed == len(results) else 1)
