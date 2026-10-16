# TUR 109 — DEĞİŞMEZ KURALLAR DOĞRULAMASI (madde 5)

**Tarih:** 16.10.2026 · **Denetlenen commit:** `afd8ad6` · **Yöntem:** statik + bağımsız denetçi (cihaz yok)

Kaynak spec: `RedmiGateway/docs/IMMUTABLE_RULES.md` (7 kural + 7 senaryo)

---

## SONUÇ: 7/7 KORUNUYOR

| # | Kural | Sonuç | Kanıt |
|---|---|---|---|
| **K1** | Kayıtlı ağa otomatik bağlanma; `setWifiEnabled` **yasak** | ✅ **KORUNUYOR** | `SmartHotspot.kt` bu oturumda **0 satır** değişti; tüm uygulamada `setWifiEnabled`/`disableNetwork`/`removeNetwork` **YOK** (grep, 0 eşleşme) |
| **K2** | Tanıdık ağa geçince hotspot **kapanır** (kota koruması) | ✅ **KORUNUYOR** | `NET_CAPABILITY_VALIDATED` (:145), `tryKnownNetwork` (:226), `stopHotspot()` (:287), kapanış yolu (:210-215) |
| **K3** | Aktif çağrıda hotspot kapanmaz; `wifi_on=0` ise korunur | ✅ **KORUNUYOR** | Çağrı kapısı **iki yerde**: `evaluate()` (:210) ve `stopHotspot()` öncesi tekrar (:277). İstemci artık yalnızca kadans (:250), seyrek tarama 90 sn (:44) |
| **K4** | Aynı ağdayken bağlantı kesintisiz sürer | ✅ **statik KORUNUYOR** ⚠️ davranış cihaz ister | `openSocket`/`receiveLoop`/`arm`/`releaseAll`/`disarm` ve `GatewayWebSocketServer`/`NsdDiscovery` **bu oturumda değişmedi** |
| **K5** | iPhone düşük veri modu; arka planda gereksiz veri yok | ✅ **KORUNUYOR** (sayılı) | Aşağıda |
| **K6** | Donanım gerçeği STA+SAP | ✅ **KORUNUYOR** | Kod bağımlılığı yok; doküman doğru (:60-64) |
| **K7** | Wi-Fi kapalıyken de tarama çalışır | ✅ **KORUNUYOR** | Kapı `if (!wifi.isWifiEnabled && !isHotspotOn()) return` (:244); `Settings.Global "wifi_on"` (:305-307) |

---

## K5 — SAYILARLA (kullanıcı "mobil kota koruması" istedi)

| Kalem | Ölçüm |
|---|---|
| Sessiz push gövdesi | **90 bayt** (`{"aps":{"content-available":1},"a":"wake","callId":"<uuid>"}`) — numara/isim **yok** |
| Tel üzerinde (APNs çerçeve + JWT) | ~200-350 bayt |
| Sıklık | **çağrı başına ≤2** (`MAX_PER_CALL = 2`, retry ≥10 sn) ve **yalnızca canlı çağrıda** + **soket yokken** |
| En kötü çağrı başına | **~180 bayt** |
| Beacon | **Mobil veri 0** (23 baytlık BLE reklamı). Maliyet pil/radyo, veri değil |
| Arka plan yedekleme/güncelleme | **YOK** |
| Tek dolaylı trafik | Uyanma sonrası delta `sync` — küçük ve yalnızca uyanma gerektiğinde |

**Sonuç:** Kota koruması sağlam. Eklenen tek arka plan verisi çağrı başına ≤2 küçük push.

---

## K4 — SOKET SÜREKLİLİĞİ (en yüksek riskli, çünkü `CallAudioRelay` değişti)

Denetçinin üç sorusu ve cevapları:

**1. `11bbdae` soket sürekliliğini bozuyor mu? → HAYIR.**
Diff yalnızca kapı değerlendirmesi + teşhis: `GATE_KICK_MS = 60`, `gateMsAtStart`/`gateFramesAtStart`/`lastGateBlocker`/`lastGateKickAt`, `GATE_WAIT`/`GATE_OPEN` logları, metrik satırı, olay-güdümlü kick. **`openSocket`/`receiveLoop`/`arm`/`releaseAll`/`disarm` değişmedi.**

**2. Ağ değişiminde ses oturumu kopar mı? → Hayır.**
İki hotspot kapanış yolu da `!inCall` kapılı (:210, :277) → çağrı sırasında hotspot kapanmaz. Relay soketi ağ değişiminde `arm()` ile yeniden açılır (kod değişmedi).

**3. Yeni `GATE_KICK_MS`/`decideNow` yanlışlıkla `releaseMediaAndKeepSession` tetikler mi? → HAYIR.**
Kick yalnızca `PREPARING_IPHONE && !mediaRunning` iken 60 ms'de bir `tick()` kuyrukluyor. Serbest bırakma koşulu **eskisiyle aynı** (`preparingSince > 8 sn && live`, :595-596) → kick yalnızca bu zaman aşımını ≤60 ms daha erken fark ettirir; **yeni tetik yolu eklemiyor**. Üstelik `releaseMediaAndKeepSession` oturumu **armed bırakır** (soketi kapatmaz); soketi kapatan `releaseAll` çağrıları değişmedi.

---

## DÜZELTİLEN KUSUR (doküman)

`IMMUTABLE_RULES.md` senaryo 6 diyordu: *"iPhone bağlıyken tanıdık ağ görülür → Hotspot kapanmaz (istemci bağlı)"*.
Kural 3'ün **kullanıcı onaylı** metni (:38-41) tam tersini söylüyor: tanıdık ağ menzildeyse **kapanır** (Kural 2 öncelikli), tek istisna `wifi_on=0`.

**Kod her zaman doğru davrandı** — çelişki yalnızca tablodaydı. Ama bu dosyayı okuyup kural uygulayacak kişi **yanlış olanı** uygulardı. Düzeltildi (`3ee40c2`), çelişkinin geçmişi de not edildi.

---

## AÇIK KALANLAR (kapatılmadı, bilinçli)

| # | Konu | Neden bekliyor |
|---|---|---|
| 1 | `tryKnownNetwork` :277 `inCall` kontrolü ↔ `stopHotspot()` arasında **milisaniyelik yarış** | Çağrı yoluna komşu → cihaz testi olmadan değiştirilmemeli (AGENTS.md bölüm 6) |
| 2 | **Beacon pil etkisi ölçülmedi** | Cihaz gerekir. Ölçüm: `dumpsys batterystats` UID payı, aynı uzunlukta iki çağrı |
| 3 | Senaryo 1-5, 7 **cihaz testi** | Gerçek Wi-Fi geçişi + gerçek çağrı gerekir |

---

## CİHAZ GEREKTİREN SENARYOLAR (dönüşte test listesi)

| # | Senaryo | Beklenen | Statik durum |
|---|---|---|---|
| 1 | Redmi kayıtlı ev ağına girer | Otomatik bağlanır | Kod engellemiyor ✅ |
| 2 | Bağlanınca hotspot | Kapanır | Yol mevcut ✅ |
| 3 | iPhone aynı ağa geçer | Bağlantı kurulur | Kanıt yok ⏳ |
| 4 | Bu sırada çağrı gelir | Kesintisiz taşınır | Soket kodu değişmedi ✅ |
| 5 | Redmi ağdan çıkar | Hotspot geri gelir, iPhone bağlı kalır | Yol mevcut ✅ |
| 6 | iPhone bağlıyken tanıdık ağ | **Kapanır** (Kural 2 öncelikli); `wifi_on=0` ise korunur | Kod doğru ✅ |
| 7 | Çağrı sürerken ağ değişimi | Çağrı düşmez | `!inCall` kapıları ✅ |
