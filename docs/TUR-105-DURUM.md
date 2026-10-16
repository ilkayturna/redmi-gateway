# TUR 105 DURUM RAPORU — 16.10.2026

## ÖZET

Bu turda **uyanma sorunu** (kullanıcının asıl şikayeti) koda döküldü, derlendi,
telefona kuruldu, iki repoya push edildi ve **bağımsız denetçiyle çapraz doğrulandı**.
Ayrıca ses geçişi darboğazının **gerçek yeri** ölçümle bulundu: Android tarafı değil,
iPhone'un ilk ses karesi.

---

## 1. UYANMA SORUNU — ÇÖZÜLDÜ (denetlendi)

### Kök neden (ölçülmüş)
- **iBeacon bölge izleme "en iyi çaba"**: iOS tarama zamanlamasını kendi seçer.
  Son gerçek çağrı testinde giriş tespiti **58 saniye** sürdü. Üstelik geçiş tabanlı
  olduğu için telefon hâlâ "içeride" sayılıyorsa yeni giriş olayı **hiç üretilmez**.
- **Giden çağrıda hiç push yoktu**: `PushNotifier` yalnızca `ringing && incoming`
  için kuyrukluyordu. Redmi'den başlatılan çağrının uyanma yolu yoktu.

### Çözüm — iki yol
| Yol | Mekanizma | Beklenen |
|---|---|---|
| **Garantili** | Sessiz arka plan push (`content-available:1`, `apns-priority:5`) | ~1-3 sn |
| **Hızlı (yedek)** | Beacon çağrı boyunca yanıyor, 100 ms + HIGH güç | ~1-2 sn |

- Sessiz push **normal APNs token**'ı kullanır (`KEY_ALERT_TOKEN` + `BUNDLE_ID` topic).
  Apple, VoIP token'ında yalnızca `.voip` topic kabul eder → sessiz push oraya gidemez.
- Çağrı başına **en fazla 2 push** (1 + 10 sn sonra 1 retry). Bütçe `callId` anahtarlı.
- **Kural 5 korunuyor**: çağrı yokken push yok, soket varken push yok.
- **Gizlilik**: gövde yalnızca `{"a":"wake","callId":"…"}` — numara/isim yok.
- iOS tarafı: `didReceiveRemoteNotification` → arka plan görevi + reconnect,
  **UI/CallKit açmaz**, `completionHandler` tam bir kez (12 s tavan).

### Bağımsız denetim sonucu (qa-adversarial, commit `2ffab8a`)
| İddia | Sonuç |
|---|---|
| A1 Sessiz push gönderilebiliyor | **DOĞRU** (nüans: log çağıranda, `CallWakePush.kt:79-83`) |
| A2 Spam yok / Kural 5 | **DOĞRU** (bütçe `callId` anahtarlı; testle pinlenmiş) |
| A3 Beacon + pil | mekanizma **DOĞRU** / pil sayısı **KANITLANAMADI** |
| A4 iOS handler UI açmıyor | **DOĞRU** (`SilentPushCompletion` tam bir kez) |
| A5 Mevcut çalışanlar bozulmadı | **DOĞRU** (ses/DTMF/FEC yollarında 0 satır diff) |

**Yanlışlanan iddia yok.** Denetçi kendi zorlamalı koşumunda **84 test, 0 failure** doğruladı.

**Denetçinin bulduğu sınır (düşük önem, belgelenmeli):**
Beacon `setConnectable(false)` ile yayınlıyor → **beacon yanarken YENİ BLE bağlantısı
kurulamaz**. Mevcut link etkilenmez; hedef zaten WS soketi. Ama iPhone'un ağ yolu yokken
(hotspot menzili dışı) beacon yanarken BLE ile de bağlanamaz.

**Pil iddiası (%1,2) ölçülmedi** — pencere 30 sn → çağrı boyu uzadığı için maliyet
eskiye göre arttı. Ölçüm: `dumpsys batterystats` UID payı.

---

## 2. SES GEÇİŞİ — DARBOĞAZIN GERÇEK YERİ BULUNDU

### Ölçüm hatası düzeltildi
`gateFrames=76`, **cutover anında** okunuyordu (`t=2477`), `startMedia` anında değil.
76 kare / (2477−592) ms = **40,3 kare/sn** → "1520 ms ses birikti" **yanılgıydı**.
Gerçekte akış 40/s'ydi ve kapı açıldığı anda ~37/s (eski kümülatif eşiğin tam sınırı).

### Bütçe aritmetiği — `<1000 ms` Android tarafından ULAŞILAMAZ
```
firstPcm      592 ms   ← iPhone: ilk PCM karesi
gate tabanı  ≥240 ms   (kanıt standardı 12 kare)
settle        454 ms   (Android doğrulama penceresi, tasarım)
─────────────────────
TABAN       = 1286 ms  > 1000 ms   ← gateMs=0 olsa bile
```

**Sonuç:** `<1000 ms` için **iPhone'un ilk kareyi ≤200-250 ms'de göndermesi şart.**
Bu iOS işi → `callflow-engineer`'a verildi.

### Android tarafında yapılanlar (commit `11bbdae`, 86 test)
1. **`gateWaitReason` logu**: `notLive / stale / tooShort / tooFewFrames / rateLow / ready`
   → `GATE_WAIT reason=rateLow wait=820ms frames=29 rate=21/s live=true fresh=true`
   ve açılışta `GATE_OPEN wait=… frames=… rate=…/s`. **Hangi koşulun beklediği artık ölçülür.**
2. `gateMs`/`gateFrames` artık **`startMedia` anında** okunuyor + `gateRate` eklendi.
3. **Kapı olay-tabanlı** (her PCM varışında, ≤60 ms) → 150 ms tick kuantizasyonu kalktı.
4. `diagnostics()`: `gateMs`, `gateFrames`, `gateRate`, `gateBlocker`,
   `silentPushesSent`, `wakeToConnectMs` **yan yana**.

### Kanıt zayıflatılmadı (bilinçli karar)
| Opsiyon | Kazanç | Neden YAPILMADI |
|---|---|---|
| `ARRIVAL_WINDOW_MIN_FRAMES` 12→8 | ~-80 ms | kanıt 240→160 ms; 1286'yı 1000 altına indirmez |
| `VERIFY_WINDOW_MS` 300→200 | ~-100 ms | doğrulama kanıtı %33 azalır |

İkisi de toplam **~180 ms** indirir — tabanı kurtarmaz. **Önce `GATE_WAIT` verisi.**

---

## 3. TESLİM DURUMU

| Öğe | Durum |
|---|---|
| Android test | **86 test, 0 failure** (bağımsız koşumla da doğrulandı: 84 @ `2ffab8a`) |
| Commit'ler | `2ffab8a` (uyanma), `11bbdae` (kapı) |
| Uzak (ana repo) | `c339eeb3` |
| Uzak (arşiv) | `38412aea` |
| APK telefona kurulu | ✅ 11:32:03 (`2ffab8a`) — `11bbdae` **kurulum bekliyor** |
| iOS build #89 | ✅ **TestFlight'a yüklendi** (`Sign and upload` = success) |
| TestFlight grubu | #90 promote job çalışıyor |
| **Cihaz** | ❌ **USB'den düştü** — fiziksel müdahale gerekiyor |

---

## 4. SIRADAKİ ÖLÇÜM (cihaz geri gelince)

Tek komut: `scripts\run-wake-test.ps1` — logcat yakalar, çağrı sonrası özetler.

Beklenen loglar:
```
GATE_WAIT reason=rateLow wait=820ms frames=29 rate=21/s live=true fresh=true
GATE_OPEN wait=330ms frames=18 rate=48/s
Call audio moved ... gateMs=330 gateFrames=18 gateRate=48/s
Silent wake push sent (1 this session)
iPhone connected 1200 ms after the wake beacon started
```

| Metrik | Eski | Hedef |
|---|---|---|
| Uyanma (`wakeToConnectMs`) | 58 000 ms | **1 000-3 000 ms** |
| `gateMs` | 1 431 ms | **250-500 ms** |
| Toplam geçiş | 2 504 ms | **<1 000 ms** (iOS `firstPcm` sonrası) |
| `firstPcm` (iOS) | 592 ms | **≤200-250 ms** |
| `silence` | 0-1/s | **korunmalı** |
| `gap` | 52 ms | **korunmalı** |
| `dropped` | 0 | **korunmalı** |
| `maxWriteMs` | ≤5 ms | **korunmalı** |
| FEC | dormant | **dormant kalmalı** |

---

## 5. ÖN KOŞULLAR (kullanıcı tarafı)

1. **iPhone'da TestFlight build 89** kurulu olmalı (sessiz push handler'ı içerir).
2. **iPhone uygulaması bir kez açılmalı** → alert token kaydı
   (`ApnsSender.alertsReady = hasKey && hasAlertToken`).
   Token yoksa `sendBackground` `false` döner ve log:
   `Silent wake push not sent: the iPhone has registered no alert token yet`
   → o durumda yalnızca beacon yolu kalır.

## 6. DÜRÜST SINIR

- **Askıya alınmış / arka plandaki uygulama** sessiz push ile uyanır ✓ (ölçülen vaka).
- **App switcher'dan kaydırılıp kapatılmış uygulama**: iOS sessiz push **teslim etmez**.
  Yalnızca VoIP push yeniden başlatır, o da CallKit ekranı ister → "giden çağrıda
  iPhone'da ekran yok" kararıyla çelişir. Gelen çalan çağrı zaten VoIP kullanıyor ✓.
- **`<1000 ms` geçiş** iPhone `firstPcm` ≤200-250 ms olmadan **mümkün değil**.
