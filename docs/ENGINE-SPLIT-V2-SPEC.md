# MOTOR AYRIMI v2 — UYGULAMA SPEC'I

> Bu, sesi kıran `7055cb0` denemesinin **düzeltilmiş** hâlidir.
> Bağımsız denetçi (qa-adversarial) `7055cb0` diff'inden **4 sessizlik yolu** buldu.
> Aşağıdaki 6 şart **hepsi** karşılanmadan bu kod ana dala alınmaz.

## NEDEN: ölçülmüş bütçe

```
firstPcm      592 ms   (iPhone: ilk PCM karesi)      <- ASIL DARBOGAZ
gate tabanı  >=240 ms  (kanıt standardı 12 kare)
settle        454 ms   (Android doğrulama penceresi)
----------------------
TABAN       = 1286 ms  > 1000 ms   (gateMs=0 olsa bile)
```

Motor kurulumu `arm()` **sonrasına serili**; hiçbir şeyle örtüşmüyor.
Kurulum `arm()` öncesine alınırsa: 1286 - ~500 = **~780 ms < 1000 ms** ✅

## DENETÇİNİN BULDUĞU 4 SESSİZLİK YOLU (v1'in ölüm sebebi)

**M1 (en güçlü, deterministik):** `startLocal` içindeki 4 sn'lik zamanlayıcı `self.stop()`
çağırıyor; `attachNetwork` girişi ise **sessiz** `guard running, !attached else { return }`.
Aktarım 4 sn'yi aşarsa → motor durur → attach sessizce düşer → `audio_ready` arm başına
**bir kez** geldiği için bir daha denenmez → **ses yok.**

**M2:** `player.play()` yalnızca `attachNetwork`'e taşındı → attach atlanırsa **aşağı yön hiç başlamaz.**

**M3:** endpoint doğrulaması `attachNetwork`'e taşındı → `early: false` yolunda grafik kurulup motor
başladıktan sonra doğrulama düşerse **mikrofon açık, ağ yok, temizlik yok.**

**M4:** `takeCallOnIPhone` erken motoru `audioActivated` iken başlatıyor, ama attach
`isAudioEligible(uuid)` kapısından geçiyor; pickup'ta bu kapı false → erken motor asla attach edilemez.

## 6 ŞART (hepsi zorunlu)

1. **Zamanlayıcıyla kendini durdurma YASAK.** Erken motor kendini öldürmesin.
   Kaynak temizliği `stop()`/`disarm`/çağrı bitişi ile yapılsın — zamanlayıcıyla değil.
   *Gerekçe:* M1. Zamanlayıcı + sessiz guard = kalıcı sessizlik.
2. **`attachNetwork` idempotent ve TEKRAR DENENEBİLİR olsun.** İkinci/üçüncü `audio_ready`
   veya geç gelen attach sessizce düşmesin; **log bassın** ve mümkünse bağlanabilsin.
   *Gerekçe:* M1. `guard ... else { return }` **sessiz olamaz** (AGENTS.md 6.1/2).
3. **`player.play()` attach'e bağlı OLMASIN.** Aşağı yön grafik hazır olur olmaz başlasın.
   *Gerekçe:* M2. Tek bir attach aksaklığı tam sessizliğe dönüşmesin.
4. **Endpoint doğrulaması grafik kurulmadan ÖNCE yapılsın.** Geçersiz endpoint'te motor hiç
   başlamasın → mikrofon açık kalmasın.
   *Gerekçe:* M3.
5. **Erken motoru başlatan kapı ile attach kapısı AYNI olsun.** `audioActivated` ile başlatıp
   `isAudioEligible` ile attach etmek = asla buluşamayan iki kapı.
   *Gerekçe:* M4.
6. **`firstPcm` tanımı karşılaştırılabilir kalsın** (AGENTS.md 6.4). `arm()`'ın nereye
   taşındığını raporda yaz; **uçtan uca** metriği (`take on iPhone requested -> firstSend`)
   esas al ve **iki metriği birlikte** raporla.

## KABUL KRİTERLERİ (cihaz testi)

| Metrik | Eski | Hedef |
|---|---|---|
| `take on iPhone requested -> firstSend` (asıl) | ~1000 ms | **<=500 ms** |
| `firstPcm` | 592 ms | 40-80 ms (tanım kaymasını raporla) |
| `localReady` | — | ~400-500 ms, **kritik yolda olduğu kanıtlansın** |
| Toplam geçiş | 2504 ms | **<1000 ms** |
| `silence` / `gap` / `dropped` / `maxWriteMs` | 0-1/s / 52 ms / 0 / <=5 | **KORUNMALI** |
| FEC | dormant | **dormant** |

## ZORUNLU CİHAZ TESTİ (AGENTS.md 6.1)

Bu değişiklik **cihazda gerçek çağrı** ile denenmeden `codex/audio-jitter-fix`'e alınmaz.
Test senaryoları:
1. Redmi'den giden çağrı, iPhone uykuda → uyanma + ses akışı
2. iPhone'dan "sesi aktar" → geçiş süresi ölçümü
3. Çağrı sırasında ağ değişimi (Kural 4) → ses kopmuyor
4. Uyanma 4 sn'yi aşan senaryo (M1'in tetiklendiği durum) → **ses yine de akmali**
5. Pickup senaryosu (M4) → ses akıyor

---

## KAPSAM NOTU (16.10.2026) — pacer dokunuşu v2'DEN ÇIKARILDI

v1'de `CallAudioTiming.swift`'e `beginImmediately()` eklenmişti ("ilk kare rezervi beklemesin",
~60 ms kazanç). Bu, **motor ayrımından bağımsız** bir davranış değişikliğidir.

**AGENTS.md 6.2 (tek build'de tek risk) gereği v2'den çıkarıldı.** v2 artık **yalnızca motor
ayrımı**. Ölçüm bütçesi onsuz da hedefi karşılıyor:

```
firstPcm (rezervli)  ~140 ms
gate tabanı           240 ms
settle                454 ms
------------------------------
TOPLAM               ~834 ms  < 1000 ms  ✅
```

Pacer dokunuşu istenirse **ayrı bir değişiklik** olarak, kendi cihaz testiyle yapılır
(~60 ms daha kazandırır ama ayrı doğrulanmalıdır).

`discardPending()` kalıyor: eski/kalan kareleri atar, davranış değişikliği değil.
