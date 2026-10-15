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

- Bir iPhone istemcisi bağlıysa veya çağrı sürüyorsa hotspot **kapanmaz** (çağrı ortasında kopma olmasın).

**Korunacak kod:** `SmartHotspot.kt` içindeki `if (clientConnected || inCall) return` koruması.

---

## KURAL 4 — Aynı ağdayken bağlantı kesintisiz sürer

- İki telefon aynı ağa geçtiğinde ses/çağrı hattı **kopmaz, yeniden kurulmaz**.

**Korunacak kod:** `GatewayWebSocketServer` + `CallAudioRelay` soket sürekliliği, `NsdDiscoveryManager` yeniden kayıt mantığı.

---

## KURAL 5 — iPhone hotspot'a "düşük veri modu" ile bağlanır

- Bu, iPhone tarafındaki kullanıcı ayarıdır; uygulama hotspot üzerinden **gereksiz veri tüketmez**.
- Uygulama arka planda yedekleme/güncelleme amaçlı mobil veri kullanmamalı.

---

## KURAL 6 — Donanım gerçeği: STA + SAP aynı anda yok

- Redmi Note 8 (Snapdragon 665 / WCN3980) aynı anda hem Wi-Fi istemcisi hem hotspot **olamaz**.
- Bu yüzden hotspot açmak için Wi-Fi kapanır. Bu **kabul edilmiş donanım kısıtıdır**, hata değildir.

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
