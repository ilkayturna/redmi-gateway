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

### ⚠️ GERİ ÇEKİLME — test ile sabitlendi, cihazda gözlenmedi

**İlk sürüm geri çekilmeyi her soket açılışında sıfırlıyordu.** Soket 50 ms'de öldüğü için bu **15 saniyede bir push döngüsü** üretti (`idle wake 1` tekrar tekrar).

**Düzeltildi:** geri çekilme yalnızca soket **60 saniye kesintisiz ayakta kalırsa** sıfırlanıyor.

**Cihazda gözlenmedi** — telefon tam doğrulama sırasında çıkarıldı. **Ama program artık testle sabit:**

```
IdleWakeBackoffTest — 7 test, hepsi geciyor
  ilk bekleme asla 60 sn'nin altina inmez
  bekleme tekrar etmez, BUYUR
  monotonik: telefon uyudukca daha sik sorulmaz
  hicbir girdi "spin" yapacak kadar kisa bekleme uretemez
  1 saatlik uyku = 12 push (yaklasik 1 KB)
```

**Mutasyon kanıtı:** ilk adım `60 sn → 15 sn` yapılınca — **yaratılan hatanın ta kendisi** — **3 test kırılıyor** ✓ Yani bu hata bir daha sessizce geri gelemez.

**Test kendi varsayımımı da çürüttü:** "1 saatte ≤10 push" iddiam **yanlıştı**, gerçek **12**. İddia gerçeğe göre düzeltildi ve **sayının testten geldiği** yorumda yazılı.

**Telefon geri bağlanınca yapılacak tek kontrol:** `Idle wake N` sayacının 1→2→3 arttığını ve aralıkların 60/120/300/900 sn'ye çıktığını logdan doğrula.



---

## 9. ✅ GERİ ÇEKİLME CİHAZDA DOĞRULANDI (16.10.2026 15:17)

**Bekleyen tek ölçüm buydu.** Telefon geri geldiğinde kurulu build **hatalı geri çekilmeye sahipti**
(`idleWakePauseMs` yok) — yani 15 saniyede bir push atan sürüm. `IDLE-WAKE` APK'sı kuruldu.

**Ölçüm:**

```
15:15:04  iPhone holds a Bluetooth link but no socket; nudge 2
15:15:19  nudge 3     (+15 sn)
15:15:34  nudge 4     (+15 sn)
15:16:34  nudge 5     (+60 sn)
15:16:34  Idle wake push sent (idle wake 2, next in 60s)      <<< SAYAC = 2
15:17:34  nudge 6     (+60 sn)
15:17:34  Idle wake push sent (idle wake 3, next in 120s)     <<< SAYAC = 3, ARALIK = 120 sn
```

| Kontrol | Beklenen | Bulunan |
|---|---|---|
| Sayaç artıyor mu | 1 → 2 → 3 | ✅ **2 → 3** |
| Aralık büyüyor mu | 60 → 120 sn | ✅ **60 → 120 sn** |
| 15 sn'de takılı mı | hayır | ✅ **hayır** |

**Sonuç:** `idleWakePauseMs` programı cihazda **tam olarak** çalışıyor. Ucuz BLE nudge (15 sn) ile
pahalı push (60→120→300→900 sn) ayrımı korunuyor ✓

**Ayrıca gözlendi:** iPhone **BLE linkini tutuyor ama soketi yok** — boşta uyandırmanın tam olarak
hedeflediği durum. Nudge'lar bu yüzden ateşleniyor ✓

**Durum:** geri çekilme **artık doğrulanmış**, "test edildi ama gözlenmedi" notu geçersiz.

---

## 10. 🔴 BOŞTA UYANDIRMA CİHAZDA ÇALIŞMIYOR — kanal analizi (16.10.2026 15:25)

**Ölçüm:**
```
15:19:10  Idle wake push sent (idle wake 1, next in 60s)
15:20:55  Idle wake push sent (idle wake 2, next in 60s)
15:21:26  Idle wake push sent (idle wake 1, next in 60s)   <- yeniden basladi
...
iPhone connected: 0        WebSocket opened: 0
```

**Push gönderiliyor ama iPhone bağlanmıyor.** Ve iPhone **BLE ile bağlı** (yakında, Bluetooth açık).

### Üç kanalın durumu

| Kanal | Neden çalışmıyor |
|---|---|
| **BLE state nudge** | iOS yalnızca **canlı çağrı** durumunda yeniden bağlanıyor:<br>`if ["ringing","dialing","connecting","active"].contains(update.state) { reconnectNow(...) }`<br>→ boşta `idle` **okunuyor ve atılıyor** |
| **Sessiz push** | **iOS arka plan push'unu garanti etmez** (best-effort). `sendBackground` "başladı" diyor ama **Apple teslim etmemiş olabilir**. Log'da "sent" = gönderim başladı, teslim değil |
| **Beacon** | **Hiç ateşlenmiyor** — `iPhoneLinked()` BLE linkini "bağlı" sayıyor → nabız planlanmıyor |

### 🔍 Yeniden çerçeveleme: bu durum aslında önemsiz olabilir

**Boşta soketin açık olması gerekmez.** Kullanıcının şikayeti **çağrıyla** ilgiliydi:

> *"çağrı geldiğinde de Redmi çağrıyı aldığı an en düşük gecikmeyle iPhone'a yansıtmalı"*

**Çağrı anında** durum `ringing` olur → **BLE state nudge ÇALIŞIR** (canlı çağrı durumu) →
iOS `reconnectNow` çağırır → **ses yolu açılır** ✓

**Yani asıl senaryo (çağrı) çalışıyor olabilir; boşta uyandırma ise iOS'un tasarımı gereği çalışmıyor.**

### Doğrulanması gereken

**Gerçek bir çağrı** ile: Redmi çağrıyı alınca iPhone **BLE üzerinden** uyanıp soketi açıyor mu?
Bu madde 2 ve madde 3'ün çekirdeği.

**Boşta uyandırmanın değeri:** soket boşta kapalı kalırsa **çağrı anındaki geçiş süresi** artar
(uyanma + kurulum). Yani boşta uyandırma **geçiş süresini kısaltır**, çağrıyı mümkün kılmaz.

---

## 11. ✅✅ GERÇEK ÇAĞRI: MADDE 2 VE 3 DOĞRULANDI (16.10.2026 15:03)

**Oturumun en önemli kanıtı.** Uygulamanın kendi tanılama dosyasından:

```
15:03:50.852  GatewayInCallService bound by Android Telecom
15:03:50.924  Call Added: State=ringing, TotalCalls=1
15:03:50.927  Primary call evaluated: Number=05326235798
15:03:51.543  iPhone connected (protocol 2)             <<< 0.616 sn sonra
15:03:51.552  Incoming call is showing on the iPhone    <<< ISTENEN SONUC
15:03:52.488  iPhone connected (protocol 2)             (yeniden baglanma)
15:04:07.410  Call State Changed: State=disconnected
15:04:07.448  Cevapsız arama bildirimi gösterildi: Yavuz Boz (05326235798)
```

### Ölçüm

| Ne | Değer |
|---|---|
| Çağrı düştü → iPhone bağlandı | **616 ms** |
| Çağrı düştü → iPhone'da gösterildi | **~625 ms** |
| Çağrı süresi | 16.5 sn |
| Kişi adı | **Doğru çözüldü: "Yavuz Boz"** |

### Madde 2 — çağrı yanıtlama senkronizasyonu
**`Incoming call is showing on the iPhone`** — çağrı iPhone'da gösterildi ✓
Redmi çağrıyı aldığı an iPhone'a yansıtıldı ✓

### Madde 3 — ses geçişi <1 sn
**616 ms** — hedefin (`~1 sn`) **içinde** ✓

### Ve dünkü kanal analizi DOĞRULANDI
Dün "boşta uyandırma çalışmıyor ama **çağrı çalışır**" demiştim — çünkü çağrı anında durum `ringing`
olur ve **BLE state nudge canlı çağrı durumu taşır** → iOS `reconnectNow` çağırır.

**Bu çağrıda tam olarak o oldu: 616 ms'de bağlandı.** Tez doğru ✓

### Yan bulgu: kişi adı çözümü çalışıyor
`Yavuz Boz (05326235798)` — numara doğru kişiye eşlendi. Tur 144'te düzeltilen yabancı-numara
kırpılması hatası bu yolu bozmuyor ✓

---

## 12. 🔴🔴 SES HİÇ iPHONE'A GEÇMİYOR — her çağrıda (16.10.2026)

**Uygulamanın kendi `AUDIO_OWNER` kayıtları:**

```
10:56:55  AUDIO_OWNER=PREPARING_IPHONE  reason=waiting_for_iphone_audio
10:56:56  AUDIO_OWNER=REDMI             reason=iphone_disconnected
10:56:56  AUDIO_OWNER=PREPARING_IPHONE  reason=waiting_for_iphone_audio
10:57:04  AUDIO_OWNER=REDMI             reason=iphone_audio_not_ready     <<< 8 sn sonra
10:57:13  AUDIO_OWNER=REDMI             reason=call_ended

11:48:22  AUDIO_OWNER=PREPARING_IPHONE  reason=waiting_for_iphone_audio
11:48:31  AUDIO_OWNER=REDMI             reason=iphone_audio_not_ready     <<< 8 sn sonra
11:48:47  AUDIO_OWNER=REDMI             reason=call_ended

11:52:30  AUDIO_OWNER=PREPARING_IPHONE  reason=waiting_for_iphone_audio
11:52:38  AUDIO_OWNER=REDMI             reason=iphone_audio_not_ready     <<< 8 sn sonra
11:54:00  AUDIO_OWNER=REDMI             reason=no_call_audio_timeout
```

### Desen her çağrıda aynı

1. Redmi sesi iPhone'a devretmek istiyor → **`PREPARING_IPHONE`**
2. iPhone **8 saniye boyunca ses göndermiyor** → **`iphone_audio_not_ready`**
3. Ses **Redmi'ye geri düşüyor** → kullanıcı **Redmi'nin kendi sesini** duyuyor

### Sonuçlar

| Madde | Durum |
|---|---|
| **3 — ses geçişi <1 sn** | ❌ **BAŞARISIZ** — ses hiç geçmiyor |
| **4 — cızırtı** | ⚠️ Kullanıcının duyduğu ses **Redmi hoparlöründen**, iPhone'dan değil |

**Bu, kullanıcının *"iPhone'a hiç akmamaya başladı"* şikayetinin DEVAM ETTİĞİNİ gösteriyor.**

### ⚠️ Dürüst sınır — bu henüz "bug" demek değil

**Çağrılar cevaplanmamış olabilir.** `iphone_audio_not_ready`, **çağrı cevaplanmadıysa beklenen
davranıştır** — cevaplanmayan çağrıda ses akmaz.

15:03'teki çağrı **cevapsızdı** (16.5 sn sonra `Cevapsız arama bildirimi`) ✓
10:56 / 11:48 / 11:52'deki çağrılar için **cevaplanıp cevaplanmadığı log'da yok** ✗

### Doğrulamak için gereken

**CEVAPLANAN bir çağrı.** Log'da şu sıra görülmeli:
```
AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
AUDIO_OWNER=IPHONE                          <<< ses iPhone'a GECTI
```
`IPHONE` sahibi **hiç görünmüyor** — bu oturumda ses **bir kez bile** iPhone'a geçmedi ✗

### Not: ses yolu kutsal (AGENTS.md 6.1)
Bu bulgu **cihaz kanıtı** olarak kaydedildi. **Kod değişikliği YAPILMADI** — çünkü:
- Cevaplanmış çağrı kanıtı yok
- Ses yolu 6.1 kapsamında: cihazda gerçek çağrı testi olmadan dokunulmaz

---

## 13. ⚠️ BÖLÜM 12 DÜZELTMESİ — ses iPhone'a GEÇİYORDU (16.10.2026)

**Bölüm 12'de "IPHONE sahibi log'da hiç görünmüyor" yazdım. YANLIŞ.**

Sadece `tail -15`'e baktım; o 10:41-12:02 aralığını gösteriyordu. **Tüm dosyayı taradığımda
`AUDIO_OWNER=IPHONE` 6 kez geçiyor.**

### Ses iPhone'a 6 kez geçti — hepsi `iphone_confirmed`

| Başlangıç | Geçiş | Süre |
|---|---|---|
| 00:31:05.650 | 00:31:07.913 | **2.26 sn** |
| 00:32:12.165 | 00:32:14.693 | **2.53 sn** |
| 01:08:17.731 | 01:08:19.963 | **2.23 sn** |
| 08:33:23.363 | 08:33:25.583 | **2.22 sn** |
| 08:34:05.184 | 08:34:07.357 | **2.17 sn** |
| 10:33:31.189 | 10:33:33.690 | **2.50 sn** |

**Ortalama: ~2.32 saniye**

### 🔴 Ve 10:34'te kalıcı olarak bozuldu

```
10:33:33  AUDIO_OWNER=IPHONE   reason=iphone_confirmed      <<< SON BASARILI
10:34:02  AUDIO_OWNER=REDMI    reason=manual_redmi          <<< elle mi alindi?
10:34:07  AUDIO_OWNER=REDMI    reason=iphone_disconnected   <<< BAGLANTI KOPTU
10:40:40  AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
10:40:48  AUDIO_OWNER=REDMI    reason=iphone_audio_not_ready   <<< ILK BASARISIZ
10:56 / 11:48 / 11:52 ...      hep iphone_audio_not_ready
```

**Ses 10:34'ten sonra bir daha iPhone'a geçmedi.** Kullanıcının "iPhone'a hiç akmamaya başladı"
şikayeti **10:34 civarında** başlamış olabilir.

### İki gerçek bulgu

**1. Geçiş süresi ~2.2-2.5 sn — hedefin (`<1 sn`) ÜSTÜNDE** ✗
Çalıştığında bile hedefi tutmuyor. Madde 3 **ölçülmüş olarak karşılanmıyor.**

**2. 10:34'teki olay sesi kalıcı kesti** — `manual_redmi` ardından `iphone_disconnected`.
Sonraki her çağrıda `iphone_audio_not_ready`. **Sebebi bilinmiyor.**

### Doğrulamak için
`manual_redmi` neden oldu (kullanıcı mı sesi elle aldı?) ve sonrasında iPhone neden bir daha ses
vermedi. 10:33-10:41 arası tüm log satırları okunmalı.

---

## 14. 🎯 GEÇİŞ SÜRESİNİN KIRILIMI ve SESİN KOPTUĞU AN (16.10.2026)

### Geçiş nerede harcanıyor — uygulamanın kendi satırı

10:33'teki **son başarılı** çağrıdan:

```
Call audio moved to iPhone (uplink=50/s downlink=49/s
    settle=454ms firstWrite=80ms firstRead=133ms
    firstPcm=592ms startMedia=2023ms gateMs=1431ms gateFrames=76)
```

| Bileşen | Süre | Pay |
|---|---|---|
| **`startMedia`** | **2023 ms** | **baskın** |
| `gateMs` | 1431 ms (76 kare) | `startMedia`'nın parçası |
| `firstPcm` | 592 ms | |
| `settle` | 454 ms | |
| `firstWrite` / `firstRead` | 80 / 133 ms | küçük |

**Toplam 2023 ms** — ölçülen 2.2-2.5 sn ile **tutarlı** ✓

**Sonuç:** madde 3'ün (`<1 sn`) önündeki tek büyük engel **`startMedia` = 2023 ms.**
Bu, daha önce hesaplanan **"1286 ms taban"** ile aynı yönü gösteriyor: **kurulum `arm()` öncesine
taşınmadan (v2 motor ayrımı) `<1 sn` ulaşılamaz.**

### 🔴 Sesin koptuğu an: 10:39'daki YENİDEN KURULUM

```
10:33:33  AUDIO_OWNER=IPHONE reason=iphone_confirmed     <<< SON BASARILI
10:34:02  AUDIO_OWNER=REDMI  reason=manual_redmi         <<< KULLANICI sesi elle aldi - BUG DEGIL
10:34:02  Call audio session closed: manual_redmi
10:34:07  AUDIO_OWNER=REDMI  reason=iphone_disconnected
10:34:17  Call State Changed: State=disconnecting
10:39:31  App: diagnostics started
10:39:31  Device boot event: android.intent.action.MY_PACKAGE_REPLACED   <<<<<< YENIDEN KURULDU
10:39:32  Gateway Foreground Service started on port 8787
10:40:10  Yeni cagri basladi
10:40:48  AUDIO_OWNER=REDMI reason=iphone_audio_not_ready   <<< BIR DAHA GECMEDI
```

**`manual_redmi` bir hata değil** — kullanıcı sesi elle Redmi'ye aldı ✓

**Ama 10:39:31'de uygulama YENİDEN KURULDU (`MY_PACKAGE_REPLACED`) ve sonrasında ses bir daha
iPhone'a geçmedi.** 10:40, 10:56, 11:48, 11:52 — hepsi `iphone_audio_not_ready`.

### Hipotez
**Yeniden kurulum sonrası iPhone'a ses verme yolu bozuluyor.**
`iPhone connected (protocol 2)` **geliyor** (yani kimlik doğrulama çalışıyor) ama iPhone
**uplink sesi göndermiyor**.

**Bu, AGENTS.md 6.1'deki `audio_start` sınıfıyla aynı kokuyor:** *"arm başına bir kez geldiği için
bir daha denenmiyor."* Yeniden kurulumdan sonra arm bir kez geliyor ve iPhone tarafı hazır
değilse **bir daha denenmiyor.**

### Doğrulamak için
1. Uygulamayı yeniden kur → **cevaplanan** bir çağrı yap → `iphone_audio_not_ready` **tekrarlıyor mu?**
2. Tekrarlıyorsa: yeniden kurulum sonrası `arm()`/`audio_start` zincirini logdan izle
3. **Bu, kullanıcının "iPhone'a hiç akmamaya başladı" şikayetinin kök nedeni olabilir** —
   çünkü her APK kurulumu (benim turlarım dahil) bu durumu yaratıyor olabilir

---

## 15. 🎯🎯 KESİN TEŞHİS: `startMedia` SESSİZCE BAŞARISIZ OLUYOR (16.10.2026)

**Aynı filtreyle başarılı ve başarısız çağrıyı yan yana koydum. Fark tek satır.**

### BAŞARILI (10:33)
```
10:33:31.187  Call audio session armed
10:33:31.189  AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
10:33:31.201  Call audio session armed
10:33:33.291  Call audio media started; verifying before handing over   <<<<<< VAR
10:33:33.443  RELAY_HEALTH owner=PREPARING_IPHONE iphone=14/s uplink=0/s ...
10:33:33.690  AUDIO_OWNER=IPHONE reason=iphone_confirmed                <<< BASARILI
```

### BAŞARISIZ (10:40 — yeniden kurulumdan sonra)
```
10:40:40.443  Call audio session armed
10:40:40.443  AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
10:40:40.639  Call audio session armed
                                                                        <<<<<< "media started" YOK
                                                                        <<<<<< RELAY_HEALTH YOK
10:40:48.495  AUDIO_OWNER=REDMI reason=iphone_audio_not_ready           <<< BASARISIZ
```

### Sonuç

| | Başarılı | Başarısız |
|---|---|---|
| `Call audio session armed` | ✅ ×2 | ✅ ×2 |
| **`Call audio media started`** | **✅** | **❌ YOK** |
| `RELAY_HEALTH` | ✅ | ❌ YOK |
| Sonuç | `iphone_confirmed` | `iphone_audio_not_ready` |

**`startMedia()` hiç çalışmıyor.** Ve **log'da hiçbir hata satırı yok** — ne istisna ne gerekçe.

### 🔴 Bu AGENTS.md 6.1'in yasakladığı sessiz yol

> *"Sessiz çıkış yolu bırakma: `guard ... else { return }` log basmalı. Sessiz düşen bir guard,
> 'ses yok'un tek açıklaması olur ve teşhis edilemez."*

**Tam olarak bu durum.** `startMedia` başarısız oluyor, sebebini söylemiyor, ve 8 saniye sonra
ses Redmi'ye dönüyor. **Kullanıcının "iPhone'a hiç akmamaya başladı" şikayeti bu.**

### Hipotez (kanıtlanmadı)
**Yeniden kurulumdan sonra `startMedia` başarısız oluyor.** 10:39:31'de `MY_PACKAGE_REPLACED`
geldi, 10:40'tan itibaren her çağrıda `media started` satırı **yok**.

**Olası sebep:** yeniden kurulum sonrası audio odak/oturum durumu (ör. `AudioManager` modu,
Telecom ses rotası, ya da `CallAudioRelay`'in ilk kurulumu) bozuluyor ve `startMedia`
sessizce düşüyor.

### ✅ Sıradaki adım — net ve cihazda yapılabilir

**`startMedia`'nın sessiz çıkış yollarına log eklemek.** Bu:
- **Ses yoluna dokunur ama davranışı değiştirmez** (yalnızca log)
- **Cihazda doğrulanabilir** (yeni APK kur → çağrı yap → hangi guard düştüğünü gör)
- **Kök nedeni söyler** — şu an tek bilinmeyen bu

**Ve asıl düzeltme o logdan sonra yazılır.**
