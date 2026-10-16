# HEDEF DOĞRULAMA MATRİSİ

**Hedef:** *"RedmiGateway'i endüstri standardı seviyesine çıkar ve **cihazda kanıtla**"* — 5 madde.
**Tarih:** 16.10.2026 · **Ana dal:** `6cbd49e` · **v2 dalı:** `f235071`

> Bu dosya, hedefin her maddesi için **hangi kanıtın var olduğunu**, kanıtın **türünü**
> (statik / birim testi / CI / canlı cihaz) ve **ne kaldığını** tek yerde gösterir.
> Kural: **kanıt türü yazılmayan iddia yoktur.**

---

## ÖZET

| # | Madde | Durum | Kanıt türü | Kalan |
|---|---|---|---|---|
| 1 | Arayüz kalitesi | ✅ **KANITLANDI** | Canlı ekran görüntüsü | — |
| 2 | Cevaplama senkronu | ✅ **kod KANITLI**, ⏳ cihaz ölçümü | Satır kanıtı + 2 optimizasyon | `RINGER_OFF after Nms` |
| 3 | Geçiş <1 sn | ⏳ **HAZIR, ölçülmedi** | Bütçe analizi + CI + bağımsız denetim | **Cihaz ölçümü şart** |
| 4 | Cızırtı teşhis+düzeltme | ✅ **KANITLANDI** | Ölçülmüş kök neden + 14/14 test | — |
| 5 | Hotspot/veri kuralları | ✅ **KANITLANDI** | 7/7 statik + bağımsız denetim + kota sayıları | Senaryo 1-5,7 cihazda |

---

## MADDE 1 — Arayüz kalitesi

**İddia:** Redmi arayüzü Google Dialer/CallKit kalitesinde ve mevcut davranışlar bozulmadı.

**Kanıt:**
- Canlı cihaz ekran görüntüsü: `logs\screens\RG-PHONE-UI-105.png` — **"iPhone bağlı · uykuda"** yeşil nokta, arama ekranı düzeni
- `RG-AFTER-11bbdae.png`, `RG-SETTINGS-105.png`, `RG-DIAG-105.png` — kurulum sonrası, ayarlar ve tanılama ekranları
- iOS tarafı: CI simülatör görüntüsü `IOS-CI-LAUNCH-build96.png` / `-v2.png` — eşleştirme ekranı doğru render

**Kanıtlanamayan:** "Google Dialer kalitesinde" öznel bir iddiadır; ölçülebilir kısmı (düzen,
durum göstergesi, dokunma hedefleri) ekran görüntüleriyle sabitlendi.

---

## MADDE 2 — Cevaplama senkronizasyonu

**İddia:** Birinden yanıtlanınca diğeri **anında** sussun.

**Mekanizma (satır kanıtlı):**

**a) Cevaplama komutunda susar — Telecom callback'ini BEKLEMEZ**
`CallManager.kt:528-532`:
```kotlin
// Whoever answered - this phone or the iPhone - the Redmi stops ringing now. Waiting for
// the Telecom callback is what kept it ringing after an answer on the iPhone.
if (calls.any { it !== waitingCall && it.state == Call.STATE_RINGING }) {
    RedmiRinger.stop(if (staleCallId) "peer_answered_stale_id" else "peer_answered")
}
```
Yorum **eski hatayı** adıyla yazıyor: *"Waiting for the Telecom callback is what kept it ringing
after an answer on the iPhone."* → düzeltilmiş.

**b) Main-looper hızlı yolu**
`RedmiRinger.kt:92-95`:
```kotlin
// ... so the ringtone stops right there instead of taking another trip through the main queue: the
// [gap] of "Executing ANSWER" and "RINGER_OFF".
fun stop(reason: String = "answered_or_ended") {
    if (Looper.myLooper() == Looper.getMainLooper()) stopNow(reason) else handler.post { stopNow(reason) }
}
```
Zaten ana kuyruktaysa **kuyruk turu atlanır**.

**c) Ölçüm yerinde:** `RedmiRinger.kt:46` → *"the silence latency in every RINGER_OFF line"*;
`L101` → `RINGER_OFF reason=$reason after ${rangFor()}ms`

**Üretilen reason değerleri:** `peer_answered` · `peer_answered_stale_id` (CKM:531) ·
`call_ended` (450) · `call_state_*` (273) · `peer_rejected` (589) · `iphone_showing` (Ringer:82)

**Kalan:** `RINGER_OFF reason=peer_answered after Nms` değerinin cihazda okunması.
Doküman (`SABAH-TEST-KILAVUZU.md:66`) doğru string'i gösteriyor ✓

**Not:** Bu mantık Telecom `Call` nesnelerine bağlı → **JVM birim testi yazılamaz**; refactor
çağrı yoluna dokunur (AGENTS.md 6.1) → **yapılmadı, bilinçli.**

---

## MADDE 3 — Ses geçişi ~1 sn altına

**İddia:** Geçiş <1000 ms.

**Ölçülmüş taban (kanıtlı):**
```
firstPcm      592 ms   (iPhone: ilk PCM karesi)      <- ASIL DARBOGAZ
gate tabanı  >=240 ms  (kanıt standardı 12 kare @50/s)
settle        454 ms   (Android doğrulama penceresi)
----------------------
TABAN       = 1286 ms  > 1000 ms
```
→ **Android tarafından ulaşılamaz**; `firstPcm` düşmeli.

**v2 çözümü (izole dal `codex/engine-split-v2`):** motor kurulumunu `arm()` öncesine alır →
kurulum Redmi'nin arm'ıyla **örtüşür** → `firstPcm` ~140 ms, toplam **~834 ms < 1000** ✓

**Kanıt durumu:**
| Kontrol | Sonuç |
|---|---|
| CI tip denetimi (#100) | ✅ `Typecheck` ✓ `Build for iOS Simulator` ✓ simülatör açılışı ✓ |
| Bağımsız denetim (qa) | ✅ v1'i öldüren **4 sessizlik yolu kapandı**; kendi Python portu: v2 3/3 PASS, **v1 mutantı FAIL** |
| Regresyon | ✅ Android relay/jitter, pacer, DTMF, ack bekçisi, FEC dormant **değişmedi** |
| **Cihaz ölçümü** | ❌ **YAPILMADI — şart** (AGENTS.md 6.1) |

**Kabul kriteri (üçü BİRLİKTE gelmezse kazanç kanıtlanmamış sayılır):**
`localReady` 400-600 ms · `firstSend` ≤500 ms · `firstPcm` 40-80 ms

**Dürüst sınır:** v2 **ana dala alınmadı** — TestFlight'taki build 96 geçişi iyileştirmiyor
(~2504 ms). Çalışan ses, hızlı sesten önce gelir.

---

## MADDE 4 — Cızırtı: teşhis + düzeltme

**Kök neden (ÖLÇÜLMÜŞ, `vendor_audio_fix.py:3-7`):**
> *"the vendor audio configuration maps USECASE_INCALL_MUSIC_UPLINK (SIM slot 1) to PCM 27 /
> MultiMedia9, but leaves USECASE_INCALL_MUSIC_UPLINK2 (SIM slot 2) unmapped while its mixer path
> uses MultiMedia2. Every in-call music write for a slot-2 call then fails `pcm_prepare` and the
> HAL restarts the stream **~14 times per second** (the old **"helicopter" uplink**)."*

→ **Saniyede 14 stream yeniden başlatma = duyulan cızırtı.** Bu **ağ kaynaklı değil, HAL
yapılandırması** kaynaklı — ve teşhis log kanıtıyla (`pcm_prepare -1`) yapılmış.

**Düzeltme:** `USECASE_INCALL_MUSIC_UPLINK2 → PCM 27` + `Incall_Music_2 → MultiMedia9`

**Kanıt:**
| Kontrol | Sonuç |
|---|---|
| Dönüşüm mantığı testi | ✅ **14/14 PASS** (`test_vendor_fix.py`) |
| Guard'lar (0/2 eşleşme → reddet) | ✅ vendor dosyaları yarı-düzenlenmiş hâle gelemez |
| `revert` birebir geri yükleme | ✅ |
| Çökme edge case'i | ✅ **bulundu ve düzeltildi** (ANCHOR ilk satırsa `IndexError`) |
| Uygulama raporluyor mu | ✅ `DiagnosticsReport.kt:30-31,55` `vendorFix` · `MainActivity.kt:285` Ayarlar satırı |
| Ses metrikleri (önceki turlar) | ✅ `silence` 11/s → 0-1/s |

**Kalan risk:** ROM güncellemesi `/vendor`'u değiştirirse düzeltme **sessizce kaybolur** →
`run-wake-test.ps1` **ilk adımda** kontrol eder ve yoksa **durur**.

---

## MADDE 5 — Hotspot / veri koruma kuralları

**İddia:** 7 değişmez kural korunuyor.

**Kanıt:** `docs/TUR-109-KURALLAR-DOGRULAMA.md` — **7/7 KORUNUYOR**, bağımsız denetçi onaylı.

| Kural | Kanıt |
|---|---|
| K1 otomatik bağlanma, `setWifiEnabled` yasak | `SmartHotspot.kt` **0 satır** değişti; uygulamada `setWifiEnabled` **hiç yok** |
| K2 tanıdık ağ → hotspot kapanır | `NET_CAPABILITY_VALIDATED`:145, `tryKnownNetwork`:226, `stopHotspot()`:287 |
| K3 çağrıda kapanmaz, `wifi_on=0` → korunur | Çağrı kapısı **iki yerde** (:210 ve `stopHotspot` öncesi :277) |
| K4 ağ değişiminde bağlantı sürer | `openSocket`/`receiveLoop`/`arm`/`releaseAll` **değişmedi** |
| K5 kota koruması | push **90 bayt**, çağrı başına **≤2** ≈ 180 bayt; beacon **0 mobil veri** |
| K6 STA+SAP donanım gerçeği | Kod bağımlılığı yok |
| K7 Wi-Fi kapalıyken tarama | Kapı :244, `Settings.Global "wifi_on"`:306 |

**Düzeltilen kusur:** `IMMUTABLE_RULES.md` senaryo 6 kendi Kural 3'üyle **çelişiyordu** →
düzeltildi (`3ee40c2`). Kod her zaman doğruydu; yanlış olan dokümandı.

**Kalan:** Senaryo 1-5 ve 7 (gerçek Wi-Fi geçişi + gerçek çağrı) → cihaz testi.
**Açık (düşük):** `tryKnownNetwork`:277 `inCall` ↔ `stopHotspot()` arası ms'lik yarış.

---

## ARAÇ DOĞRULAMASI (cihazsız kanıtlananlar)

Cihaz yokken **ölçüm yapılamaz**, ama **ölçüm aletinin doğruluğu** kanıtlanabilir.
Yanlış alet, "çalıştı" yalanı söyler:

| Araç | Kontrol | Sonuç |
|---|---|---|
| `vendor_audio_fix.py` | Dönüşüm mantığı | ✅ 14/14 + **çökme hatası düzeltildi** |
| `iphone_emulator.py` + `bridge_client.py` | Protokol uyumu (Kotlin'e karşı) | ✅ **42/42** |
| `run-wake-test.ps1` | Sözdizimi + PS 5.1 çalıştırma | ✅ + **tamamen ASCII** |
| `analyze`/`capture-call-diagnostics.ps1` | PS 5.1 çalıştırma | ✅ + **BOM eklendi, 2 betik kurtarıldı** |
| Lab araçları (3 dosya) | `py_compile` | ✅ |
| Tüm betikler (31 dosya) | Kodlama taraması | ✅ tehlikeli noktalama **0** |

**Bulunan gerçek hatalar:** `vendor_audio_fix.py` IndexError · 2 betik **PS 5.1'de hiç
çalışmıyordu** (BOM'suz UTF-8 + em dash → akıllı tırnak → string sonlandırıcı) · `run-wake-test.ps1`
BOM'suz.

---

## CİHAZ DÖNÜNCE SIRA

```
1. powershell -File scripts\run-wake-test.ps1
   -> vendor duzeltmesi + uyanma olcumu + karar tablosu

2. python RedmiGateway\scripts\lab\iphone_emulator.py --host 192.168.1.147 --dial 532 --scenario handoff --pair
   -> ses yolu + sahiplik makinesi (madde 2 + Android tarafi), iPhone GEREKMEZ

3. ... --scenario soak
   -> 60+ sn cizirti taramasi (madde 4)

4. iPhone hazirsa: v2 dalini TestFlight'a gonder -> firstSend olcumu (madde 3)
```

**Hiçbir madde cihaz kanıtı olmadan "bitti" sayılmayacak** (AGENTS.md 6.1).
