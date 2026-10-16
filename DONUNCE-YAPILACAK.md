# DÖNÜNCE NE YAPILACAK — tek sayfa

**Hazırlanma tarihi:** 16.10.2026 · **Ana dal:** `1ec8a65` · **v2 dalı:** `2585549`

---

## ⚠️ SENDEN İKİ AKSİYON GEREKİYOR

| # | Ne | Neden |
|---|---|---|
| 1 | **Telefonları geri getir** | Madde 1-5'in cihaz kanıtı yalnızca cihazda yapılabilir |
| 2 | **GitHub → Settings → Billing → Actions kotası** | **iOS build ÜRETİLEMİYOR** (aşağıya bak) |

### 🚨 CI kesintisi (16.10.2026)

**Tek adımlık `echo` işi bile runner alamıyor** → hesap seviyesi kısıt:
```
JOB health: failure   runner_id=0   steps=0
```
- Ne iOS iş akışı, ne macOS, ne de bugünkü değişiklikler — **hiçbir runner yok**
- En olası neden: **Actions dakika kotası** (private repo, `macos-latest` **10× çarpanı**, bugün ~100 build)
- **Etki:** v2 (geçiş düzeltmesi) ve CallKit ikonu **build edilemiyor**
- Detay: `CI-KESINTISI-20261016.md`

**Teslim yine de güvende:** build 96 TestFlight'ta, APK hazır, v2 dalı uzakta bekliyor.

### 📌 Ayrıca: varsayılan dal `main` **127 commit geride**

GitHub'da repoya bakan biri **çok eski bir sürüm** görüyor (197 dosya farklı).
Merge etmedim — `HANDOVER.md`: *"Merging to main is the owner's decision."*
**Öneri:** cihaz testi geçtikten sonra `codex/audio-jitter-fix` → `main`.

---

## 0. 🚨 İLK BAKILACAK: VENDOR SES DÜZELTMESİ

**Ses hiç akmıyorsa, sebebi bu olabilir — koddan ÖNCE bunu kontrol et.**

Redmi'nin `/vendor` ses yapılandırması, **SIM slot 2** için `USECASE_INCALL_MUSIC_UPLINK2`'yi
eşlemiyor; her yazma `pcm_prepare` hatası veriyor ve HAL stream'i **saniyede ~14 kez** yeniden
başlatıyor → eski **"helikopter" sesi** (= kullanıcının "cızırtı" dediği şey) ve
**bu düzeltme olmadan iPhone sesi HİÇ çalışmaz.**

**ROM güncellemesi `/vendor`'u değiştirirse düzeltme kaybolur.**

### Kontrol (iki yol, biri yeter)

**A) Redmi ekranından:** Ayarlar (sağ üstteki dişli) → **"iPhone ses kanalı (SIM 2)"**
| Gösterge | Anlamı |
|---|---|
| **"Hazır"** | ✅ Düzeltme yerinde |
| **"Düzeltme yok — iPhone sesi çalışmaz"** | ❌ **Sesin akmamasının sebebi bu** |

**B) Komutla:**
```
cd C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway
python scripts\device\vendor_audio_fix.py status
```
Beklenen: `mixer path fixed: True; platform mapping fixed: True`

**Düzeltme yoksa:**
```
python scripts\device\vendor_audio_fix.py apply
```
(adb root ister; `/vendor` bu ROM'da yazılabilir. Orijinaller `/vendor/etc/phonebridge-backup/`
altında saklanır, `revert` ile geri alınır.)

> **Neden bu madde 1. sırada:** Kodda aradığımız sessizlik yolları (motor ayrımı, geç attach)
> *belirti* olabilir; ama vendor düzeltmesi yoksa **hiçbir kod değişikliği sesi getirmez.**
> Bu tek kontrol, saatlerce yanlış teşhisi önler.
>
> **Kanıt:** `RedmiGateway/docs/AUDIO_ARCHITECTURE.md:30,75` — ölçülmüş kök neden
> (`pcm_prepare -1` → bozuk, periyodik uplink sesi).

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

Tanılamada üç anahtar: `push.key` (APNs anahtarı), `push.iphoneRegistered` (cihaz token'ı),
`push.alertsRegistered` (**alert token** — sessiz push'un şartı).

### Redmi
Üç APK hazır — **önce yıldızlı olanı kur**:

| Dosya | Test | Ne var | Ne zaman |
|---|---|---|---|
| **`apk\app-debug-LINT-FIXES.apk`** | 86 test | Uyanma + kapı tanılaması + **2 API çökme düzeltmesi** | **⭐ Önce bunu kur** |
| `apk\app-debug-GATE-LOGGING.apk` | 86 test | Uyanma + kapı tanılaması | Lint düzeltmeleri istenmiyorsa |
| `apk\app-debug-GATE-REVERTED.apk` | 84 test | Uyanma, kapı değişikliği YOK | Ses yine çalışmazsa yedek |

**Üçünde de uyanma özelliği var.** Android 16'da **üçü de aynı davranır** — lint düzeltmeleri
yalnızca API 26-28'de tetiklenen çökmeleri kapatır, yani **LINT-FIXES'ın dezavantajı yok.**

```
adb -s 17df6357 install -r "C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\apk\app-debug-LINT-FIXES.apk"
```

Detaylı karşılaştırma ve hash'ler: `artifacts\README.md` (repoda `docs\APK-ARSIVI.md`).

---

## 2. İLK TEST: ses çalışıyor mu?

**Önce madde 0** (vendor düzeltmesi "Hazır" mı). Sonra bir çağrı yap. **Beklenen:** ses iPhone'a akıyor.

**Çalışmazsa sırayla:**

**a) Vendor düzeltmesi yerinde mi?** (madde 0) — "Düzeltme yok" ise `apply` et, başka hiçbir şeye bakma.

**b) Log'da hangi sessizlik yolu var?**
- `Early audio start was never attached; engine stopped`
- `engine.start() returned (running=false)`
- `Invalid audio endpoint`
- `Audio path: firstSend=` satırının **YOKLUĞU**

Bunlar **motor ayrımı kaynaklı** yollardır (geri alındı, ama teyit için).

**c) Redmi tarafı:** `GATE_WAIT reason=...` satırı hangi koşulun beklediğini söyler
(`notLive` / `stale` / `tooShort` / `tooFewFrames` / `rateLow`).

**d) Ses geliyor ama cızırtılı/kesikse:** `RELAY_HEALTH` oku — `silence`, `gap`, `dropped`,
`maxWriteMs`. Cızırtı + `pcm_prepare` hatası = **vendor düzeltmesi** (madde 0), kod değil.

Bunlar **motor ayrımı kaynaklı** sessizlik yollarıdır (geri alındı, ama teyit için).

---

## 3. İKİNCİ TEST: uyanma (asıl şikayet)

**Tek komut** — ön kontrolü kendi yapar, çağrıyı otomatik algılar, karar tablosu basar:
```
powershell -ExecutionPolicy Bypass -File "C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\scripts\run-wake-test.ps1"
```
Ne yapar:
1. **Ön kontrol:** cihaz, **vendor ses düzeltmesi** (`/vendor`'dan okur), servis, varsayılan dialer, push kaydı
   → **vendor düzeltmesi yoksa DURUR** ve `apply` komutunu söyler (kod sorunu değil)
2. **Yakalama:** logcat temizlenir, arka planda yakalanır
3. **Çağrı algılama:** otomatik (`arm` / `audio_start` / `Call audio moved` / `Silent wake push`),
   Enter gerekmez. Gevşek desen kullanmaz — arka plan gürültüsüne takılmaz
4. **Karar tablosu:** her metriği hedefiyle karşılaştırır → `[OK]` / `[XX]` / `[??]`

**Ölçülecek üçlü:**

| Metrik | Nerede | Eski | Hedef |
|---|---|---|---|
| `silentPushesSent` | Redmi log | — | **≥1** (0 ise token kaydı eksik) |
| `wakeToConnectMs` | Redmi log | **58 000 ms** | **1 000-3 000 ms** |
| `Wake latency: silent push -> authenticated in N ms` | iPhone log | — | 1 000-3 000 ms |
| `firstSend` (v2 dalı) | iPhone log | ~1000 ms | **≤500 ms** |
| `localReady` (v2 dalı) | iPhone log | — | 400-600 ms |

**Korunması gerekenler (bozulmamalı):**
`silence` 0-1/s · `gap` 52 ms · `dropped` 0 · `maxWriteMs` ≤5 · DTMF · ack bekçisi · **FEC dormant**

**Bekçi satırı çıkarsa korkma:** `Early audio start was never attached after 8 s; releasing the
microphone` — bu **doğru davranış**. Mikrofon bırakıldı; geç gelen attach grafiği yeniden kurdu
(`Attach found no running engine; building the graph now`) ve **ses yine aktı**. v1'de burada
kalıcı sessizlik olurdu.

---

## 3.5 ⭐ iPHONE OLMADAN TEST: lab emülatörü

**Fiziksel iPhone gerekmez.** Emülatör, iPhone tarafını oynar: root adb'den anahtarları okur,
çağrı açar, UDP ile 20 ms 16 kHz PCM akıtır ve **aşağı yön karelerini sayar**.

```
cd C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\scripts\lab
python iphone_emulator.py --host 192.168.1.147 --dial 532 --scenario handoff --pair
```

**`--pair`** taze bir protocol-2 eşleşmesi yapar ve **sonunda gerçek iPhone'un eşleşmesini geri
yükler** → gerçek iPhone çalışmaya devam eder. Lab modu `bridge_client.enable_lab_mode()` ile
açılır.

### Senaryolar ve neyi kanıtladıkları

| Senaryo | Ne yapar | Neyi kanıtlar |
|---|---|---|
| **`handoff`** | 9 aşama: aktarım → ton → **mikrofon susar** → yeniden arm → **iPhone sağır (tek yönlü kayıp)** → geri alma → **manuel dönüş** | **Madde 2** (sahiplik/cevaplama senkronu) + **madde 3** (geçiş) + ses yolu |
| **`soak`** | 12×5 sn, ton açık/kapalı değişimli (**60+ sn**) | **Madde 4** — cızırtı/kesilme uzun çağrıda ortaya çıkar |
| **`race`** | `audio_start`, Telecom çağrıyı kurmadan **önce** gönderilir (iPhone build 33 davranışı) | Erken istek yolu, 20 sn tutma |
| **`security`** | Protocol 1 reddi, sahte proof, yanlış kod, tekrarlanmış/sahteleşmiş kareler | Güvenlik |
| **`sync`** | Journal replay: tam senkron, sonra `lastSeq` sonrası boş replay | Senkron |

### Nasıl okunur
Her saniye şu satır basılır:
```
  [handoff] downlink 50/s nonzero 48/s sending=True
```
- **`downlink 0/s`** → ses **akmıyor** (kullanıcının şikayeti tam bu)
- **`nonzero`**, gelen karelerin **gerçekten ses içerdiğini** gösterir (sessizlik değil)
- Etiketler aşamaları ayırır: `handoff` / `iphone_tone` / `iphone_silent` / `rearmed` /
  `iphone_deaf` / `rearmed_again` / `manual_redmi`

Sonunda `owners:` satırı **sahiplik durum makinesinin** tüm geçişlerini döker — madde 2'nin kanıtı.

### Neden bu, elle çağrıdan iyidir
- **Otomatik ve sayısal** — "ses geliyor gibi" demek gerekmez
- **iPhone gerekmez** → iPhone hazır olmasa bile Redmi tarafı doğrulanır
- **Tekrarlanabilir** — aynı senaryo, aynı ölçüm
- **Sınırı:** iOS'un `firstPcm`/`firstSend` metriklerini ölçemez (onlar iPhone tarafında) →
  **v2 için yine gerçek iPhone gerekir.** Emülatör madde 2/4'ü ve Android tarafını kanıtlar.

**Doğrulandı:** üç lab aracı da temiz derleniyor (`py_compile`), `websockets 15.0.1` kurulu.

---

## 4. BİLİNEN DURUMLAR (dürüst)

| Konu | Durum |
|---|---|
| Ses akışı | ✅ Düzeltildi (revert), **cihazda teyit edilmedi** |
| Sessiz push uyanması | ⏳ Kod tam, **cihazda teyit edilmedi** |
| Ses geçişi <1000 ms | ❌ **Karşılanmıyor** — optimizasyon geri alındı, ~2504 ms'ye döndü |
| Cızırtı/ses kesilmesi | ✅ Önceki turlarda çözüldü (`silence` 11/s → 0-1/s) |
| Arayüz | ✅ Canlı doğrulandı ("iPhone bağlı · uykuda") |
| Hotspot/veri kuralları | ✅ **7/7 kural korunuyor** (statik + bağımsız denetim) — kota ölçüldü: push **90 bayt**, çağrı başına **≤2** → `docs/TUR-109-KURALLAR-DOGRULAMA.md` |
| Beacon pil etkisi | ⏳ **Ölçülmedi** — `dumpsys batterystats` ile ölçülecek |
| Açık yarış (düşük) | ⏳ `tryKnownNetwork`:277 `inCall` ↔ `stopHotspot()` arası ms'lik pencere — **cihaz testi olmadan değiştirilmeyecek** |

---

## 5. SES GEÇİŞİ v2 — HAZIR, CİHAZ TESTİ BEKLİYOR

Optimizasyonun düzeltilmiş hâli **izole dalda hazır**: `codex/engine-split-v2` (`f235071` / uzak `369b945b`).
**Ana dal (`codex/audio-jitter-fix` = build 96) dokunulmadı** — TestFlight sadece oradan gidiyor.

### Durum
| Kontrol | Sonuç |
|---|---|
| CI tip denetimi (#100) | ✅ **success** — `Typecheck` ✓ `Build for iOS Simulator` ✓ simülatör açılışı ✓ |
| v1'i öldüren **M1** | ✅ **KAPANDI** — kendini durduran zamanlayıcı yok; **geç attach grafiği yeniden kuruyor** |
| M2 (`player.play()`) | ✅ grafikle başlıyor, attach'e bağlı değil |
| M3 (mikrofon açık kalması) | ✅ **KAPANDI** — 8 sn'lik **log'lu/idempotent bekçi**, kalıcı sessizlik üretmiyor |
| M4 (kapı uyuşmazlığı) | ✅ tek kapı `engineGateOpen(for:)` |
| Bağımsız denetim | ✅ **"M3 gerçekten kapandı; yeni sessizlik yolu bulunamadı"** (denetçi kendi Python portunu yazdı: v2 3/3 PASS, v1 mutantı FAIL) |
| Regresyon | ✅ Android relay/jitter, pacer, DTMF, ack bekçisi, FEC dormant **değişmedi** |

### Cihaz testi (bu yapılmadan ana dala ALINMAZ — AGENTS.md 6.1)
```
git checkout codex/engine-split-v2
# TestFlight'a göndermek için release=true ile dispatch et
```
**Senaryolar:**
1. Redmi'den giden çağrı, iPhone **uykuda** → uyanma + ses akışı
2. iPhone'dan "sesi aktar" → **geçiş süresi ölçümü**
3. Çağrı sırasında ağ değişimi → ses kopmuyor
4. **Aktarım 4 sn'yi aşsın** (M1 senaryosu) → ses **yine de akmali**; log'da bekçi satırı görünebilir
5. Pickup senaryosu → ses akıyor
6. Bekçi hiç ateşlenmesin (normal akış) → log'da bekçi satırı **olmamalı**

### Ölçülecek üçlü (üçü birlikte gelmezse kazanç kanıtlanmamış sayılır)
| Metrik | Eski | Hedef |
|---|---|---|
| `take on iPhone requested → firstSend` (**asıl**) | ~1000 ms | **≤500 ms** |
| `localReady` (kritik yolda olduğunun kanıtı) | — | 400-500 ms |
| `firstPcm` (kısmen tanım kayması — raporla) | 592 ms | 40-80 ms |
| Toplam geçiş | 2504 ms | **<1000 ms** |

**Korunması gerekenler:** `silence` 0-1/s · `gap` 52 ms · `dropped` 0 · `maxWriteMs` ≤5 · DTMF · ack bekçisi · **FEC dormant**

### Bilinçli sınır (dürüst)
Pacer dokunuşu (`beginImmediately`, ~60 ms) v2'den **çıkarıldı** — motor ayrımından bağımsız bir değişiklik olduğu için (AGENTS.md 6.2: tek build'de tek risk). Bütçe onsuz da yetiyor: ~140 + 240 + 454 = **~834 ms < 1000**.

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
| `3ee40c2` | `IMMUTABLE_RULES.md` çelişkisi düzeltildi (senaryo 6 ↔ Kural 3) |
| `3acdd6b` | Değişmez kurallar doğrulaması — **7/7 korunuyor** |
| `2fa4597` | v2 spec: 4 sessizlik yolu + 6 şart (**v2 dalı**) |
| `fe565a8` | v2 motor ayrımı (**v2 dalı**) |
| `5607e3f` | v2 derleme düzeltmesi — pacer dokunuşu kapsam dışı (**v2 dalı**) |
| `f235071` | v2 mikrofon bekçisi — M3 kapandı (**v2 dalı**) |

> **Dal ayrımı:** `afd8ad6`…`3acdd6b` = **ana dal** (`codex/audio-jitter-fix`, TestFlight).
> `2fa4597`…`f235071` = **v2 dalı** (`codex/engine-split-v2`, izole, TestFlight'a gitmez).

---

## 7. ÖNEMLİ DOSYALAR

- `TUR-106-108-SES-REGRESYONU.md` — regresyon + kök neden + denetim
- `TUR-105-DURUM.md` — uyanma çözümü + bütçe analizi
- `docs\TUR-109-KURALLAR-DOGRULAMA.md` — 7/7 değişmez kural doğrulaması
- `docs\ENGINE-SPLIT-V2-SPEC.md` — v2 spec + 6 şart + kabul kriterleri
- `scripts\run-wake-test.ps1` — ölçüm betiği
- `scripts\push-both-repos.ps1` — iki repoya push
- `AI-DERSLERI.md` — kalıcı dersler (AGENTS.md kopyası)
