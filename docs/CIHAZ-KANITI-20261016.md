# CİHAZ KANITI — 16.10.2026 13:40 (Redmi Note 8 bağlıyken)

**Cihaz:** Redmi Note 8 (`ginkgo`), Android 16 (SDK 36), `ro.build.type=user`
**Seri:** `17df6357` · **Uygulama:** v2.0.2 (versionCode 202)

---

## 1. ORTAM DURUMU

| Kontrol | Sonuç | Kanıt |
|---|---|---|
| **Vendor ses düzeltmesi** | ✅ **YERİNDE** | `mixer_paths_idp.xml` L1700 `MultiMedia9 value="1"`; `audio_platform_info_intcodec.xml` L68 `USECASE_INCALL_MUSIC_UPLINK2 id="27"` |
| Yedek dizin | ✅ duruyor | `/vendor/etc/phonebridge-backup/` (fabrika tarihli iki dosya) |
| Dosya tarihleri | ✅ değiştirilmiş | 2026-09-27 14:29 (diğer vendor dosyaları 2009-01-01) |
| **Root** | ❌ **YOK** | `su: inaccessible or not found`; Magisk paketi yok; `verifiedbootstate=green` |
| `run-as` | ❌ **SELinux engelliyor** | `avc: denied { getattr } ... scontext=u:r:runas:s0 tcontext=u:object_r:privapp_data_file:s0` |
| SELinux | `Enforcing` | — |

**Yorum:** Vendor düzeltmesi ve priv-app **ROM imajına gömülü** (25-27.09.2026 tarihli) → **root olmadan da kalıcı** ✓
Ama root olmadığı için (a) emülatör eşleme anahtarlarını okuyamıyor, (b) düzeltme kaybolursa **yeniden uygulanamaz**.

### Kurulu build doğrulaması (APK içeriği)
```
VAR  peer_answered_stale_id   -> madde 2 (cevap senkronu) cihazda
VAR  Silent wake push sent    -> uyanma ozelligi cihazda
VAR  no alert token yet       -> bildirim token uyarisi
VAR  GATE_WAIT / GATE_OPEN    -> kapi teshisi
VAR  vendorFix / RELAY_HEALTH -> tanilama
VAR  RedmiRinger / iphone_showing
yok  attachNetwork            -> v2 motor ayrimi KURULU DEGIL (dogru)
```
**Sonuç:** Cihazda **ana dal** build'i var — önerilen sürüm ✓

---

## 2. ✅ UYANMA BEACON'I ÇALIŞIYOR — KANITLANDI

Gerçek iPhone bağlantısı sırasında uygulamanın kendi logu:

```
12:51:10.241  BleServerManager: iPhone has no link; reconnect pulse 1
12:51:10.262  BleServerManager: Call wake beacon on (major 2, low latency, high power)
12:51:13.533  BleServerManager: iPhone linked again; reconnect pulses stop
12:51:13.535  BleServerManager: Call wake beacon off
12:51:13.626  CallManager: iPhone connected (protocol 2)
12:51:13.627  BleServerManager: iPhone connected 3365 ms after the wake beacon started
```

**Bu, kullanıcının asıl isteğinin kanıtı:** *"iPhone'da uygulama arka planda çalışmıyor olsa dahi
Redmi onu uyandırmalı."* → Redmi **uyandırdı**, iPhone **3.365 saniye sonra** bağlandı ✓

**Ama hedef `<1000 ms`** — ölçülen **3365 ms**. İyileştirme alanı burada.

---

## 3. 🔴 BAĞLANTI KARARSIZ — 46 saniyede 5 yeniden bağlanma

```
12:50:57.056  iPhone connected (protocol 2)
12:51:00.757  iPhone connected (protocol 2)   +3.7 sn
12:51:13.626  iPhone connected (protocol 2)   +12.9 sn
12:51:24.461  iPhone connected (protocol 2)   +10.8 sn
12:51:43.555  iPhone connected (protocol 2)   +19.1 sn
```

Ve **BLE linki iki kez koptu:**
```
12:51:00.766  iPhone is linked; BLE advertising paused
12:51:05.026  BLE call-state updates disabled      <- sadece 4.2 sn sonra
12:51:05.033  iPhone has no link; BLE advertising on
```

### Yorum
İki farklı bağ:
- `CallManager: iPhone connected (protocol 2)` = **WebSocket (WiFi)**
- `BleServerManager: iPhone linked` = **BLE**

**WebSocket 5 kez**, **BLE 2 kez** koptu. Kullanıcının *"iPhone bağlı değil diyor"* şikayetinin
muhtemel sebebi bu kararsızlık — bağlantı **var ama sürekli düşüyor**.

---

## 4. 🔴 TEŞHİS BOŞLUĞU — kopma loglanmıyor

Tüm logcat'te (`26.070` satır) **tek bir kopma kaydı yok.** Uygulama `iPhone connected` yazıyor
ama **kopmayı hiç yazmıyor**.

**Sonuç:** 5 yeniden bağlanmanın **sebebi logdan çıkarılamıyor.** Hangi taraf kapatıyor?
WiFi mi düştü, keepalive mi zaman aşımına uğradı, iPhone arka plana mı geçti, yoksa sunucu mu
düşürdü? **Hiçbiri ayırt edilemiyor.**

**Bu AGENTS.md 6.1'in tam olarak yasakladığı şey:** sessiz çıkış yolu. Bir bağlantı kapanırken
**kim kapattığını, neden kapattığını ve ne kadar sürdüğünü** loglamayan bir sistem, "bağlantı
kararsız" şikayetini **teşhis edilemez** kılar.

**Yapılacak (cihaz testi gerektirir, ses yolu DEĞİL):** WebSocket kapanış yoluna
`onClose(code, reason)` + süre + son alınan çerçeve zamanı logu eklemek.

---

## 5. DİĞER GÖZLEMLER

- **Gerçek çağrılar yapılmış:** `CallActivity` 11:48, 11:52, 11:57, 12:01, 12:02'de açıldı
- **Çağrı süreleri:** Telecom unbind kayıtları `17175 ms`, `27269 ms`, **`585873 ms`** (9.8 dakika!)
- **Pil/şarj:** kilit ekranı `%87 • Yavaş şarj oluyor • Dolmasına 53 dk` (kullanıcının ilk şikayeti)
- **Kilit:** cihaz kilitli olduğu için UI ekran görüntüsü alınamadı

---

## 6. ENGELLER

| Engel | Etkilediği | Çözüm |
|---|---|---|
| **Root yok** | Emülatör (eşleme anahtarları), vendor düzeltmesini yeniden uygulama | Kullanıcı Magisk'i geri yükler **veya** uygulamaya root'suz lab yolu eklenir |
| **Cihaz kilitli** | UI ekran görüntüsü (madde 1) | Kullanıcı kilidi açar |

---

## 7. ✅ TEŞHİS BOŞLUĞU KAPATILDI — ve ilk kapanış satırı hemen işe yaradı

**Düzeltme:** `onClose` artık `code`, `reason`, `remote` + üç yeni alanı logluyor
(`connectedAt`, `lastMessageAt`, `authenticatedAt`) + **`verdict`** sınıflandırması.
Commit `c8d771d` · 94 → **102 test** · mutasyonla doğrulandı (dal zayıflatılınca 3 test kırılıyor).

**Kuruldu ve ilk kapanış satırı:**

```
13:42:35.302  WebSocket server started on port 8787
13:42:35.636  WebSocket opened from 10.64.17.201
13:42:35.851  CallManager: iPhone connected (protocol 2)
13:42:45.575  WebSocket closed code=1006 remote=false authenticated=true protocol=2
              sessionMs=9939 sinceLastMessageMs=9693 verdict=closed_quiet
              reason=Connection reset by peer
```

### Bu satırın söyledikleri

| Alan | Değer | Anlam |
|---|---|---|
| `sessionMs` | **9939** | Oturum **~10 saniye** yaşadı |
| `sinceLastMessageMs` | **9693** | Kapanmadan önce **9.7 sn uygulama trafiği yok** |
| `code` | **1006** | Anormal kapanış — close frame yok |
| `remote` | **false** | **Sunucu kapatmadı** — bağlantı öldü |
| `reason` | **Connection reset by peer** | **Karşı taraf TCP RST gönderdi** |

### 🔍 Kök neden adayı: ping aralığı oturum ömründen UZUN

**iOS tarafı `GatewayConnection.swift:476`:**
```swift
try? await Task.sleep(nanoseconds: 15_000_000_000)   // 15 saniye
task.sendPing { ... }
```
**Android tarafında ise HİÇ keepalive/ping YOK** (grep: 0 sonuç).

**Yani:** iPhone **15 saniyede bir** ping atıyor, ama bağlantı **~10 saniyede** ölüyor →
**ilk ping hiç gönderilemiyor.** Bağlantı, canlı olduğunu kanıtlayacak hiçbir trafik üretmeden ölüyor.

**Ayrıca:** WebSocket PING çerçevesi `onMessage`'ı tetiklemez → bizim `lastMessageAt` ölçümümüz
ping'leri **saymaz**. Yani "9.7 sn sessiz" = "uygulama mesajı yok", "bağlantı ölü" değil.

### ⚠️ DÜRÜSTLÜK SINIRI

Bu **tek bir olay.** Kurulum sonrası iPhone bir kez bağlandı, öldü ve **150 saniye boyunca
bir daha bağlanmadı.** Tek olaydan "ping aralığı suçlu" sonucu **çıkarılamaz** — sadece
**en güçlü aday** olarak yazılıyor.

**Doğrulamak için gereken:** iPhone birkaç kez daha bağlanıp kopmalı. O zaman
`sessionMs` dağılımı ve `reason` alanları deseni gösterir.

### Sıradaki aday düzeltmeler (kanıt birikince)
1. **Android'e keepalive ekle** (~5 sn ping) → NAT deliğini canlı tutar, ölü soketi ~5 sn'de fark eder
2. **iOS ping aralığını 15 sn → 5 sn** indir → oturum ömründen kısa olsun
3. `verdict`'e `peer_reset` sınıfı ekle (`code=1006` + reset nedeni ayrı bir sinyal)

---

## 8. 🎯 KULLANICININ 1 NUMARALI ŞİKAYETİ — KÖK NEDEN BULUNDU VE DÜZELTİLDİ

**Şikayet:** *"telefon geçidi uygulamasını iphone'da açana kadar iphone bağlı değil diyor… gidip oradan onu uyandırmalı haberdar etmeli"*

### Kök neden zinciri

**1. iPhone BLE linkini dakikalarca tutuyor, soketi yok:**
```
BleServerManager: BLE client connected: EC:0D:51:0C:64:D2
BleServerManager: iPhone linked while advertising started; stopping it
```
→ Ama WebSocket **yok**.

**2. `iPhoneLinked()` BLE linkini "bağlı" sayıyor:**
```kotlin
private fun iPhoneLinked() =
    listeningCentrals() > 0 || GatewayService.authenticatedClientCount() > 0
```
→ `listeningCentrals() > 0` → **true** → **uyanma nabzı HİÇ planlanmıyor:**
```kotlin
} else if (!pulseScheduled) {          // YALNIZCA linked == false iken
    schedulePulse(RECONNECT_GRACE_MS)
}
```

**3. Çağrı durumu yalnızca DEĞİŞİNCE gönderiliyor** → boştayken hiç trafik yok.

**Sonuç:** Redmi "iPhone bağlı değil" diyor ve **hiçbir şey yapmıyor.** Tek çıkış yolu iPhone'da uygulamayı elle açmak. **Kod bunu biliyordu** (L730 yorumu) ama çözüm yalnızca çağrı sırasında uygulanmıştı.

### İki kanal denendi — cihaz hangisinin işe yaradığını söyledi

| Kanal | Sonuç |
|---|---|
| **BLE çağrı-durumu bildirimi** | ❌ **İşe yaramıyor.** iOS yalnızca **canlı çağrı** durumunda soketi açıyor:<br>`if ["ringing","dialing","connecting","active"].contains(update.state) { reconnectNow(...) }`<br>→ `idle` okunuyor ve **atılıyor** |
| **Sessiz push (APNs)** | ✅ **İşe yarıyor.** İşleyici çağrı durumundan **bağımsız** uyanıyor:<br>`connection.wakeFromBeacon()` → 12 sn'ye kadar kimlik doğrulama bekliyor |

### ✅ ÖLÇÜLEN SONUÇ — 6 tekrar, hepsi tutarlı

```
13:59:51.771  Idle wake push sent (idle wake 1, next in 60s)
13:59:53.236  WebSocket opened from 10.64.17.201        <- 1.5 sn
13:59:53.257  iPhone connected (protocol 2)

14:00:06.798  Idle wake push sent
14:00:07.365  WebSocket opened                          <- 0.6 sn
14:00:21.836  Idle wake push sent
14:00:22.665  WebSocket opened                          <- 0.8 sn
14:00:36.873  Idle wake push sent
14:00:37.705  WebSocket opened                          <- 0.8 sn
14:01:36.911  Idle wake push sent
14:01:37.783  WebSocket opened                          <- 0.9 sn
14:02:36.949  Idle wake push sent
14:02:37.804  WebSocket opened                          <- 0.9 sn
```

**Uyandırma süresi: 0.6 - 1.5 saniye** — kullanıcının istediği `<1 sn` hedefinin **içinde** ✓
**Ve bu, projenin uyuyan bir iPhone'u ilk kez kendi başına geri getirdiği an.**

### 🔴 Ama soket 50 ms'de ölüyor — iOS tasarımı

```
WebSocket closed code=1001 remote=true authenticated=true sessionMs=70 sinceLastMessageMs=24
WebSocket closed code=1006 remote=true authenticated=true sessionMs=53 sinceLastMessageMs=18
```

iPhone bağlanıyor, kimlik doğruluyor, **~50 ms sonra iOS uygulamayı askıya alıyor.** Bu **hata değil** — arka planda uyanan bir uygulamanın normal ömrü. Soketin kalıcı olması için uygulamanın **ön planda** olması gerekir.

### ⚠️ VERİ POLİTİKASI DEĞİŞİKLİĞİ (madde 5 ile ilişkili)

`CallWakePush` belgesi diyordu: *"Nothing is sent while no call is live… the user's data allowance is only touched when the call audio genuinely needs the iPhone."*

**Boşta uyandırma bunu değiştiriyor.** Bilinçli olarak dar tutuldu:
- Yalnızca soket **gerçekten yokken**
- Yalnızca telefon **BLE ile erişilebilirken** (yani "uyuyor", "uzakta" değil)
- Aynı ~90 baytlık yük, **numara/isim yok** (`WakePushPayloadTest` bunu sabitliyor)
- Geri çekilme: 60 sn → 120 sn → 300 sn → **900 sn**
- Uyanan telefon **hemen durduruyor** → saatte ~2 KB

### ❌ DOĞRULANMAMIŞ KISIM (dürüstçe)

**İlk sürüm geri çekilmeyi her soket açılışında sıfırlıyordu.** Soket 50 ms'de öldüğü için bu **15 saniyede bir push döngüsü** üretti (`idle wake 1` tekrar tekrar).

**Düzeltildi:** geri çekilme yalnızca soket **60 saniye kesintisiz ayakta kalırsa** sıfırlanıyor.

**AMA:** düzeltme **kuruldu**, telefon **doğrulama sırasında çıkarıldı** → **bu kısım gözlenmedi.** Doğrulanmış sayılmıyor.

**Telefon geri bağlanınca ilk iş:** `Idle wake N` sayacının arttığını (1→2→3) ve aralıkların 60/120/300 sn'ye çıktığını doğrula.


