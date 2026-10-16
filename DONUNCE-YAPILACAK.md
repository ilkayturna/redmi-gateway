# DÖNÜNCE NE YAPILACAK — tek sayfa

**Hazırlanma tarihi:** 16.10.2026 · **Uzak HEAD:** `ccb7839` (ana) / `d11c30b` (arşiv)

---

## 1. TELEFONA KURULACAKLAR

### iPhone
**TestFlight → sürüm 2.0 → build 96**

> ⚠️ **build 93'ü KURMA** — sesi kıran sürüm o.
> build 96 = bilinen-çalışan ses yolu (**birebir**, denetçi 0-satır diff ile doğruladı)
> **+** sessiz push uyanması (`remote-notification`).

Sonra **uygulamayı bir kez aç** → alert token kaydı (sessiz push'un ön koşulu).
Token kaydolmazsa log şunu der:
`Silent wake push not sent: the iPhone has registered no alert token yet`

### Redmi
İki APK hazır — **önce ASIL olanı kur**:

| Dosya | Test | Ne var | Ne zaman |
|---|---|---|---|
| `apk\app-debug-GATE-LOGGING.apk` | 86 test | Uyanma + `GATE_WAIT` tanılaması | **Önce bunu kur** |
| `apk\app-debug-GATE-REVERTED.apk` | 84 test | Uyanma, kapı değişikliği YOK | Ses yine çalışmazsa yedek |

```
adb -s 17df6357 install -r "C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\apk\app-debug-GATE-LOGGING.apk"
```

---

## 2. İLK TEST: ses çalışıyor mu?

Bir çağrı yap. **Beklenen:** ses iPhone'a akıyor.

**Çalışmazsa** (yedek APK'yı kurmadan önce) şu log satırlarına bak — hangisi varsa kök neden odur:
- `Early audio start was never attached; engine stopped`
- `engine.start() returned (running=false)`
- `Invalid audio endpoint`
- `Audio path: firstSend=` satırının **YOKLUĞU**

Bunlar **motor ayrımı kaynaklı** sessizlik yollarıdır (geri alındı, ama teyit için).

---

## 3. İKİNCİ TEST: uyanma (asıl şikayet)

Ölçüm betiği hazır:
```
C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\scripts\run-wake-test.ps1
```
Betik logcat'i yakalar, çağrı sonrası özetler.

**Ölçülecek üçlü:**

| Metrik | Nerede | Eski | Hedef |
|---|---|---|---|
| `silentPushesSent` | Redmi log | — | **≥1** (0 ise token kaydı eksik) |
| `wakeToConnectMs` | Redmi log | **58 000 ms** | **1 000-3 000 ms** |
| `Wake latency: silent push -> authenticated in N ms` | iPhone log | — | 1 000-3 000 ms |

**Korunması gerekenler (bozulmamalı):**
`silence` 0-1/s · `gap` 52 ms · `dropped` 0 · `maxWriteMs` ≤5 · DTMF · ack bekçisi · **FEC dormant**

---

## 4. BİLİNEN DURUMLAR (dürüst)

| Konu | Durum |
|---|---|
| Ses akışı | ✅ Düzeltildi (revert), **cihazda teyit edilmedi** |
| Sessiz push uyanması | ⏳ Kod tam, **cihazda teyit edilmedi** |
| Ses geçişi <1000 ms | ❌ **Karşılanmıyor** — optimizasyon geri alındı, ~2504 ms'ye döndü |
| Cızırtı/ses kesilmesi | ✅ Önceki turlarda çözüldü (`silence` 11/s → 0-1/s) |
| Arayüz | ✅ Canlı doğrulandı ("iPhone bağlı · uykuda") |
| Hotspot/veri kuralları | ⏳ Koda dokunulmadı, teyit bekliyor |

---

## 5. SES GEÇİŞİ TEKRAR DENENİRSE — denetçinin 6 ŞARTI

Optimizasyon (motor ayrımı) **bu şartlar sağlanmadan gönderilmeyecek**:

1. **Zamanlayıcıyla kendini durdurma olmasın** — attach **idempotent + tekrar denenebilir**
2. `player.play()` **attach'e bağlı olmasın** (aşağı yön attach'tan bağımsız başlasın)
3. Endpoint doğrulaması **grafik kurulmadan ÖNCE**
4. Erken motoru başlatan kapı ile attach kapısı **aynı** olsun
5. Karşılaştırılabilir bir `firstPcm` tanımı (tanım kayması olmasın)
6. **Cihazda gerçek çağrı testi** yapılmadan kullanıcıya gönderilmesin

Ayrıca: sessiz `guard ... else { return }` **yasak** — her guard log basmalı.

---

## 6. NELER DEĞİŞTİ (özet)

| Commit | Ne |
|---|---|
| `2ffab8a` | Uyanma: sessiz push + beacon (Android + iOS) |
| `11bbdae` | `GATE_WAIT`/`GATE_OPEN` tanılaması (Android) |
| `b5a5845` | `remote-notification` — **sessiz push'u ölü olmaktan kurtardı** |
| `7055cb0`+`7006a3f` | Motor ayrımı → **sesi kırdı** |
| `afd8ad6` | **GERİ ALMA** — ses bilinen-iyi hâline döndü |
| `914d090` | CI tip denetimi (tüm kaynakları derler; iki build kırılmasını yakalardı) |
| `b337126` | AGENTS.md bölüm 6 — sürüm disiplini |

---

## 7. ÖNEMLİ DOSYALAR

- `TUR-106-108-SES-REGRESYONU.md` — regresyon + kök neden + denetim
- `TUR-105-DURUM.md` — uyanma çözümü + bütçe analizi
- `scripts\run-wake-test.ps1` — ölçüm betiği
- `scripts\push-both-repos.ps1` — iki repoya push
- `AI-DERSLERI.md` — kalıcı dersler (AGENTS.md kopyası)
