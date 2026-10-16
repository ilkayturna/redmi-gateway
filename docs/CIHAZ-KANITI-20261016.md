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
