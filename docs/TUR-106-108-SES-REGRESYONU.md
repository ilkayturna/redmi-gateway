# TUR 106-108 DURUM — SES REGRESYONU VE GERİ ALMA

**Tarih:** 16.10.2026 · **Branch:** `codex/audio-jitter-fix` · **Uzak HEAD:** `219b9db` / arşiv `f7f7ac0`

---

## 1. NE OLDU (dürüst anlatım)

Bu turda **sesi kırdım**. Kullanıcı bildirdi: *"iPhone'a hiç akmamaya başladı."*

**Zincir:**
1. callflow `firstPcm` gecikmesini düzeltmek için **motor ayrımı** yaptı (`startLocal` / `attachNetwork`) — commit `7055cb0`
2. Derleme hatası çıktı (`startLocal` içinde çıplak `return`) → düzeltildi (`7006a3f`)
3. **Build 93** ile iPhone'a gitti (ayrımın ilk kez *çalışan* sürümü)
4. Kullanıcı test etti → **ses akmıyor**
5. **Geri aldım** (`afd8ad6`): 4 dosya `b5a5845` sürümüne döndü
6. **Build 96** → TestFlight'a yüklendi ✅

**Temel hata (benim):** Bir optimizasyonu **gerçek çağrı testi olmadan** kullanıcıya gönderdim. Üstelik uyanma düzeltmesiyle **aynı build'de** gönderdim — böylece birindeki regresyon diğerini gizledi.

---

## 2. KÖK NEDEN (bağımsız denetçi buldu — qa-adversarial)

Denetçi `7055cb0` diff'inden **dört somut sessizlik yolu** çıkardı. En güçlüsü:

### M1 — 4 saniyelik zamanlayıcı + sessiz guard (deterministik)
```swift
// startLocal içinde:
DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
    guard let self, self.earlyStartToken == token, !self.attached else { return }
    self.log("Early audio start was never attached; engine stopped")
    self.stop()                                    // ← motoru durdurur
}
// attachNetwork girişinde:
guard running, !attached else { return }           // ← SESSİZ düşer (log yok)
```
Aktarım 4 sn'yi aşarsa (uyanma 1-3 sn + kurulum + arm gecikmesi → **aşar**):
motor durur → sonra gelen `attachNetwork` guard'da **sessizce** düşer →
`startEngine` bir daha çağrılmaz (`audio_ready` arm başına **bir kez** gelir) →
**Redmi'nin rölesi armed ve medya akarken iPhone tarafı hiç bağlanmaz = ses yok.**

### Diğerleri
- **M2:** `player.play()` yalnızca `attachNetwork`'e taşındı → attach atlanırsa **aşağı yön hiç başlamaz** (eskiden motor başlar başlamaz çalıyordu)
- **M3:** endpoint doğrulaması `attachNetwork`'e taşındı → `early: false` yolunda grafik kurulup motor başladıktan sonra doğrulama düşerse **mikrofon açık, ağ yok, temizlik yok**
- **M4:** `takeCallOnIPhone` erken motoru `audioActivated` iken başlatıyor, ama attach `isAudioEligible(uuid)` kapısından geçiyor; pickup'ta bu kapı false → erken motor asla attach edilemez → 4 sn sonra M1 onu durdurur

**Not:** M1-M4 **kesin kanıtlanmadı** (kullanıcının cihaz logu gerekli), ama üçü de ayrımdan **önce yoktu** → revert doğru karar.

---

## 3. BAĞIMSIZ DENETİM SONUCU (commit `afd8ad6`)

| İddia | Sonuç | Kanıt |
|---|---|---|
| **B1** Revert ses yolunu birebir eski hâline döndürdü | **DOĞRU** | `git diff b5a5845 afd8ad6 -- <4 dosya>` → **0 satır**; `startLocal`/`attachNetwork`/`isAttached`/`earlyStartToken`/`localGeneration` repo genelinde **yok** |
| **B2** Sesi motor ayrımı kırdı | KANITLANAMADI ama **4 sessizlik yolu bulundu** | M1-M4 (yukarıda), satır kanıtlı |
| **B3** `remote-notification` sesi etkilemez | **DOĞRU** | `b5a5845` yalnızca `Info.plist` +3 satır; `UIBackgroundModes` yalnızca arka plan yürütme izni beyan eder, AVAudioSession/CallKit ile etkileşimi yok |
| **B4** Kalan her şey korundu | **DOĞRU** | `remote-notification` ✓ · CI tip denetimi ✓ (`build-ios.yml:56-65`, `-simulator` düzeltmesiyle) · `GATE_WAIT` ✓ (`CallAudioRelay.kt:592`) · push/throttle dosyaları revert listesinde yok ✓ |

---

## 4. TESLİM DURUMU

| Öğe | Durum |
|---|---|
| **iOS build 96** | ✅ **success** — `Typecheck app sources` ✓, `Upload to App Store Connect` ✓ |
| İçeriği | Bilinen-çalışan ses yolu (**birebir**) + `remote-notification` (sessiz push uyanması) |
| TestFlight grubuna ekleme | 🔄 job **#97** |
| iOS build 93 (sesi kıran) | ❌ kullanılmamalı — **96'yı kur** |
| Android APK | Kurulu: `11:51:10`, SHA256 `5CDCB5E2...` (86 test) — kapı loglaması var, ses yolu değişmedi |
| Android CI | #32, #34 **success** |
| Bağımsız denetim | **yanlışlanan iddia yok**, revert doğrulandı |

**TestFlight'ta aranacak:** sürüm **2.0** → build **96**.

---

## 5. SIRADAKİ ÖLÇÜM (kullanıcı telefonlarla dönünce)

Ön koşullar:
1. iPhone'da **build 96** kurulu
2. Uygulama **bir kez açılmış** (alert token kaydı — sessiz push'un şartı)

Ölçülecek üçlü:
| Metrik | Eski | Hedef |
|---|---|---|
| `wakeToConnectMs` (uyanma) | **58 000 ms** | 1 000-3 000 ms |
| `silentPushesSent` | — | ≥1 (yoksa token kaydı eksik) |
| `gateMs` / `gateFrames` / `gateRate` | 1 431 ms / 76 / — | 250-500 ms |
| `GATE_WAIT reason=` | — | hangi koşul bekliyor (yeni tanılama) |
| `silence` / `gap` / `dropped` / `maxWriteMs` | 0-1/s / 52 ms / 0 / ≤5 | **korunmalı** |
| FEC | dormant | **dormant kalmalı** |

Betik hazır: `scripts\run-wake-test.ps1` — logcat yakalar, çağrı sonrası özetler.

**Ses çalışmıyorsa ilk bakılacaklar (log):** `Early audio start was never attached`,
`engine.start() returned (running=false)`, `Invalid audio endpoint`,
`Audio path: firstSend=` satırının **yokluğu**.

---

## 6. MOTOR AYRIMI YENİDEN DENENİRSE (denetçinin şartları)

**Uygulanmadan önce hepsi karşılanmalı:**
1. **Zamanlayıcıyla kendini durdurma olmasın** — attach **idempotent + tekrar denenebilir** olsun
2. `player.play()` **attach'e bağlı olmasın**
3. Endpoint doğrulaması **grafik kurulmadan ÖNCE** yapılsın
4. Erken motoru başlatan kapı ile attach kapısı **aynı** olsun
5. Karşılaştırılabilir bir `firstPcm` tanımı korunsun (tanım kayması olmasın)
6. **Gerçek çağrıda cihaz testi** yapılmadan kullanıcıya gönderilmesin

---

## 7. KALICI DERSLER (AGENTS.md'ye yazıldı)

- **2.3** — Sessiz push için `UIBackgroundModes`'ta `remote-notification` **şart**; 7 adımlı kontrol listesi
- **4.3** — `workflow_dispatch` parametrelerini okumadan tetikleme (build 88 hiç var olmadı)
- **Yeni (bu tur):** *Ses yoluna dokunan bir optimizasyon, cihazda gerçek çağrı testi olmadan gönderilmez. Uyanma düzeltmesi ile ses optimizasyonu aynı build'de gönderilmez — biri diğerinin regresyonunu gizler.*
