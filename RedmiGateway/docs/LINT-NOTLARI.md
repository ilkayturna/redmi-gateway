# LINT NOTLARI — baseline'da ne var, neden kabul edildi

**Tarih:** 16.10.2026 · **lint 8.5.2** (AGP 8.5.2) · Baseline: `android/app/lint-baseline.xml`

Android lint bu projede **hiç koşmamıştı**. İlk koşum **56 hata, 143 uyarı** buldu.
Bunların **2'si gerçek çökme**ydi ve düzeltildi; kalan **193 kayıt** baseline'a alındı ki
CI **yeni** sorunlarda kırılsın, birikmiş listede değil.

> ⚠️ **Baseline bir çöp kutusu değildir.** Aşağıda her kategorinin **neden** kabul edildiği yazılı.
> Yeni bir kayıt eklenirse **önce buraya gerekçesi yazılır.**

---

## DÜZELTİLEN GERÇEK ÇÖKMEler (baseline'da YOK)

| # | Yer | Sorun | Etki |
|---|---|---|---|
| 1 | `CallActivity.kt` | `setShowWhenLocked`/`setTurnScreenOn` **API 27**, `minSdk 26` | Android 8.0'da **gelen çağrı ekranı çökerdi** — ekranın en çok gerektiği anda |
| 2 | `MainActivity.requestDialerRole` | `RoleManager` **sınıfı API 29**, koruma yok | Android 8-9'da butona basınca `NoClassDefFoundError`. **`PhoneActivity`'de aynı fonksiyonun koruması vardı**, fark buydu |

Düzeltme sonrası: **56 → 50 hata**, `NewApi` **19 → 13**, `MainActivity` **4 → 0**.

---

## BASELINE KATEGORİLERİ

### `MissingPermission` (29) — çoğu YANLIŞ POZİTİF
`BleServerManager` Bluetooth API'lerini bir yardımcı arkasından çağırıyor:
```kotlin
if (!hasConnectPermission()) { ...; return }   // hasConnectPermission: checkSelfPermission
```
**Lint yardımcı fonksiyonun içini göremez** → korumalı çağrıları da işaretler. 29 kaydın çoğu bu.

**GERÇEK olan kısım (belgelendi, düzeltilmedi):** `if (!hasConnectPermission())` **dalının
içindeki** `sendResponse` çağrısı **kendisi de `BLUETOOTH_CONNECT` istiyor** → izin yoksa
**tam da kurtarma yolu çöker.** Doğrusu: o dalda `sendResponse`'u `runCatching` içine almak.

**Neden şimdi düzeltilmedi:** `BleServerManager` **uyanma beacon'ını** taşıyor ve AGENTS.md 6.1
onu cihaz testine bağlıyor. Cihazsız değiştirilmez.

### `NewApi` (13) — hepsi kullanıcının cihazında MEVCUT
| API | Nerede | Not |
|---|---|---|
| 27 | `windowLightNavigationBar` (4, XML) | API 26'da **yok sayılır**, çökmez |
| 31 | `CallActivity.kt:267` `Call.Details#getState` | `runCatching` ile korunmuş ✓ |
| 31 | `CallAudioLab.kt:75,130,132` | **Lab modu** (`LabMode` kapısı arkasında) |
| 31/34 | `CallAudioLab` `OutcomeReceiver`/`CallEndpoint` | Lab modu |

**Hepsi Android 16'da mevcut** → kullanıcının cihazında **hiçbiri tetiklenmez.**
Kalanlar `minSdk 26` beyanının teorik borcu; `minSdk` yükseltilirse kendiliğinden kapanır.

### `StaticFieldLeak` (3) — TASARIM GEREĞİ
`BleServerManager.current`, `GatewayWebSocketServer.current`, `SmsReceiver.webSocketServer`.
Bunlar **bilinçli singleton'lar**; uygulama tek sunucu çalıştırır ve süreç ömrü boyunca yaşar.
Sızıntı değil, **tek örnek**.

### `Wakelock` (1) — YANLIŞ POZİTİF, kod DOĞRU
`CallActivity.kt:171` `proximityWakeLock?.release()`. Lint `?.` güvenli-çağrısı ile önceki
`isHeld` kontrolünü ilişkilendiremiyor. **Gerçekte beş `release()` çağrısının hepsi korumalı:**
```
L161  if (isHeld == true) release()
L170  if (isHeld == true) release()
L209  if (isHeld == true) release()
L240  if (isHeld == true) release()
L205  if (isHeld == false) acquire()
```
Korunmasaydı `release()` **`RuntimeException`** fırlatırdı ("under-locked").

### `PrivateApi` (3) — `HotspotRestarter`, bilinçli
`android.net.TetheringManager$StartTetheringCallback` / `TetheringRequest` üzerine **yansıma**.
Bu API'ler gizli; ama uygulama **ayrıcalıklı sistem uygulaması** (`/system/priv-app`) ve
hotspot **çalışıyor** (doğrulandı). Alternatifi yok.

### `ObsoleteSdkInt` (4) — ölü ama zararsız
`if (SDK_INT >= O)` / `>= LOLLIPOP` — `minSdk 26` iken **her zaman doğru**. Temizlik değeri var,
davranış değeri yok.

### `HardcodedText` (49) / `SetTextI18n` (35) — programatik UI
Ekranlar Kotlin'de programatik kuruluyor; metinler kaynak dosyasında değil kodda. Çeviri
hedeflenirse taşınır, şimdi değil.

### `UnusedResources` (26) — artıklar
Kullanılmayan renk/drawable. Silinebilir; davranışı etkilemez.

---

## KURAL

1. **Yeni lint hatası = CI kırmızı.** Baseline yalnızca **mevcut** listeyi taşır.
2. Baseline'a kayıt eklemek gerekiyorsa **önce bu dosyaya gerekçe yazılır.**
3. Kullanıcının cihazı **Android 16 (API 36)** → `NewApi` kayıtları onun için **risk değil**;
   ama `minSdk 26` beyanı durdukça **teorik borç** olarak kalır.
4. `MissingPermission` altındaki **guard-dalı** sorunu gerçek; cihaz testi geldiğinde
   `BleServerManager` ile birlikte ele alınmalı.

---

## KOTLIN DERLEYICI UYARILARI (16.10.2026 temiz derleme: 32)

Lint'ten ayrı bir katman. Temiz derlemede **32 uyarı**; hepsi incelendi:

| Uyarı | Adet | Karar |
|---|---|---|
| **`Condition is always 'true'`** — `BleServerManager.kt:378` | 1 | ✅ **DÜZELTİLDİ** (aşağıda) |
| `'val state: Int' is deprecated` — `CallManager.kt` | 26 | Bilinçli — aşağıda |
| `characteristic.value` / `notifyCharacteristicChanged` deprecated | 2 | Bilinçli — API 33 öncesini desteklemek için |
| `FLAG_SHOW_WHEN_LOCKED` / `FLAG_TURN_SCREEN_ON` deprecated — `CallActivity.kt` | 2 | **Bilinçli** — API 26 yedeği (tur 130'daki çökme düzeltmesi) |
| `Bundle.get` deprecated — `CallManager.kt:814` | 1 | Bilinçli |

### ✅ Düzeltilen: ölü null kontrolü
```kotlin
var responseBytes: ByteArray? = null
responseBytes = handleCallControlWrite(device, combined)
if (responseBytes != null) { notifyCharacteristicToSubscribers(...) }   // her zaman true
```
`handleCallControlWrite` **her yolda** `controlReply(...)` döndürüyor (reddedilen komutlar dahil) →
kontrol **ölü kod**, `responseBytes` başka yerde de okunmuyor. Sadeleştirildi.
**Davranış değişmedi** (koşul zaten hep doğruydu), uyarı kalktı, niyet netleşti.

### Bilinçli: `Call.state` deprecation (26 kullanım)
`Call.state` API 31'de `Call.Details.state` lehine **deprecated** edildi, **kaldırılmadı**.
Alternatif (`details?.state`) **API 31+** ister ve sürüm kontrolü ya da `try/catch` gerektirir.
`minSdk 26` ile 26 çağrı noktasını sarmalamak **çağrı durum makinesini** yeniden yazmak olur;
bu makine **madde 2'nin (cevaplama senkronu)** kalbi ve AGENTS.md 6.1 ruhu gereği **cihaz
testi olmadan dokunulmaz.**

**Sonuç:** 26 uyarı **kabul edilmiş teknik borç**; `minSdk` yükseltilirse kendiliğinden kapanır.
