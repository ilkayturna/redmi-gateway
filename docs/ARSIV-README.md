# RedmiGateway — Yerel Tam Arşiv

Bu klasör, Redmi Note 8 ↔ iPhone çağrı geçidi (gateway) projesinin **tam yerel yedeğidir**.
Oluşturulma: 11.10.2026

---

## 🚨 ÖNEMLİ: `repo-source\` BAYAT — KULLANMA

| | |
|---|---|
| **Durum** | **11.10.2026 tarihli DONDURULMUŞ kopya.** Güncellenmiyor. |
| **Canlı kaynak** | `C:\Users\ilkay.turna\source\repos\redmi-gateway` ← **her zaman buradan çalış** |
| **Neden tehlikeli** | Aşağıdaki düzeltmeler orada **YOK** |

`repo-source\` içeriği canlı repodan **ölçülmüş farklar** taşıyor (16.10.2026 doğrulaması):

| Dosya | Fark | Sonucu |
|---|---|---|
| `ios\RedmiGateway\Info.plist` | **`remote-notification` YOK** | Sessiz push uygulamayı **uyandırmaz** → uyanma özelliği **sessizce ölü** |
| `android\...\push\ApnsSender.kt` | Eski | Sessiz push gönderimi yok |
| `android\...\audio\CallAudioRelay.kt` | Eski | `GATE_WAIT`/`GATE_OPEN` tanılaması yok |
| `android\...\push\CallWakePush.kt` | **Hiç yok** | Uyanma tetikleyicisi yok |
| `scripts\device\vendor_audio_fix.py` | Eski | `IndexError` düzeltmesi yok |

**KURAL:** `repo-source\` yalnızca **tarihsel kayıttır**. Oradan **derleme, kurulum, kopyalama
veya çalıştırma yapılmaz.** Bir dosyaya ihtiyacın varsa canlı repodan al.

---

## Klasör Yapısı

| Yol | İçerik | Kullanılır mı |
|---|---|---|
| `repo-source\` | 11.10.2026 tarihli **dondurulmuş** çalışma ağacı kopyası | ❌ **HAYIR** (yukarıya bak) |
| `git-history\redmi-gateway-full-history.bundle` | **Tüm git geçmişi** — tek dosya. `git clone <bundle> <dizin>` | ✅ Kurtarma için |
| `artifacts\android\` | Derlenmiş APK'lar (güncel + eski sürümler) | ✅ |
| `logs\` | Cihazdan ADB ile çekilen loglar (pil, termal, ses, çağrı) | ✅ |
| `DIAGNOSTIK-ARACLAR.md` | Bu arşivdeki **doğrulanmış** betikler ve ne yaptıkları | ✅ |
| `DONUNCE-YAPILACAK.md` | Cihaz dönünce yapılacaklar (tek sayfa) | ✅ |
| `DOGRULAMA-MATRISI.md` | Hedefin 5 maddesi için kanıt durumu | ✅ |

## Git Deposu

- **Asıl repo:** https://github.com/ilkayturna/redmi-gateway (private) — branch: `codex/audio-jitter-fix`
- **Arşiv repo:** https://github.com/ilkayturna/redmi-gateway-archive (private)
- **Yerel canlı kopya:** `C:\Users\ilkay.turna\source\repos\redmi-gateway` ← **buradan çalış**
- **Deney dalı:** `codex/engine-split-v2` (TestFlight'a gitmez, cihaz testi bekler)

## Sürüm Bilgileri

- **Android:** `com.ilkay.redmigateway` — versionCode **202** / versionName **2.0.2** (compileSdk/targetSdk 34, minSdk 26)
- **iOS:** Xcode projesi `RedmiGateway.xcodeproj` — MARKETING_VERSION **2.0**, CI: `.github/workflows/build-ios.yml`
  - **TestFlight'ta kurulacak:** sürüm **2.0** → build **96** (sessiz push düzeltmesi + çalışan ses)
  - ⚠️ **build 93'ü KURMA** — sesi kıran sürümdü, geri alındı
- **Cihaz:** Xiaomi Redmi Note 8 (`ginkgo`, M1908C3JG), ADB seri no: `17df6357`

## Geri Yükleme

```powershell
# Canlı repo zaten burada:
cd C:\Users\ilkay.turna\source\repos\redmi-gateway

# Git geçmişini bundle'dan geri yükle (felaket kurtarma)
git clone "C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\git-history\redmi-gateway-full-history.bundle" redmi-gateway-restored
```

## Not

`adb.exe` bu makinede `C:\Users\ilkay.turna\platform-tools\adb.exe` yolunda (PATH'te değil).
