# APK ARŞİVİ — HANGİSİ NE?

**Ölçüm tarihi:** 16.10.2026 · Yöntem: DEX içinde sınıf adı araması (`aapt2 dump badging` + string taraması)

> ⚠️ **Sürüm numarası ayırt etmez.** Dört APK'nın hepsi `versionCode=202` / `versionName=2.0.2`.
> Ayırt etmenin tek yolu **dosya adı, tarih ve hash**'tir. Bu yüzden bu tablo var.

---

## KURULACAK OLANLAR (uyanma özelliği VAR)

| Dosya | Boyut | Tarih | `CallWakePush` | `WakePushThrottle` | Ne zaman |
|---|---|---|---|---|---|
| **`apk\app-debug-LINT-FIXES.apk`** | 6,63 MB | 16.10 13:20 | ✅ | ✅ | **⭐ BUNU KUR** |
| `apk\app-debug-GATE-LOGGING.apk` | 6,63 MB | 16.10 12:11 | ✅ | ✅ | Lint düzeltmeleri istenmiyorsa |
| `apk\app-debug-GATE-REVERTED.apk` | 6,63 MB | 16.10 12:11 | ✅ | ✅ | Ses çalışmazsa yedek |

```
SHA256 (LINT-FIXES)    = BFD66960A2D5302E42BE...   (86 test) ⭐
SHA256 (GATE-LOGGING)  = 63120852B3BC336D936D...   (86 test)
SHA256 (GATE-REVERTED) = 87B1C09DE090D10D4C58...   (84 test, kapı değişikliği yok)
```

**Farklar:**
| | LINT-FIXES | GATE-LOGGING | GATE-REVERTED |
|---|---|---|---|
| Uyanma özelliği | ✅ | ✅ | ✅ |
| `GATE_WAIT`/`GATE_OPEN` kapı teşhisi | ✅ | ✅ | ❌ |
| **API 27 çökmesi düzeltildi** (gelen çağrı ekranı) | ✅ | ❌ | ❌ |
| **API 29 `RoleManager` çökmesi düzeltildi** (ayarlar butonu) | ✅ | ❌ | ❌ |

> **Android 16'da üçü de aynı davranır** — düzeltmeler yalnızca API 26-28'de tetiklenen çökmeleri
> kapatır. Yani LINT-FIXES'ın **dezavantajı yok**, kullanılmayan yollarda iki çökmeyi kapatıyor.

---


## GERİ DÖNÜŞ (rollback) — ⚠️ UYANMA YOK

| Dosya | Boyut | Tarih | `CallWakePush` | Ne işe yarar |
|---|---|---|---|---|
| `artifacts\rollback\redmigateway-2.0.2-KNOWN-GOOD-20261015-2157.apk` | 7,48 MB | 15.10 22:08 | ❌ **YOK** | **Yalnızca ses** için bilinen-iyi |

**DİKKAT:** Bu APK'da **uyanma özelliği hiç yok** (sessiz push göndericisi, throttle, `ApnsSender.sendBackground`).
Geri dönersen **ses çalışır ama iPhone uyanmaz.** Bilinçli bir tercih olmalı, kaza değil.

---

## ESKİ SÜRÜMLER (yalnızca tarihsel kayıt)

| Dosya | Tarih | Not |
|---|---|---|
| `artifacts\android\redmigateway-v2.0.2-debug-20261011.apk` | 11.10 | Uyanma yok |
| `artifacts\android\*-legacy.apk` (5 adet) | 01-10.10 | Çeşitli "öncesi" anlık görüntüler |
| `artifacts\android\AndroidAutoStub-before-legacy.apk` | 10.10 | 0,17 MB — farklı bir bileşen |
| `repo-source\scratch\**\*.apk` (5 adet) | 01-10.10 | `repo-source` bayat kopyasının içinde — **dokunma** |

---

## KURAL

1. **Kurulacak APK'yı isimden doğrula.** Hepsi aynı sürümü gösterir; sürüme güvenme.
2. Kurmadan önce **hash**'i kontrol et (yukarıdaki iki hash).
3. **`repo-source\` içindeki hiçbir APK'yı kurma** — o klasör 11.10.2026'da dondurulmuş
   (`repo-source\_BAYAT-KULLANMA.md`).
4. Geri dönüş gerekiyorsa **`GATE-REVERTED`**'ı tercih et: uyanma özelliği onda **var**.
5. Kurulum sonrası doğrula:
   ```
   adb shell pm path com.ilkay.redmigateway
   adb pull <yol> %TEMP%\kurulu.apk && certutil -hashfile %TEMP%\kurulu.apk SHA256
   ```
   Hash, kurduğun dosyanınkiyle **birebir** aynı olmalı (bu doğrulama daha önce yapıldı: kurulu APK
   derleme çıktısıyla eşleşiyordu).

---

## NASIL DOĞRULANDI

```
aapt2 dump badging <apk>                     -> versionCode 202 / versionName 2.0.2 (dördünde de aynı)
unzip -> classes*.dex -> ASCII string arama  -> "CallWakePush" / "WakePushThrottle" / "ApnsSender"
```
Sürüm numarası ayırt etmediği için **DEX içeriği** tek güvenilir ayrımdır.
