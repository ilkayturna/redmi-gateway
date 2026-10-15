# DEĞİŞMEZ KURALLAR (IMMUTABLE RULES)

Bu dosya, kullanıcının açıkça koyduğu ve **hiçbir geliştirmede bozulmayacak** davranışları listeler.
Her kod değişikliğinden sonra bu senaryolar tek tek doğrulanır.

Son güncelleme: 15.10.2026

---

## KURAL 1 — Kayıtlı ağa otomatik bağlanma (WI-FI AUTO-JOIN)

> Kullanıcı: "Kayıtlı bir ağ gördüğünde Redmi hep otomatik bağlanacak — internetim kolay kolay bitmesin.
> Aynı ağa girdiklerinde bağlantılar devam ediyor; o yapıyı bozma."

- Redmi, **kayıtlı/tanıdık** bir Wi-Fi ağı (ev, iş) gördüğünde **otomatik bağlanır**.
- Bu davranış Android'in kendi Wi-Fi auto-join mekanizmasıdır; uygulama bunu **engellemez, kapatmaz, geciktirmez**.

**Korunacak kod:** `SmartHotspot.kt` — "known network" mantığı, `WifiManager` bağlantı takibi.
**Yasak:** Kayıtlı ağa bağlanmayı önleyen, geciktiren veya Wi-Fi'ı kapatan hiçbir mantık eklenmeyecek.

---

## KURAL 2 — Tanıdık ağa geçince hotspot kapanır (KOTA KORUMASI)

- Redmi tanıdık ağa bağlandığı **anda** mobil hotspot **kapanır**.
- Böylece iPhone da aynı tanıdık ağa geçer ve **mobil veri/kota harcanmaz**.

**Korunacak kod:** `SmartHotspot.kt` (`tryKnownNetwork`, `NET_CAPABILITY_INTERNET/VALIDATED` kontrolü).
**Yasak:** Bu kapanışı engelleyen, geciktiren veya hotspot'u gereksiz açık tutan değişiklik yapılmayacak.

---

## KURAL 3 — Bağlı istemci/aktif çağrı varken hotspot kapanmaz

- **Aktif çağrı varken hotspot ASLA kapanmaz** (çağrı ortasında kopma olmasın). `stopHotspot()` çağrısından hemen önce çağrı durumu yeniden kontrol edilir.
- **Bağlı istemci varken:** çağrı yoksa ve tanıdık ağ menzilde + `wifi_on=1` ise hotspot **kapanır** (kullanıcının kota kuralı önceliklidir). İstemci bağlıyken tarama seyrek yapılır (`CLIENT_SCAN_EVERY_MS = 90 sn`; normal `60 sn`).
- **Tanıdık ağ menzilde ama `wifi_on=0`** (kullanıcı Wi-Fi anahtarını kapatmış) ise hotspot **KORUNUR** — istasyon ayağa kalkamayacağı için hotspot'u düşürmek iPhone'un tek linkini keser, hiçbir şey kazandırmaz. Bu durumda kullanıcıya sürekli bildirimde ipucu gösterilir:
  `Ev/iş ağı "X" menzilde · Wi-Fi'ı aç, iPhone mobil veri harcamasın`
- Uygulama **`setWifiEnabled` ÇAĞIRMAZ** (Kural 1).

---

## KURAL 4 — Aynı ağdayken bağlantı kesintisiz sürer

- İki telefon aynı ağa geçtiğinde ses/çağrı hattı **kopmaz, yeniden kurulmaz**.

**Korunacak kod:** `GatewayWebSocketServer` + `CallAudioRelay` soket sürekliliği, `NsdDiscoveryManager` yeniden kayıt mantığı.

---

## KURAL 5 — iPhone hotspot'a "düşük veri modu" ile bağlanır

- Bu, iPhone tarafındaki kullanıcı ayarıdır; uygulama hotspot üzerinden **gereksiz veri tüketmez**.
- Uygulama arka planda yedekleme/güncelleme amaçlı mobil veri kullanmamalı.

---

## KURAL 6 — Donanım gerçeği: STA + SAP

- Redmi Note 8 (Snapdragon 665 / WCN3980) **sürekli durumda** hem Wi-Fi istemcisi hem hotspot olamaz.
- **Devir teslim sırasında kısa süreli örtüşme mümkündür.** Denetim kanıtı (15.10.2026): 22:42:49'da STA doğrulanmış internetle bağlıyken SAP da `EVENT_IFACE_SERVING_STATE_ACTIVE` ile servis ediyordu; AP, uygulamanın "hotspot off to save mobile data" satırından **112 ms sonra** durdu. Bu örtüşme, `wifi && hotspot && !inCall → stopHotspot` yolunun neden çalışabildiğini açıklar.
- Bu nedenle hotspot açmak için Wi-Fi kapanır (kullanıcının gözlemi doğru); **Wi-Fi anahtarının açık olması** devir teslimin ön koşuludur — uygulama Android 10+ kısıtı nedeniyle Wi-Fi'ı kendisi açamaz.

---

## KURAL 7 — Hotspot taraması Wi-Fi kapalıyken de çalışır

- Bu cihazda hotspot açıkken `WifiManager.isWifiEnabled()` **false** döner (istasyon kapalı). Eski kod bu yüzden tarama yolunu ölü koda çeviriyordu.
- Doğru kapı: `if (!wifi.isWifiEnabled && !isHotspotOn()) return`
- `wifi_scan_always_enabled=1` sayesinde Wi-Fi kapalıyken de **taze tarama sonucu** gelir (kanıt: `Saved network ... in range but Wi-Fi is switched off; hotspot stays` + `ScanResult` yaşının 126 sn → 29 sn'ye düşmesi).
- Kullanıcı ayarını okumak için `Settings.Global "wifi_on"` kullanılır (`isWifiEnabled` bu durumda yanlış cevap verir).

---

## DOĞRULAMA SENARYOSU (her sürümde)

| # | Senaryo | Beklenen |
|---|---|---|
| 1 | Redmi kayıtlı ev ağına girer | Otomatik bağlanır |
| 2 | Bağlanınca hotspot | Kapanır |
| 3 | iPhone aynı ağa geçer | Bağlantı kurulur |
| 4 | Bu sırada çağrı gelir | Kesintisiz taşınır |
| 5 | Redmi ağdan çıkar (dışarı) | Hotspot yeniden açılır, iPhone bağlı kalır |
| 6 | iPhone bağlıyken tanıdık ağ görülür | Hotspot kapanmaz (istemci bağlı) |
| 7 | Çağrı sürerken ağ değişimi | Çağrı düşmez |
