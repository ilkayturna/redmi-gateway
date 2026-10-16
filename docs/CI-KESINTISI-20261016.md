# 🚨 CI KESİNTİSİ — GitHub Actions runner ATANMIYOR (16.10.2026)

**Tespit:** 16.10.2026 10:00 UTC · **Durum:** Devam ediyor · **Etki:** iOS build ÜRETİLEMİYOR

---

## BELİRTİ

Koşumlar **5 saniyede** bitiyor, **0 adım** çalışıyor, **runner atanmıyor**:

```json
{
  "name": "Compile iOS app",
  "conclusion": "failure",
  "steps": [],
  "runner_id": 0,
  "runner_name": "",
  "started_at": "2026-10-16T10:03:34Z",
  "completed_at": "2026-10-16T10:03:39Z"
}
```

`runner_id: 0` + `runner_name: ""` + `steps: []` = **iş hiç başlamadı.** Bu bir derleme hatası
**değildir** — runner hiç tahsis edilmedi.

## KANIT: Benim değişikliğim değil

| Koşum | Dal / sha | İş akışı | Sonuç |
|---|---|---|---|
| #52 | `7c3a8c1` | Android | ✅ **success** ← son başarı |
| #53 | `a6b54f4` | Android | ❌ failure |
| #54-#59 | çeşitli | Android | ❌ failure |
| #101, #102 | v2 dalı | iOS | ❌ failure |
| **#103** | **ANA DAL `761a4f1`** | iOS | ❌ **failure** |
| #103 (attempt 2) | ana dal | iOS | ❌ failure |
| **#104** | **ANA DAL** | iOS | ❌ **failure** |

**Ana dal** (bilinen-iyi, build 96'yı üreten aynı kod) **de** başarısız → **kodla ilgisi yok.**
**Android de** başarısız → **iOS iş akışıyla ilgisi yok.**

## ELENEN NEDENLER

| Olası neden | Kontrol | Sonuç |
|---|---|---|
| GitHub genel kesintisi | `githubstatus.com` | ✅ "All Systems Operational" |
| Repo arşivlenmiş/kapatılmış | API | `archived=False disabled=False` |
| Actions kapalı | `actions/permissions` | `enabled=true allowed_actions=all` |
| İş akışı YAML hatası | Android de aynı şekilde çöküyor | ❌ elendi |
| Benim son değişikliğim | Ana dal da çöküyor | ❌ elendi |

## EN OLASI NEDEN (kanıtlanamadı — token yetkisi)

**GitHub Actions dakika/kota tükenmesi.**
- Repo **private** → Actions dakikaları **faturalı**
- **`macos-latest` çarpanı 10×** → 1 dakika = 10 dakika kota
- Bugün **~100 iOS build** koştu → kaba hesap **binlerce dakika**
- Faturalama uçları (`/settings/billing/actions`) bu token'la **404** veriyor → kesin doğrulama yapılamadı

**Doğrulama kullanıcıya ait:** GitHub → Settings → Billing → Actions dakikaları ve harcama limiti.

---

## ETKİ

| İhtiyaç | Durum |
|---|---|
| **Build 96 (TestFlight)** | ✅ **Zaten yüklü** — teslim güvende |
| Android APK | ✅ **Yerel derlenebiliyor** (86 test, 0 failure) — CI gerekmiyor |
| **v2 iOS build** (geçiş düzeltmesi) | ❌ **ENGELLİ** — CI şart |
| Gelecekteki her iOS değişikliği | ❌ ENGELLİ |

**iOS, macOS/Xcode gerektirir; bu makinede üretilemez. Yerel alternatif YOK.**

---

## YAPILANLAR

1. **v2 dalı uzakta güvende:** `codex/engine-split-v2` = `a04d635` (motor ayrımı + CallKit ikonu
   + `.gitignore` düzeltmesi). CI döner döndönmez **tek dispatch** ile build alınır.
2. **Android yolu canlı:** yerel Gradle derlemesi doğrulandı (86 test, 0 failure).
3. **Build 96 TestFlight'ta:** kullanıcının asıl ihtiyacı (çalışan ses + sessiz push) karşılanmış durumda.

## ÇÖZÜM SEÇENEKLERİ

| # | Seçenek | Kim yapar |
|---|---|---|
| 1 | **Faturalama/harcama limitini kontrol et ve yükselt** | **Kullanıcı** |
| 2 | Kota sıfırlanmasını bekle (aylık döngü) | — |
| 3 | macOS kullanımını azalt: `release=false` ile yalnızca tip denetimi koştur | — |
| 4 | Build'i **public** bir repoya taşı (public repolarda Actions ücretsiz) | Kullanıcı kararı |

---

## TEKRAR DENEME

CI döndüğünde:
```
# v2 build (TestFlight'a)
gh workflow run build-ios.yml --ref codex/engine-split-v2 -f release=true

# yalnızca derleme kontrolü (daha ucuz)
gh workflow run build-ios.yml --ref codex/engine-split-v2 -f release=false
```

**Doğrulama:** koşumun `runner_id` alanı **0'dan farklı** olmalı ve `steps` dolu olmalı.
Boşsa kesinti sürüyor.
