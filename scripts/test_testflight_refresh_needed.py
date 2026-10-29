"""testflight_refresh_needed.py tarih mantiginin CEVRIMDISI testi (Apple API'si, anahtar, ag yok).

Neden onemli: haftalik koruma yanlis "gerek yok" derse build 90. gunde sessizce olur ve iPhone'daki
uygulama acilmaz; yanlis "gerek var" derse her hafta macOS dakikasi harcanir ve her yukleme
telefona TestFlight bildirimi dusurur. Ikisi de kullaniciya gorunur (AGENTS 0.2).

Test edilen:
  T1  Apple zaman bicimleri (Z, ofset, kesirli saniye) ve bozuk deger
  T2  bitis: expirationDate varsa o, yoksa yukleme + 90 gun
  T3  en yeni GECERLI build: PROCESSING / INVALID / expired atlanir, yukleme saatine gore secilir
  T4  esik: 31 gun kala gerek yok, 30 ve alti gerek var, grupta build yoksa gerek var
  T5  sertifika son 30 gunu: tek bir yenileme tetikler, sonraki haftalar tetiklemez
  T6  sertifika bitmis ya da yoksa: yenileme gerekiyorsa hata (bos macOS kosumu baslatilmaz)
  T7  imzalayan sertifika secimi: seri no (bas sifir / harf buyuklugu), tek sertifika, en uzun omurlu
  T8  import yan etkisiz: PyJWT ve kimlik bilgisi gerekmez (AGENTS 7.2)
"""
import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

_jwt_before = "jwt" in sys.modules
import testflight_refresh_needed as guard  # noqa: E402

UTC = dt.timezone.utc
NOW = dt.datetime(2027, 1, 4, 6, 0, tzinfo=UTC)

results = []


def check(name, cond, detail=""):
    results.append((name, cond))
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail else ""))


def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.000+00:00")


def build(version, state="VALID", uploaded=None, expiry=None, expired=False):
    attributes = {"version": version, "processingState": state, "expired": expired}
    if uploaded is not None:
        attributes["uploadedDate"] = iso(uploaded)
    if expiry is not None:
        attributes["expirationDate"] = iso(expiry)
    return {"id": f"build-{version}", "attributes": attributes}


def cert(serial, expiry):
    return {"id": f"cert-{serial}", "attributes": {"serialNumber": serial, "expirationDate": iso(expiry)}}


GOOD_CERT = cert("5A1B2C", NOW + dt.timedelta(days=300))

print("=== TestFlight yenileme korumasi: tarih mantigi ===\n")

# --- T1 ---
parsed = guard.parse_time("2026-10-28T22:17:33.000-07:00")
check("T1 ofsetli zaman UTC'ye dogru doner",
      parsed is not None and parsed.astimezone(UTC) == dt.datetime(2026, 10, 29, 5, 17, 33, tzinfo=UTC),
      str(parsed))
check("T1 'Z' sonekli zaman okunur",
      guard.parse_time("2026-10-28T22:17:33Z") == dt.datetime(2026, 10, 28, 22, 17, 33, tzinfo=UTC))
check("T1 ofsetsiz zaman UTC sayilir",
      guard.parse_time("2026-10-28T22:17:33") == dt.datetime(2026, 10, 28, 22, 17, 33, tzinfo=UTC))
check("T1 bos / bozuk / None -> None",
      guard.parse_time("") is None and guard.parse_time("yarin") is None and guard.parse_time(None) is None)

# --- T2 ---
uploaded = dt.datetime(2026, 10, 29, 1, 0, tzinfo=UTC)
explicit = dt.datetime(2027, 1, 20, 0, 0, tzinfo=UTC)
check("T2 expirationDate varsa o kullanilir",
      guard.build_expiry(build("155", uploaded=uploaded, expiry=explicit)["attributes"]) == explicit)
check("T2 expirationDate yoksa yukleme + 90 gun",
      guard.build_expiry(build("155", uploaded=uploaded)["attributes"]) == uploaded + dt.timedelta(days=90))
check("T2 iki tarih de yoksa None", guard.build_expiry({"version": "1"}) is None)

# --- T3 ---
builds = [
    build("150", uploaded=NOW - dt.timedelta(days=80)),
    build("155", uploaded=NOW - dt.timedelta(days=60)),
    build("156", state="PROCESSING", uploaded=NOW - dt.timedelta(days=1)),
    build("157", state="INVALID", uploaded=NOW - dt.timedelta(days=2)),
    build("149", uploaded=NOW - dt.timedelta(days=5), expired=True),
    build("158"),  # yukleme saati yok: siralanamaz
]
picked = guard.newest_valid_build(builds)
check("T3 islenmemis / gecersiz / suresi dolmus atlanir, en yeni gecerli secilir",
      picked is not None and picked["attributes"]["version"] == "155",
      picked["attributes"]["version"] if picked else "None")
# Surum numarasi degil yukleme saati belirler (yeniden yukleme eski numarayla gelemez ama
# grup sirasi garanti degil).
reordered = guard.newest_valid_build([build("200", uploaded=NOW - dt.timedelta(days=50)),
                                      build("199", uploaded=NOW - dt.timedelta(days=10))])
check("T3 secim yukleme saatine gore", reordered["attributes"]["version"] == "199")
check("T3 bos liste -> None", guard.newest_valid_build([]) is None)

# --- T4 ---
d31 = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=59)), GOOD_CERT, NOW)
check("T4 31 gun kala gerek yok", d31.needed is False and d31.error is None and d31.build_days_left == 31,
      f"needed={d31.needed} left={d31.build_days_left}")
d30 = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=60)), GOOD_CERT, NOW)
check("T4 30 gun kala gerek var", d30.needed is True and d30.build_days_left == 30,
      f"needed={d30.needed} left={d30.build_days_left}")
d_exp = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=10),
                           expiry=NOW + dt.timedelta(days=29, hours=23)), GOOD_CERT, NOW)
check("T4 expirationDate yuklemeden once gelir (29.9 gun -> gerek var)", d_exp.needed is True)
d_none = guard.decide(None, GOOD_CERT, NOW)
check("T4 grupta gecerli build yoksa gerek var", d_none.needed is True and d_none.error is None)
d_nodate = guard.decide({"id": "x", "attributes": {"version": "9", "processingState": "VALID"}}, GOOD_CERT, NOW)
check("T4 bitisi okunamayan build hata verir (sessizce 'gerek yok' denmez)",
      d_nodate.needed is False and d_nodate.error is not None)

# --- T5 ---
cert_soon = cert("77", NOW + dt.timedelta(days=20))
old_build = build("155", uploaded=NOW - dt.timedelta(days=20))   # pencereden (expiry-30g) once
d_c1 = guard.decide(old_build, cert_soon, NOW)
check("T5 sertifika 20 gun kala, build pencereden once yuklenmis -> yenile",
      d_c1.needed is True and any("certificate" in w for w in d_c1.warnings))
fresh_build = build("160", uploaded=NOW - dt.timedelta(days=3))   # pencere icinde yuklenmis
d_c2 = guard.decide(fresh_build, cert_soon, NOW)
check("T5 pencere icinde zaten yenilenmis -> tekrar yukleme yok, uyari kalir",
      d_c2.needed is False and d_c2.error is None and len(d_c2.warnings) == 1)
cert_far = cert("78", NOW + dt.timedelta(days=31))
d_c3 = guard.decide(old_build, cert_far, NOW)
check("T5 sertifika 31 gun kala -> yenileme yok, uyari yok",
      d_c3.needed is False and not d_c3.warnings)

# --- T6 ---
cert_dead = cert("79", NOW - dt.timedelta(days=1))
d_dead = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=70)), cert_dead, NOW)
check("T6 yenileme gerekli ama sertifika bitmis -> hata, macOS kosumu yok",
      d_dead.needed is False and d_dead.error is not None)
d_dead_ok = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=5)), cert_dead, NOW)
check("T6 sertifika bitmis ama build taze -> hata yok, yalnizca uyari",
      d_dead_ok.needed is False and d_dead_ok.error is None and d_dead_ok.warnings)
d_nocert = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=70)), None, NOW)
check("T6 yenileme gerekli ama sertifika yok -> hata", d_nocert.needed is False and d_nocert.error)
d_nocert_ok = guard.decide(build("155", uploaded=NOW - dt.timedelta(days=5)), None, NOW)
check("T6 sertifika yok, build taze -> yalnizca uyari",
      d_nocert_ok.needed is False and d_nocert_ok.error is None and d_nocert_ok.warnings)

# --- T7 ---
c_a = cert("00A1B2", NOW + dt.timedelta(days=100))
c_b = cert("C3D4", NOW + dt.timedelta(days=200))
check("T7 seri no bas sifir ve kucuk harfle eslesir",
      guard.signing_certificate([c_b, c_a], "a1b2")["id"] == c_a["id"])
check("T7 tek sertifika, seri yok -> o", guard.signing_certificate([c_a], "")["id"] == c_a["id"])
check("T7 cok sertifika, seri yok -> en uzun omurlu",
      guard.signing_certificate([c_a, c_b], "")["id"] == c_b["id"])
check("T7 seri eslesmezse de en uzun omurlu", guard.signing_certificate([c_a, c_b], "FFFF")["id"] == c_b["id"])
check("T7 sertifika yok -> None", guard.signing_certificate([], "A1B2") is None)

# --- T8 ---
check("T8 import PyJWT yuklemez", _jwt_before or "jwt" not in sys.modules)
check("T8 import assign_testflight'i (kimlik bilgisi okuyan modul) yuklemez",
      "assign_testflight" not in sys.modules)

failed = [name for name, ok in results if not ok]
print()
print(f"SONUC: {len(results) - len(failed)}/{len(results)} gecti")
sys.exit(1 if failed else 0)
