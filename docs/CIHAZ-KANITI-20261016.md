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

---

## 16. 🎯 MEKANİZMA: iPhone `audio_start` GÖNDERMİYOR (16.10.2026)

### Akışın gerçek yönü

`GatewayWebSocketServer.kt:357`:
```kotlin
"audio_start" -> startAudio(peer)
```

**`audio_start`'ı iPhone GÖNDERİYOR.** Redmi onu **bekliyor**, göndermiyor.

```
1. Redmi: arm()                        -> "Call audio session armed" ×2
2. Redmi: PREPARING_IPHONE             -> "waiting_for_iphone_audio"
3. iPhone: audio_start + uplink göndermeli
4. Redmi: kapıda iPhone çerçevesi bekler -> RELAY_HEALTH iphone=N/s
5. Kapı açılır                          -> startMedia() -> iphone_confirmed
```

### Başarılı vs başarısız — 3. adımda ayrılıyor

| Adım | Başarılı (10:33) | Başarısız (10:40) |
|---|---|---|
| 1. arm | ✅ ×2 | ✅ ×2 |
| 2. PREPARING_IPHONE | ✅ | ✅ |
| **3. iPhone uplink** | **✅ `iphone=14/s`** | **❌ HİÇ** |
| 4. RELAY_HEALTH | ✅ | ❌ yok |
| 5. `startMedia()` | ✅ | **❌ hiç çağrılmıyor** |

**`startMedia()` çağrılmıyor çünkü kapı hiç iPhone çerçevesi görmüyor.** Suçlu Redmi değil —
**iPhone ses göndermiyor.**

### 🔴 Ve bu AGENTS.md 6.1'in tarif ettiği tam senaryo

> *"`audio_start` **arm başına bir kez** geldiği için bir daha denenmiyor → **ses yok**."*

Redmi iPhone'a "sıra sende" mesajını **yalnızca ses-durumu DEĞİŞTİĞİNDE** gönderiyor
(`sendAudioState`). **iPhone o an yeniden bağlanıyorsa mesajı kaçırır ve bir daha hatırlatılmaz.**

**Kanıt:** başarısız çağrıda `iPhone connected (protocol 2)` **10:40:11.890**'da geldi,
`PREPARING_IPHONE` **10:40:40.443**'te. Yani bağlantı **29 saniye önce** kurulmuştu — ama
ses-durumu mesajı **bir kez** gitti ve iPhone onu **işlemedi**.

### ✅ Yapılabilecek düzeltme — **Android tarafında**

**`PREPARING_IPHONE` durumundayken ses-durumu mesajını periyodik olarak TEKRARLA**
(ör. 1 sn'de bir, 8 sn boyunca). Bu:
- **Davranışı iyileştirir** — kaçırılan mesaj telafi edilir
- **Ses yoluna dokunur** → AGENTS.md 6.1: **cihazda gerçek çağrı testi şart**
- **Cihazda doğrulanabilir:** çağrı yap → `iphone=N/s` **geliyor mu**
- **iOS build gerektirmez** ✓

**Risk:** mesaj tekrarı iPhone'da çift `audio_start` tetiklerse... Hmm — `startMedia` zaten
idempotent olmalı (`startMedia` tekrar çağrılırsa). **Cihazda test edilmeli.**

---

## 17. ✅✅ BASE64 PROTOKOL DEĞİŞİKLİĞİ CİHAZDA DOĞRULANDI (16.10.2026 15:28)

**Tur 140'tan beri açık olan tek protokol riski kapandı.**

`BridgeCrypto`, `android.util.Base64` → `java.util.Base64` olarak değiştirilmişti (birim testinde
stub `null` döndüğü için `SecureChannel` test edilemiyordu). **Değişiklik protokole dokunuyordu** ve
12 tur boyunca doğrulanamamıştı — çünkü iPhone o sırada hiç bağlanmıyordu.

### Kanıt

**Kurulu build (AUDIO-STATE-REPEAT, güncel ağaçtan derlendi):**
```
java/util/Base64               True   <<< protokol degisikligi VAR
idleWakePauseMs                True
classifyClose                  True
Repeated the preparing state   True
```

**Ve kurulumdan SONRA iPhone kimlik doğruladı:**
```
15:27:52  lastUpdateTime                                  <<< kurulum
15:28:03  pid 29721  WebSocket opened from 10.64.17.201
15:28:03  pid 29721  iPhone connected (protocol 2)        <<<<<< CALISTI
```

**Aynı süreç (29721) hem `java/util/Base64` içeriyor hem de `iPhone connected (protocol 2)` üretiyor.**
Yani encoder değişikliği **el sıkışmayı bozmuyor** ✓

### Önceden yapılan doğrulamalar (şimdi cihazda teyit edildi)
- RFC 4648 §10 vektörleri (`Base64EncodingTest`)
- **Python'la bağımsız hesaplanan** protokol zinciri değerleri (`ProtocolChainTest`)
- Replay + yön koruması (`SecureChannelTest`)

**Üçü de doğruymuş.** Cihaz bunu teyit etti.

### Kalan tek doğrulanmamış ses değişikliği
**`AUDIO-STATE-REPEAT`** (tur 171) — `PREPARING_IPHONE` boyunca ses-durumu mesajını tekrarlıyor.
**Gerçek çağrı gerekiyor** ve o çağrı henüz yapılmadı (`Repeated the preparing state` sayısı: **0**).

---

## 18. ✅ MADDE 5 — HOTSPOT DOĞRU KARAR VERMİŞ DURUMDA (16.10.2026 15:35)

**Çağrı gerektirmeyen tek madde buydu ve cihazdan okundu.**

### Ağ durumu

```
wlan2        inet 10.64.17.212/24          <<< HOTSPOT ARAYUZU AYAKTA
rmnet_data1  inet 10.216.122.179/29        <<< mobil veri var
iPhone       10.64.17.201                  <<< ayni /24 - hotspot istemcisi
```

### WiFi istemci durumu

```
mWifiInfo: Supplicant state: DISCONNECTED, IP: null, RSSI: -127
CMD_SET_AP kayitlari: hepsi 10-13 (3 gun once) - yeni mudahale YOK
```

### Yorum — kuralın istediği davranış

| Kural | Durum |
|---|---|
| **Tanıdık ağ görünce hotspot kapanır** | WiFi istemci **DISCONNECTED** → tanıdık ağ **yok** → hotspot **açık kalıyor** ✓ |
| **iPhone hotspot üzerinden bağlanır** | iPhone `10.64.17.201`, hotspot `10.64.17.212/24` → **aynı ağ** ✓ |
| **Mobil kota koruması** | `rmnet_data1` var; ses yolu **LAN üzerinden** (WebSocket `10.64.17.x`) → **mobil veri kullanılmıyor** ✓ |

**Hotspot 3 gündür `CMD_SET_AP` almamış** — yani `SmartHotspot` kararı **kalıcı ve kararlı** ✓

### ⚠️ Teşhis boşluğu bulundu

**`SmartHotspot` hiçbir kararını loglamıyor.** Log'da `hotspot` içeren **tek satır yok**:
```
grep -iE 'hotspot|SmartHotspot|tether' call-diagnostics.log   ->  BOS
```

**Sonuç:** hotspot'un **neden** açık/kapalı olduğu cihazdan **okunamıyor.** Şu anki durum
dışarıdan çıkarıldı (arayüz + WiFi durumu), uygulamanın kendi kararından değil.

**Bu, tur 135'te bulunan "kopma loglama boşluğu" ile aynı sınıf:** karar veriliyor, kaydı yok.

### Yapılacak (düşük risk, çağrı gerektirmez)
`SmartHotspot.decideHotspotAction` sonucunu tanılama loguna yaz — karar değiştiğinde bir satır.
**Ses yoluna dokunmaz, cihazda doğrulanabilir.**

---

## 19. ✅ MADDE 5 TEŞHİS BOŞLUĞU KAPANDI VE DOĞRULANDI (16.10.2026 15:32)

**Bölüm 18'de "SmartHotspot hiçbir kararını loglamıyor" demiştim. Asimetriymiş:** `STOP_HOTSPOT`
dosyaya yazıyordu, `START_HOTSPOT`/`TRY_KNOWN_NETWORK` yalnızca logcat'e.

**Düzeltme kuruldu ve AYNI DAKİKA ateşlendi:**

```
15:32:12.759  App: diagnostics started                    <<< HOTSPOT-LOG kurulumu
15:32:13.143  Gateway Foreground Service started on port 8787
15:32:13.150  SmartHotspot: Wi-Fi lost; trying a known network before falling back to the hotspot
15:32:22.719  iPhone connected (protocol 2)
15:32:33.206  SmartHotspot: Wi-Fi lost; trying a known network before falling back to the hotspot
```

### Doğrulanan

| Ne | Kanıt |
|---|---|
| **Yeni log satırı dosyaya yazılıyor** | ✅ 2 satır, kurulumdan 0.4 sn sonra |
| **Karar yolu görünür** | ✅ `Wi-Fi lost → known network → hotspot` |
| **Kural işliyor** | ✅ Tanıdık ağ yok → hotspot'a düşülüyor |

**Artık hotspot'un durumu dışarıdan çıkarılmıyor; uygulama kendi kararını yazıyor** ✓

### Madde 5 durumu
| Kural | Durum |
|---|---|
| **Tanıdık ağ görünce hotspot kapanır** | ✅ Karar yolu loglanıyor, `STOP_HOTSPOT` zaten yazıyordu |
| **iPhone hotspot üzerinden bağlanır** | ✅ `10.64.17.201` ↔ `10.64.17.212/24` (bölüm 18) |
| **Mobil kota koruması** | ✅ Ses yolu LAN üzerinden; mobil veri harcanmıyor |

**Madde 5 tamamen doğrulandı** ✓ — davranış + teşhis edilebilirlik.

---

## 20. ⚠️ KENDİ REGRESYONUM: log satırı 20 saniyede bir tekrarlıyordu (16.10.2026 15:34)

**Bölüm 19'da eklediğim log satırı cihazda hemen görüldü — ve hemen bir kusur gösterdi:**

```
15:32:13.150  Wi-Fi lost; trying a known network...
15:32:33.206  Wi-Fi lost; trying a known network...
15:32:53.227  Wi-Fi lost; trying a known network...
```

**Değerlendirme her 20 saniyede çalışıyor** ve durum genelde **değişmemiş** oluyor. Satırı her geçişte
yazmak **dakikada 3 kez aynı metni** yazıyordu → **4000 satırlık döngüde gerçek geçmişi itiyordu.**

### Düzeltme: `recordDecision` — yalnızca karar DEĞİŞTİĞİNDE dosyaya yaz

```kotlin
private fun recordDecision(action: HotspotAction, message: String) {
    Log.i(TAG, message)                        // logcat: her gecis
    if (action == lastRecordedAction) return   // dosya: yalnizca degisim
    lastRecordedAction = action
    DiagnosticsLog.record(TAG, message)
}
```

### ✅ Cihazda doğrulandı

```
Dosya (tanilama) :  6 -> 6     120 saniyede ARTMADI
Logcat           :  10 satir   orada tam kayit devam ediyor
```

**Kurulumdan 1 sn sonra gelen tek satır (`15:33:53.206`) doğru davranış:** ilk değerlendirmede
karar `null → TRY_KNOWN_NETWORK` **değiştiği** için yazıldı; sonraki tekrarlar yazılmadı ✓

### Ders
**Bir log satırı eklerken "bu kaç kez çalışır?" sorusu sorulmalı.** Değerlendirme döngüsü içindeki
her kayıt, döngü frekansında yazar. Kullanıcının gördüğü yan etki sınıfı (kural 0.2) log için de
geçerli: **dosya dolup faydalı geçmiş kaybolur.**

---

## 21. ORTAM ÖN KOŞULU + KURULUM SAYACI (16.10.2026 15:55, SALT OKUMA)

**Kural 0.4 uygulandı: bu turda cihaza HİÇBİR ŞEY kurulmadı.**

### 1. Kurulum sayacı — kendi izim

```
Kurulum sayisi: 26   (dun de 26 idi -> taahhut tutuldu, yeni kurulum YOK)
```

**Bu sayı bir teşhis aracıdır** (kural 0.4). Bugün 8 kurulum yapılmıştı; ses 00:31-10:33 arası
6 kez çalıştı, 10:39'daki kurulumdan sonra çalışmadı. **Korelasyon güçlü, mekanizma kanıtlanmadı.**

### 2. Ortam ön koşulu (kural 6.5 adım 1)

| Kontrol | Sonuç |
|---|---|
| **ROM tarihi** | `Sat Sep 19 18:31:47 UTC 2026` |
| **Vendor düzeltmesi uygulanma tarihi** | **27.09.2026** (tur 134'te doğrulandı) |
| **Sonuç** | Düzeltme **ROM'dan SONRA** uygulanmış → **ROM güncellemesi onu silmemiş** ✓ |
| `/vendor` içeriği | ❌ **Root yok → okunamıyor.** İçerik doğrulaması **tekrarlanamadı** |

**Dürüst sınır:** Vendor düzeltmesinin hâlâ yerinde olduğunu **bu turda kanıtlayamadım.**
Tek dayanak: ROM tarihi (19.09), düzeltme tarihinden (27.09) **eski** — yani düzeltmeden sonra
ROM güncellenmemiş ✓ Bu **çıkarım**, içerik doğrulaması değil.

### 3. Uygulama sağlığı

```
15:35:47  iPhone connected (protocol 2)
15:40:47  iPhone connected (protocol 2)
15:45:47  iPhone connected (protocol 2)
15:51:59  iPhone connected (protocol 2)
```
**iPhone 1-6 dakikada bir bağlanıyor** → uygulama ayakta, ağ yolu çalışıyor ✓

`AUDIO_OWNER` son kaydı **12:02** → **12:02'den beri hiç çağrı gelmemiş** ✗

### 4. Bekleyen
- **Cevaplanan çağrı** → madde 3 ve 4
- **Ekran kilidi** (`mDreamingLockscreen=true`) → madde 1

---

## 22. MADDE 1 DENETİMİ — EKRAN GÖRÜNTÜSÜ ALINAMADAN (16.10.2026, SALT OKUMA)

**Ekran kilitli olduğu için görüntü alınamıyor. Bunun yerine `CallActivity.kt` standart bir
dialer'ın ögelerine karşı denetlendi.**

| Standart öge | Durum |
|---|---|
| Yakınlık sensörü (konuşurken ekran kapanır) | ✅ VAR |
| Çağrı süresi sayacı | ✅ VAR |
| Hoparlör butonu | ✅ VAR |
| Sustur butonu | ✅ VAR |
| Tuş takımı (DTMF) | ✅ VAR |
| Kilit üstü tam ekran | ✅ VAR |
| Kişi adı çözümü | ✅ VAR |
| Cevap / çağrıyı bitir | ✅ VAR |
| **Bluetooth ses yolu butonu** | ❌ **YOK** |

### Değerlendirme

**Tek eksik Bluetooth butonu.** Google Dialer ve iOS CallKit'te bu buton standarttır; kullanıcı
sesi kablosuz kulaklığa yönlendirebilir.

**Ama bunun bu projede kasıtlı olma olasılığı yüksek:** ses yolu zaten **iPhone'a** gidiyor
(`AUDIO_OWNER=IPHONE`), bir kulaklığa değil. Yani "Bluetooth'a yönlendir" kavramı bu mimaride
karşılıksız olabilir.

**Bu yüzden bunu "eksiklik" olarak değil, AÇIK SORU olarak kaydediyorum.** Kullanıcıya sorulacak:
*"Redmi'de konuşurken sesi bir Bluetooth kulaklığa yönlendirmek ister misiniz, yoksa ses zaten
iPhone'da olduğu için gereksiz mi?"*

### Kalan işler (kurulum gerektirir — kural 0.4: kullanıcı onayı olmadan kurulmaz)
1. **Bluetooth butonu** — yalnızca kullanıcı isterse
2. Ses geçişi (`startMedia=2023ms` → `<1 sn`) — motor ayrımı, **çağrı doğrulaması şart** (6.1)

---

## 23. 🎯 GEÇİŞ GECİKMESİNİN KESİN SEBEBİ: iPhone'un SES MOTORU GEÇ BAŞLIYOR (16.10.2026)

**Kapı eşikleri `CallAudioRelay.kt`'den okundu:**

```kotlin
ARRIVAL_WINDOW_MIN_MS     = 240L    // 240 ms yeterli
ARRIVAL_WINDOW_MIN_FRAMES = 12      // 12 kare = 240 ms
MIN_FRAMES_PER_SECOND     = 38      // saniyede 38 kare sart
GATE_SAMPLE_MS            = 140L
```

**Eşiklere göre kapı ~240 ms'de açılmalı. Ölçülen:**

```
gateMs     = 1431 ms      <<< esigin gerektirdiginin 6 KATI
gateFrames = 76
```

### Sebep: iPhone 14 kare/sn ile başlıyor

**Başarılı çağrının kendi `RELAY_HEALTH` satırı** (10:33:33.443):
```
owner=PREPARING_IPHONE iphone=14/s uplink=0/s pcm=0/s ... maxWriteMs=3
```
**Sonra** (10:33:38.566):
```
owner=IPHONE iphone=52/s uplink=52/s pcm=51/s ...
```

**iPhone `14/s` ile başlıyor, `38/s` eşiğini geçmesi ~1.4 saniye sürüyor.**

### Hesap birebir tutuyor

```
firstPcm   592 ms   ilk kare geldi
gate      1431 ms   hiz 38/sn esigini gecene kadar beklendi (76 kare)
------------------------------
startMedia 2023 ms  OLCULEN DEGER
```

### Sonuç — `<1 sn` neden eşik gevşeterek tutulamaz

**Sorun eşik değil, iPhone'un ses motorunun GEÇ BAŞLAMASI.** Kapıyı gevşetmek
(`MIN_FRAMES_PER_SECOND` düşürmek) iPhone **ses vermeden** devir teslim yapmak demektir →
**kullanıcı sessizlik duyar.** Bu, 6.1'in doğduğu hatanın ta kendisi.

**Tek doğru çözüm:** iPhone'un ses motoru **devir teslimden ÖNCE** çalışıyor olmalı —
**motor ayrımı (pre-arm)**, yani `codex/engine-split-v2` dalının yaptığı şey.

**Ve 6.1 gereği:** cihazda **gerçek çağrı** ile doğrulanmadan gönderilemez ✗

### Kalan tek belirsizlik
**`firstPcm=592 ms` neden bu kadar uzun?** İki bileşen olabilir:
1. iPhone'un ses motorunu başlatma süresi (iOS tarafı)
2. Redmi'nin `audio_state` mesajının iPhone'a ulaşma + işlenme süresi

**Ayırt etmek için:** `AUDIO_STATE_REPEAT` düzeltmesi çağrıda denendiğinde, tekrar **hemen**
başlıyorsa (1 sn aralıklarla) ve `firstPcm` **düşüyorsa**, ikinci bileşen baskındı — yani
Redmi tarafında yapılabilecek bir şey var. **Düşmüyorsa** sorun iOS'ta ve **CI şart.**

---

## 24. 🎯🎯 MADDE 3'ÜN DÜZELTMESİ ANDROID TARAFINDA — iOS BUILD GEREKMİYOR (16.10.2026)

**iOS kodu okundu: `GatewayConnection.swift:888-912`**

```swift
private func handleAudioState(_ audio: AudioStateMessage) {
    if audio.detail == "request_iphone_audio" {
        CallKitManager.shared.takeCallOnIPhone()      // <<< MOTOR BURADA BASLIYOR
        return
    }
    switch audio.owner ?? (audio.running ? "iphone" : "redmi") {
    case "iphone":            audioLocation = .iphone
    case "preparing_iphone":  audioLocation = .movingToIPhone   // <<< SADECE ARAYUZ BAYRAGI
    default:                  ...
    }
}
```

### Bulgu

**Redmi `preparing_iphone` gönderdiğinde iPhone HİÇBİR ŞEY başlatmıyor** — yalnızca
`audioLocation` adlı **arayüz bayrağını** güncelliyor. Ses motoru **yalnızca
`request_iphone_audio`** detayı gelince çalışıyor (`takeCallOnIPhone()`).

### Akış ve maliyet

| # | Redmi | iPhone |
|---|---|---|
| 1 | `preparing_iphone` gönderir | **sadece bayrak** — motor yok |
| 2 | iPhone'un sesini bekler | — |
| 3 | `request_iphone_audio` gönderir | **motor ŞİMDİ başlar** |
| 4 | kapıda bekler (14/s → 52/s) | motor yükseliyor |

**`firstPcm = 592 ms` = iPhone'un motor başlatma süresi** — ve bu süre **istek ne zaman
gönderilirse o zaman** başlıyor.

### ✅ Düzeltme: `request_iphone_audio`'yu DAHA ERKEN gönder

**Çağrı çalarken (cevaplanmadan önce)** istek gönderilirse, iPhone motoru **cevap anında
ısınmış** olur → `firstPcm` düşer → `startMedia` düşer → **`<1 sn` hedefi tutulabilir.**

**Bu değişiklik:**
- **Android tarafında** ✓ — Redmi mesajı ne zaman göndereceğini değiştiriyor
- **iOS build gerektirmiyor** ✓ — protokol aynı, iPhone eski mesajı aynı şekilde okuyor
  (yorumda yazılı: *"no new type, no new command and no protocol bump is needed"*)
- **Ses yoluna dokunuyor** → **AGENTS.md 6.1: cihazda gerçek çağrı doğrulaması ŞART** ✗
- **Kural 0.4: kullanıcı onayı olmadan cihaza KURULMAZ** ✗

### Neden kapı gevşetmek değil
Kapıyı gevşetmek (`MIN_FRAMES_PER_SECOND` düşürmek) iPhone **ses vermeden** devir teslim
demektir → **sessizlik**. Bu, 6.1'in doğduğu hatanın şekli. **Erken istek** ise motoru
**gerçekten** çalıştırır — ses hazır olur.

### Riskler (kurulumdan önce düşünülecek)
1. **Çağrı cevaplanmazsa** iPhone motoru gereksiz çalışır → **pil + veri** (0.2)
2. iPhone'un **mikrofonu** erken açılırsa → **gizlilik**: kullanıcı cevaplamadan dinleme olur mu?
   → **Bu ciddi bir soru; cevaplanmadan gönderilmemeli.**
3. İki `request_iphone_audio` → `takeCallOnIPhone()` iki kez → idempotent mi?

**Sonuç: düzeltme net ve Android'de, ama riski 2 (gizlilik) yüzünden tasarım kararı gerekiyor.**

---

## 25. ✅ GİZLİLİK RİSKİ YOK · ✅ TEKRAR DÜZELTMESİ UYUMLU · ❌ ERKEN İSTEK İŞE YARAMAZ (16.10.2026)

**`CallKitManager.swift:368` okundu.**

```swift
func takeCallOnIPhone() {
    let connection = GatewayConnection.shared
    guard let call = connection.currentCall, call.state == .active, connection.isAuthenticated else {
        GatewayConnection.shared.log("Cannot take the call audio: no active call on the link")
        return
    }
    ...
    if audioRequested {
        audioPathLog("audio already requested; duplicate request ignored")
        return
    }
```

### 1. ✅ Gizlilik riski (bölüm 24, risk 2) GERÇEK DEĞİL
**iPhone, çağrı `active` olmadan motoru başlatmıyor.** `call.state == .active` guard'ı var →
**cevaplanmadan önce dinleme mümkün değil** ✓ **Bu riski geri çekiyorum.**

### 2. ❌ Ama "erken istek" düzeltmesi de İŞE YARAMAZ
Aynı guard yüzünden: Redmi çalarken istese bile **iPhone reddeder** ve
`"Cannot take the call audio: no active call on the link"` yazar.

**Yani bölüm 24'teki düzeltme önerisi geçersiz.** Redmi tarafından motor ısıtılamaz —
**iPhone bunu tasarım gereği engelliyor.**

### 3. ✅ Tekrar düzeltmem (`AUDIO-STATE-REPEAT`) UYUMLU
**iOS kodunun kendi yorumu:**
> *"The request can arrive more than once (**the Redmi repeats it**, and the app's own button
> sends the same one). … so sending it is idempotent here."*

**iOS zaten tekrarı bekliyor ve çift isteği yok sayıyor** ✓
- Bölüm 24'ün **3 numaralı riski (idempotency) kapandı** ✓
- Kurulu düzeltme **güvenli** ✓

### Sonuç — `<1 sn` için ne gerekiyor

| Yol | Durum |
|---|---|
| Kapıyı gevşet | ❌ Sessizlik verir (6.1'in hatası) |
| Redmi erken istesin | ❌ **iPhone reddediyor** (`state == .active` guard) |
| **iOS motoru cevap öncesi ısıtsın** | ⚠️ **iOS değişikliği + tasarım kararı gerekir** |
| Olduğu gibi kabul (2.32 sn) | ✅ Çalışıyor, hedefin üstünde |

**Yani madde 3'ün `<1 sn` hedefi, iOS tarafında bir tasarım değişikliği olmadan
tutulamaz.** — ve o değişiklik **CI'ya bağlı** ✗

### Kalan belirsizlik (dürüstçe)
**`firstPcm = 592 ms`'in ne kadarı motor başlatma, ne kadarı mesaj gecikmesi** hâlâ ayrıştırılmadı.
Çağrı testi bunu söyler. Ama **motor başlatma baskınsa**, Redmi tarafında yapılacak bir şey
**yok** — çünkü iPhone erken başlatmayı reddediyor.

---

## 32. ✅ KAPANIŞ — kilit çözüldü ve GERÇEK ÇAĞRIYLA doğrulandı (16.10.2026 16:06)

### Kök neden (bölüm 31'de bulundu, 16:04'te kuruldu)
`CallAudioRelay.kt` içindeki devir teslim kapısı **saf kilitlenme** hâlindeydi:
`gateSampleAt` sıfırdan başlıyor, `sampleMs` onu görünce zorla 0 yapılıyor, `recentRate` de
0 kalıyordu; **`gateSampleAt`'ı atayan koşul hiç doğru olamıyordu.** Yani kapı hiç açılmıyor,
`startMedia()` hiç çağrılmıyor, 8 sn sonra `iphone_audio_not_ready` yazılıyordu — ve **log
hiçbir sebep göstermiyordu.**

`1305f06` (10:38) getirdi, 10:39'daki kurulumla cihaza girdi; ses **10:33'ten sonra bir daha
geçmedi.** Bu, `AGENTS.md` 6.1'in ("ses yoluna dokunan değişiklik gerçek çağrıyla doğrulanmadan
gönderilmez") çiğnenmesiyle oldu.

### Düzeltme
Kapı, 10:33'te çalıştığı kanıtlanmış mantığa (**ömür boyu ortalama hız**) döndürüldü; ölü
örnekleme durumu (`GATE_SAMPLE_MS`, `gateSampleFrames`, `gateSampleAt`) **tamamen silindi**.
334 test, 0 failure. Kurulum 26 → **27** (dosyada `MY_PACKAGE_REPLACED` = 27).

### CİHAZ KANITI — uygulamanın kendi tanılama dosyasından
Çağrı: **outgoing**, 16:06:21 → 16:06:27 (iPhone'dan başlatıldı).

```
16:06:20.361  Call: iPhone connected (protocol 2)
16:06:21.195  Call: Initiating outgoing GSM call
16:06:21.360  Call: Call audio session armed
16:06:21.361  Audio: AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
16:06:22.383  Call: Call audio media started; verifying before handing over (gate=431ms frames=21 rate=50/s)
16:06:22.796  Audio: AUDIO_OWNER=IPHONE reason=iphone_confirmed
16:06:22.797  Call: Call audio moved to iPhone (uplink=50/s downlink=43/s settle=473ms
              firstWrite=78ms firstRead=153ms firstPcm=524ms startMedia=955ms gateMs=380 gateFrames=19 gateRate=50/s)
16:06:25.232  Call: Call State Changed: State=active        <- karsi taraf cevapladi
16:06:27.117  Call: Executing HANGUP / DISCONNECT
16:06:27.226  Audio: AUDIO_OWNER=REDMI reason=call_ended
```

**Sonuç 1 — ses geri geldi (madde 3'ün "geçiş" kısmı):** `GATE_OPEN` üretildi, ses iPhone'a
geçti. 10:39'dan sonra ilk kez. Kullanıcı da kendi deneyimiyle doğruladı.

**Sonuç 2 — geçiş süresi hedefin ALTINDA (ilk kez):**

| Çağrı | firstPcm | gate | **startMedia** |
|---|---|---|---|
| 08:34 (çalışıyordu) | 474 ms | — | 1474 ms |
| 10:33 (son çalışan) | 592 ms | 1431 ms | **2023 ms** |
| **16:06 (bu çağrı)** | 524 ms | 431 ms | **955 ms** |

Aynı metrik, aynı tanım (kural 6.4 ihlali yok): `startMedia = firstPcm + gate` →
592+1431 = 2023 ✓ ve 524+431 = 955 ✓.

**Sonuç 3 — HAL tarafı temiz (madde 4'ün ön koşulu):**
```
16:06:22.332  audio_hw_primary: start_output_stream: usecase(51: incall_music_uplink2)
              devices(0x10000) ... device voice-tx
16:06:30.208  audio_hw_primary: out_standby: usecase(51: incall_music_uplink2)
```
Tek **kesintisiz** stream, ~7.9 sn. Ve logcat tamponunda **`pcm_prepare` eşleşmesi = 0** →
6.5'te tarif edilen "saniyede ~14 kez yeniden başlatma" (helikopter/cızırtı) imzası **yok**.
Vendor düzeltmesi (USECASE_INCALL_MUSIC_UPLINK2 → SIM 2) **çalışıyor**.

### ⚠️ Bu çağrının KAPATMADIĞI şeyler (dürüst sınır)
1. **Madde 4 (cızırtı) kapanmadı.** Konuşma penceresi ~1.9 sn (`active` 16:06:25.232 →
   hangup 16:06:27.117). Devir teslim **sonrası** `RELAY_HEALTH` satırı hiç yazılmadı
   (5 sn'de bir yazılıyor) → `underruns / dropped / silence / maxWriteMs` **ölçülemedi**.
   "Ses geçti" ile "ses temiz" ayrı iddialardır; ikincisi için **≥5 sn konuşma** gerekir.
2. **`<1 sn` hedefi n=1.** `gateMs` çağrıya göre değişiyor (10:33'te 1431 ms, çünkü iPhone
   14/s ile başladı; 16:06'da 380-431 ms, çünkü 50/s ile başladı). Hedef **en az 3 çağrıyla**
   ve dağılımla (min/ort/max) doğrulanmalı → `AGENTS.md` **6.7**.
3. **Teardown'da bir `E` satırı:** `16:06:30.283 E audio_hw_primary: disable_snd_device:
   Invalid sound device 0`. Logcat tamponu bu oturumda **15:33:55'te başlıyor** ve 10:33
   çağrısını **kapsamıyor** → bu satırın yeni mi eski mi olduğu **bilinmiyor**. Kapsamayan
   tamponda yokluk kanıt değildir (kural 3.1).
4. **Madde 1 (arayüz) hâlâ ekran kilidine bağlı:** `mDreamingLockscreen=true`,
   `mWakefulness=Dozing` → ekran görüntüsü alınamıyor.
5. **Madde 2 (yanıtlama senkronu)** bu çağrıda ölçülmedi (çağrı iPhone'dan başlatıldı);
   616 ms'lik kanıt 15:03'teki **incoming** çağrıdan geliyor ve **hâlâ geçerli**.

### Kurulum disiplini
- **26 → 27.** Bu kurulum kullanıcının açık talimatıyla yapıldı ("ses hiç iPhone'a geçmiyor;
  ya düzelt ya da eski hâline getir") ve kural 0.4'ün beş sorusu yanıtlandı.
- Sonrasında **hiçbir kurulum yapılmadı**; bu bölümdeki tüm kanıt **salt okuma** ile toplandı.

---

## 33. İkinci ve üçüncü çağrı + madde 4'ün ölçülü teşhisi (16.10.2026 16:15, salt okuma)

Kullanıcı "konuştum" dedi: 16:15:13 → 16:15:27 çağrısı, **konuşma penceresi 9.6 sn**
(`active` 16:15:17.591 → hangup 16:15:27.208). Bu, kural 6.7'nin istediği ≥5 sn eşiğini geçen
ilk çağrı; yani **devir teslim sonrası `RELAY_HEALTH` satırları ilk kez yazıldı.**

### Çağrı ve geçiş
```
16:15:13.672  Audio: AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
16:15:14.980  Call: media started (gate=731ms frames=32 rate=38/s)
16:15:15.384  Audio: AUDIO_OWNER=IPHONE reason=iphone_confirmed
16:15:15.387  Call: moved to iPhone (uplink=50/s downlink=49/s firstPcm=505ms startMedia=1236ms gateMs=683 gateRate=38/s)
16:15:17.591  Call State Changed: State=active
16:15:27.208  Executing HANGUP / DISCONNECT
```

### Geçiş süresi — n=2, hedef TUTARLI tutmuyor (dürüst tablo)

| Çağrı | firstPcm | gate | startMedia | hedef <1 sn |
|---|---|---|---|---|
| 08:34 (eski mantık) | 474 ms | — | 1474 ms | ✗ |
| 10:33 (son çalışan, eski mantık) | 592 ms | 1431 ms | 2023 ms | ✗ |
| 16:06 (düzeltme sonrası) | 524 ms | 431 ms | **955 ms** | ✓ |
| **16:15 (düzeltme sonrası)** | 505 ms | 683 ms | **1236 ms** | ✗ |

**Düzeltme sonrası: min 955 · ort 1096 · max 1236 ms.** Yani `<1 sn` **bir çağrıda tuttu,
bir çağrıda tutmadı.** Değişen kısım `firstPcm` değil (505-524 ms, neredeyse sabit = iPhone
motorunun başlama süresi); değişen **kapının beklediği süre** (431 vs 683 ms). Kapı, iPhone'un
çerçeve hızı 38/s eşiğini geçene kadar bekliyor; 16:15'te hız tam sınırda (gateRate=38/s) açtı.

**Sonuç:** taban ~0.95 sn, tavan ~1.25 sn. Hedefi **garantili** kılmak, iPhone motorunun devir
teslimden önce ısınmasını gerektirir — ki bu iOS tarafında bir değişikliktir ve bölüm 30'da
iPhone'un `state == .active` guard'ı yüzünden mümkün olmadığı okunmuştu.

### Madde 4 (cızırtı/kesilme) — ölçülü teşhis

Devir teslim sonrası sağlık satırları:
```
16:15:20.102  owner=IPHONE iphone=52/s uplink=52/s pcm=52/s silence=0/s partial=0/s
              queued=7 targetFrames=10 dropped=0 expired=0 underruns=12 maxWriteMs=1
              maxAuthenticatedArrivalGapMs=197 maxAuthenticatedArrivalBurstFrames=7
16:15:25.226  owner=IPHONE iphone=50/s uplink=53/s pcm=51/s silence=2/s partial=0/s
              queued=0 targetFrames=11 dropped=0 expired=0 underruns=28 maxWriteMs=1
              maxAuthenticatedArrivalGapMs=204 maxAuthenticatedArrivalBurstFrames=8
```

**Temiz olanlar (hepsi iyi):**
- `pcm_prepare` = 0, HAL stream **tek ve kesintisiz**: `start_output_stream usecase(51:
  incall_music_uplink2)` 16:15:14.926 → `out_standby` 16:15:30.336 (~15.4 sn tek açılış).
  Yani "helikopter" imzası yok.
- `dropped=0`, `expired=0` → hiçbir çerçeve atılmadı.
- `maxWriteMs=1` → ses yazıcısı hiç bloklanmadı.
- `partial=0` → bozuk/eksik çerçeve yok.

**Kalan tek kusur — bağlantı patlamalı (bursty) ve tampon tam sınırda:**
`underruns` 12 → 28, yani 5.124 saniyede **16 çerçeve** (16 × 20 ms = 320 ms) gerçek ses yerine
gizleme ile dolduruldu → **~3.1 çerçeve/sn = çerçevelerin ~%6'sı.** Karşılaştırma: 10:33'teki
smooth çağrıda aynı ölçüm **0.78/sn (~%1.6)** idi.

Fark, ağın geliş deseninde:
| Çağrı | maxAuthenticatedArrivalGapMs | burstFrames | underrun hızı |
|---|---|---|---|
| 10:33 | 52 ms | 3 | 0.78/s (%1.6) |
| 08:34 | 206 ms | 5 | (tek satır: 41) |
| **16:15** | **197-204 ms** | **7-8** | **3.1/s (%6.2)** |

**Kök neden koddan doğrulandı (UplinkJitterBuffer.kt):**
```kotlin
const val LOCAL_TARGET_CEILING = 12       // 12 * 20 ms = 240 ms  (yerel link)
private fun observe(gapMs, burst, nowMs) {
    val gapFrames = if (gapMs > FRAME_MS) ((gapMs - FRAME_MS + FRAME_MS - 1) / FRAME_MS) else 0
    val need = maxOf(gapFrames, burst) + 1
    needFrames[needIndex] = minOf(need, ceiling())      // <<< TAVANDA KIRPILIYOR
}
```
`need = gapFrames + 1` ve `ceiling = 12` olduğu için **240 ms'den büyük her geliş boşluğu
yapısal olarak karşılanamaz** (gap 241 ms → gapFrames 12 → need 13 → 12'ye kırpılır).
Bugünkü ağın en kötü boşluğu **197-204 ms**, yani tavanın **~40 ms altında**: tampon
hedefi 10-11 çerçeveye çıkıyor ve her ekstra jitter'da kuru kalıyor (`queued=0` tam da bunu
gösteriyor). Yani cızırtının kaynağı **kod hatası değil, tamponun yerel-link tavanının bu ağın
gerçek davranışına göre dar olması.**

**Aday düzeltme (KURULMADI — kural 0.4/6.1 gereği çağrı doğrulaması ve karar bekliyor):**
yerel tavanı yükseltmek ya da gözlenen boşluk bir eşiği geçtiğinde linki "remote" profiline
(480 ms tavan) almaya izin vermek. Bedeli: gecikme 240 → 320-480 ms'ye çıkar (telsiz bütçesi).
Bu bir ses yolu değişikliğidir: **kurulmadan önce gerçek çağrıyla doğrulanmalıdır.**

### Madde 1 (arayüz) — ilk GEÇERLİ ekran görüntüsü bulundu
Ekran şu an kilitli (`mDreamingLockscreen=true`, `mWakefulness=Awake`), yeni görüntü alınamıyor:
16:15:29'da alınan kare **kilit ekranı** çıktı. Ancak bugün 10:31'de alınmış **geçerli** bir
kare var (`call-now.png`, 182 KB) ve uygulamanın kendi arayüzünü gösteriyor:

| Öge | Durum |
|---|---|
| "Son aramalar" başlığı + ayar dişlisi | ✅ |
| "iPhone bağlı" durum şeridi (yeşil nokta + ok) | ✅ |
| Yeşil "Görüşme sürüyor · 532" bandı + "Aç" düğmesi | ✅ |
| Arama geçmişi: avatar, ad, yön (Gelen/Giden), süre, **"iPhone'da"** etiketi | ✅ |
| Alt sekme çubuğu: Son · Kişiler · Tuşlar · Mesajlar | ✅ |

Dikkat: `call-ui2.png`, `call-live.png`, `call-live2.png`, `call-screen-live.png` **siyah**
(14.8 KB) — geçersiz kareler; kanıt sayılmaz.

**Hâlâ eksik:** çağrı **sırasında** tam ekran çağrı arayüzünün karesi. 10:29'da kilit ekranında
yalnızca "Görüşme sürüyor" **bildirimi** görünüyordu, tam ekran çağrı ekranı değil. Ama o kare
**13:21'deki `198e372` commit'inden önce** alındı ve o commit tam da bu yolu yeniden yazdı
(`setShowWhenLocked`/`setTurnScreenOn` → `FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON`). Yani
kilitliyken tam ekran çağrı ekranı çıkıyor mu sorusu **kurulu sürüm için hâlâ açık** — ve testi
tek: kilitliyken bir çağrı + ekran görüntüsü.

### Teardown'daki `E` satırı artık tekrarlanabilir
`E audio_hw_primary: disable_snd_device: Invalid sound device 0` — 16:06:30.283 **ve**
16:15:30.410, yani her iki çağrının da **kapanışında** (out_standby sonrası), ses akarken değil.
Hâlâ "zararsız" diyemem (10:33 penceresi tamponda yok), ama artık **her çağrıda tekrarlıyor** ve
zamanlaması sabit: çağrı bittikten ~3 sn sonra, stream kapanırken.

### Kurulum disiplini
Kurulum **27 → 27**: bu bölümdeki her şey **salt okuma** ile toplandı (adb shell okuma +
screencap). Kullanıcının çektiği ekran görüntüsü dosyası cihazdan silindi; hiçbir ayar,
kurulum veya `am start` yapılmadı.

---

## 34. "iPhone'a aktar" kararsız döngüye girdi — kullanıcının gördüğü şey (16.10.2026 16:23-16:24)

Kullanıcı Redmi'den arama yaptı, **"iPhone'a aktar"** dedi ve şunu bildirdi:
*"gitti geldi; bir şeyler oldu. takıldı falan en son iPhone'a geçti falan garip bir şey oldu."*
Kayıt bunu **birebir** doğruluyor.

### Çağrı ve ses sahipliğinin 21 saniyede 6 kez el değiştirmesi
```
16:23:45.899  Call: Initiating outgoing GSM call          (Redmi'den)
16:23:49.781  Call State Changed: State=active            (karşı taraf cevapladı)
16:23:52.472  Call: iPhone connected (protocol 2)         <- aktar isteğiyle uyandı
16:23:52.492  Audio: AUDIO_OWNER=PREPARING_IPHONE
16:23:54.408  Audio: AUDIO_OWNER=REDMI  reason=iphone_disconnected   (1) geri döndü
16:23:55.091  Call: media started (gate=300ms frames=18 rate=48/s)
16:23:55.498  Call: iPhone connected (protocol 2)
16:23:55.690  Audio: AUDIO_OWNER=REDMI  reason=iphone_disconnected   (2) tam geçerken iptal
16:23:57.828  Call: media started (gate=448ms)
16:23:58.225  Audio: AUDIO_OWNER=IPHONE reason=iphone_confirmed      (3) GEÇTİ (startMedia=632ms)
16:23:58.525  Audio: AUDIO_OWNER=REDMI  reason=iphone_disconnected   (4) 0.3 sn sonra geri
16:24:01.511  Call: media started (gate=324ms)
16:24:01.911  Audio: AUDIO_OWNER=IPHONE reason=iphone_confirmed      (5) GEÇTİ (startMedia=486ms)
16:24:04.315  Audio: AUDIO_OWNER=REDMI  reason=iphone_disconnected   (6) 2.4 sn sonra geri
16:24:05.383  GatewayWSServer: Repeated the preparing state (1)
16:24:06.386  (2)   16:24:07.395  (3)      <- AUDIO-STATE-REPEAT çalışıyor
16:24:07.264  Call: Executing HANGUP / DISCONNECT     <- kullanıcı beklemekten vazgeçti
```
Son denemede kapı **hiç açılmadı**: `GATE_WAIT reason=rateLow wait=2416ms frames=86 rate=35/s`
— iPhone'un çerçeveleri 38/s eşiğinin altında kaldı, kapı haklı olarak devretmedi.

### Kök neden: soket 32 saniyede 9 kez açıldı, hepsi sessizce öldü
```
16:24:04.213  WebSocket closed code=1006 remote=true  sessionMs=3332  sinceLastMessageMs=3215 verdict=closed_quiet
16:24:18.406  WebSocket closed code=1006 remote=false sessionMs=14174 sinceLastMessageMs=11020 verdict=closed_quiet reason=Connection reset by peer
16:24:18.530  WebSocket closed code=1006 remote=true  sessionMs=56    sinceLastMessageMs=16    verdict=dropped_while_active
```
`code=1006` = el sıkışmasız ani ölüm. Ve **`sinceLastMessageMs ≈ sessionMs`**: soket açıldıktan
sonra neredeyse **hiç mesaj gelmedi**, sonra öldü. Android tarafında **keepalive/idle timeout yok**
(kodda yalnızca yorum var) → kapanışları Redmi kapatmıyor. Redmi'nin her `iphone_disconnected`
gerekçesi bu ölümlerdir ve her ölüm ses oturumunu kapatıp sesi Redmi'ye döndürür.

### Ve istek, iPhone'un kendi kapatmaya çalıştığı bağlantıdan gidiyor
```swift
// GatewayConnection.swift — iOS
private func finishCatchUp() {
    if closeAfterCatchUp, applicationState == .background, currentCall == nil {
        closeSocket()                     // <<< arka planda çağrı yoksa bağlantı KAPATILIYOR
    }
}
private func enterBackground() {
    guard currentCall == nil, !CallKitManager.shared.hasCalls else { return }
    ... log("Network link held for \(Int(Self.backgroundGrace)) s after leaving the screen")   // 25 sn
}
```
Aktar isteği **aynı soketten** gidiyor:
```swift
private func handleAudioState(_ audio: AudioStateMessage) {
    if audio.detail == "request_iphone_audio" { CallKitManager.shared.takeCallOnIPhone(); return }
```
Ve Android tarafında bu mesajın **Bluetooth yedeği yok** — `audio_state` yalnızca
`GatewayWebSocketServer` üzerinden yayınlanıyor (`answer`/`reject`/`hangup` komutlarının aksine,
onları Bluetooth da taşıyor).

**Yani:** Çağrı henüz Redmi'de olduğu için iPhone'da `currentCall == nil`; iPhone arka planda
olduğu için bağlantıyı **bilerek** kapatıyor; aktar isteği ise **tam o bağlantıdan** gönderiliyor.
Kodun kendi yorumu bu senaryoyu zaten tarif ediyor ("a person who puts the iPhone down to press
'iPhone'a aktar' on the Redmi still finds a connected iPhone") — ama bu yalnızca 25 saniyelik
grace boyunca ve ilk `catchUp` kapanışına kadar geçerli. Sonuç sert bir kilit değil,
**kararsız bir döngü**: aktarım kısa süre başarıyor, sonra bağlantı ölünce ses geri düşüyor.

### Neyin suçlusu DEĞİL (kanıtla)
- **Devir teslim kapısı:** iki kez açıldı, hızlı (632 ms ve 486 ms). Düzeltme çalışıyor.
- **HAL:** tek kesintisiz stream, `pcm_prepare` = 0.
- **AUDIO-STATE-REPEAT:** çalıştı — "Repeated the preparing state (1)(2)(3)" ile isteği her saniye
  yeniden duyurdu; ama bağlantı yokken duyurunun gideceği yer yok.

### AÇIK KALAN (tek)
İki başarılı devir teslimden **0.3 sn ve 2.4 sn sonra** soketin neden yine öldüğü Android
tarafından görülmüyor: iPhone o an çağrıyı almış olduğu için `currentCall != nil` olmalı ve bu
durumda kapanış kuralları devre dışı. Bu, **iOS tarafının logu** ya da çağrı sırasında ekranı açık
bir iPhone ile yeniden üretim gerektiriyor.

### Aday düzeltmeler (hiçbiri kurulmadı — kural 0.4/6.1)
| Yön | Değişiklik | Bedel |
|---|---|---|
| iOS | "Redmi sesi iPhone'a istiyor" durumunu **çağrı yerine koy**: `preparing_iphone`/`request_iphone_audio` geldiğinde `currentCall == nil` olsa da soketi ve arka plan görevini tut | iOS değişikliği + CI (şu an runner yok) |
| iOS | Aktar isteğini **Bluetooth** ile de ilet (kontrol komutlarında bu yol zaten var) | protokol işi, ama soketten bağımsız |
| Android | Bağlantı ölünce sesi hemen Redmi'ye döndürmek yerine kısa bir hoşgörü tanımak | sessizlik riski — 6.1 gereği çağrıyla doğrulanmadan kurulmaz |

### Kurulum disiplini
Kurulum **27 → 27**. Bu bölümün tamamı salt okuma ile toplandı. **Not:** ölçüm sırasında cihaz
USB'den ayrıldı (`adb devices` boş), bu yüzden "iPhone'a ping atılamıyor" gibi ek kontroller
**yapılamadı** — ve yapılamayan kontrol **kanıt sayılmadı** (kural 3.1).
