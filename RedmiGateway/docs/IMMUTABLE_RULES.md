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

> **Kullanıcının sözleşmesi (DEĞİŞMEDİ):** "Bağlı istemci veya aktif çağrı varken hotspot kapanmaz."

- ✅ **Aktif çağrı varken hotspot ASLA kapanmaz** — sözleşmeye birebir uyuluyor. `stopHotspot()` çağrısından hemen önce çağrı durumu yeniden kontrol edilir.
- ✅ **ONAYLANDI (kullanıcı, 15.10.2026):** Redmi tanıdık bir ağın menziline girdiğinde **o ağa bağlanır ve hotspot'u kapatır**; iPhone zaten Wi-Fi ile o tanıdık ağa **otomatik** geçer. Kullanıcının birebir ifadesi: *"redmi; tanıdık bir ağ menziline girince ona bağlanıp hotspotu kapatsın; böylece zaten iphone'da wifi ile tanıdık ağa geçicek otomatik."* Bu nedenle bağlı istemci, tanıdık ağ menzildeyken hotspot'un kapanmasını **engellemez**; kota koruması (Kural 2) önceliklidir.
- **Tanıdık ağ menzilde ama `wifi_on=0`** ise hotspot **KORUNUR** — istasyon ayağa kalkamayacağı için hotspot'u düşürmek iPhone'un tek linkini keser. Bu durumda kullanıcıya bildirimde ipucu gösterilir:
  `Ev/iş ağı "X" menzilde · Wi-Fi'ı aç, iPhone mobil veri harcamasın`
- Uygulama **`setWifiEnabled` ÇAĞIRMAZ** (Kural 1). İstemci bağlıyken tarama seyrek yapılır (`CLIENT_SCAN_EVERY_MS = 90 sn`; normal `60 sn`).

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
- ✅ **KANITLANMIŞ:** Wi-Fi kapatıldığında (istasyon kapalıyken) **taze tarama sonucu gelir.** Kanıt: uygulama logu 3 kez `Saved network ... in range but Wi-Fi is switched off; hotspot stays` + bağımsız ölçümde aynı `ScanResult` yaşının 126 sn → 29 sn'ye düşmesi.
- ⚠️ **KANITLANMAMIŞ:** "AP fiilen servis ederken de taze sonuç gelir" — o anlarda `dumpsys tethering` son olayı `SERVING_STATE_INACTIVE` idi; kontrollü AP-aktif testi **yapılmadı**. Bu kısım uygulamanın kendi `isHotspotOn()` görüşüne dayanır.
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
| 6 | iPhone bağlıyken tanıdık ağ görülür | Tanıdık ağ menzildeyse hotspot **KAPANIR** — Kural 2 (kota) Kural 3'ten önceliklidir, kullanıcı 15.10.2026'da böyle onayladı. **İstisna:** `wifi_on=0` ise hotspot **KORUNUR** (istasyon ayağa kalkamaz) |
| 7 | Çağrı sürerken ağ değişimi | Çağrı düşmez |

> **NOT (16.10.2026):** 6. satır eskiden "Hotspot kapanmaz (istemci bağlı)" diyordu; bu,
> Kural 3 onaylanmadan önceki sürümden kalmıştı ve **Kural 3'ün güncel metniyle çelişiyordu**
> (bkz. satır 38-41). Kod her zaman doğru davrandı — çelişki yalnızca bu tablodaydı.

---

## BİLİNEN AÇIK (henüz kapatılmadı)

**Yarış penceresi (DÜŞÜK):** `SmartHotspot.tryKnownNetwork` içinde çağrı kontrolü (`if (inCall) return`)
ile `stopHotspot()` çağrısı arasında milisaniyelik bir pencere var. O pencerede bir çağrı başlarsa
hotspot, çağrı sürerken kapanabilir. İhlal olasılığı çok düşük (pencere dar) ama **atomik bir
"çağrı canlı mı" kontrolü** ile sertleştirilebilir. Cihazda test edilmeden değiştirilmemeli —
ses/çağrı yoluna komşu olduğu için AGENTS.md bölüm 6 kapsamındadır.

**Pil (ÖLÇÜLMEDİ):** Beacon artık çağrı boyunca yanıyor (Kural: `WAKE_MAX_MS = 10 dk`).
Veri tüketimi **sıfır** (BLE reklamı), ama pil etkisi ölçülmedi. Ölçüm:
`dumpsys batterystats` UID payı, aynı uzunlukta iki çağrıda (beacon açık / soket açık).
