# KALICI DERSLER — BU HATALARA BİR DAHA DÜŞÜLMEYECEK

> Bu dosya her oturumda otomatik okunur. Buradaki kurallar **pazarlık konusu değil**.
> Her kural gerçek bir hatadan doğdu; tarih ve kanıtı yanında yazılıdır.
> Yeni bir hata yapıldığında **önce buraya yazılır**, sonra düzeltilir.

---

# ⛔ 0. KULLANICIYA ZARAR VERME — EN ÖNCE BU

> Bu bölüm dosyanın **en başında** durur çünkü buradaki hata **kullanıcıya doğrudan
> zarar verir** ve ben farkına varmadan **saatlerce tekrarlanır**. Diğer kuralların hepsi
> teknik; bu kural **insani**.

## 0.1 Başarısız her CI koşumu kullanıcıya **MAİL** atar

**Hata (16.10.2026):** GitHub Actions runner atamaz hâle geldi (hesap seviyesi kısıt).
Her `git push` `build-android.yml`'i tetikledi → her koşum **5 saniyede çöktü** → GitHub
**depo sahibine hata bildirimi maili** gönderdi. Ben bu sırada **15+ commit** push ettim.
Kullanıcı namazdan döndüğünde **posta kutusu hata maili doluydu.**

**Bunu fark etmedim** çünkü ben mailleri görmüyorum. Kullanıcının gördüğü şeyi
**modelleyemedim.**

**KURAL — bir push yapmadan ÖNCE şunu sor:**
> *"Bu push bir iş akışını tetikler mi? O iş akışı şu an ÇALIŞIYOR mu?"*

1. **Bilinen-bozuk bir iş akışını tetikleyen push YAPMA.** Önce:
   ```
   PUT /repos/{owner}/{repo}/actions/workflows/{id}/disable
   ```
   Doğrula: `state=disabled_manually`. Sonra push et.
2. **Bir kesinti doğrulandıysa TEKRAR DENEMEYİ BIRAK.** Her "bir daha deneyeyim" =
   kullanıcıya **bir mail daha**. Kesinti tespiti **tek** koşumla yapılır, sonra susulur.
3. **Kesinti doğrulanınca ilk iş iş akışlarını devre dışı bırakmaktır** — teşhisi
   bitirmeden önce. Teşhis sürerken her deneme bir maildir.
4. İş akışını **yeniden etkinleştirmeyi unutma** (`PUT .../enable`) — kesinti bitince.
   Devre dışı bırakılan iş akışı sessizce ölüdür; CI'nın çalıştığını sanırsın.

## 0.2 Kullanıcının gördüğü yan etkileri modelle

**Genel kural:** Benim çalıştırdığım her komutun **kullanıcıya görünen** bir yan etkisi
olabilir: mail, bildirim, ses, pil, veri, para (CI dakikası). Bir işlemi tekrarlamadan önce
**"kullanıcı bunu kaç kez görür?"** diye sor.

| İşlem | Kullanıcıya görünen |
|---|---|
| Başarısız CI koşumu | **E-posta bildirimi** |
| Başarılı CI koşumu | (genelde sessiz, ayara bağlı) |
| TestFlight yüklemesi | Bildirim + beta uyarısı |
| Cihaza kurulum | Telefon ekranında değişiklik |
| APK derleme tekrarı | (yerel, görünmez) |

## 0.3 "Farkında değildim" mazeret değil — kontrol et

Bir şeyi **göremiyorsam** (mail kutusu, telefon ekranı, kullanıcının oturumu), o şeyin
**benim yüzümden nasıl göründüğünü** başka yoldan kontrol et:
```powershell
# Kac kosum BASARISIZ oldu (her biri bir mail olabilir)
$runs.workflow_runs | Where-Object { $_.conclusion -eq "failure" } | Measure-Object
# Kullanici kac bildirim almis olabilir
Invoke-RestMethod "https://api.github.com/repos/$repo/actions/runs?per_page=100" -Headers $h |
  Select-Object -ExpandProperty workflow_runs |
  Group-Object conclusion | ForEach-Object { "$($_.Name): $($_.Count)" }
```

**KURAL:** Uzun bir oturumda **periyodik olarak** "kaç başarısız koşum ürettim?" say.
Sayı 3'ü geçtiyse **dur ve sebebini bul** — kullanıcı o sayıda mail almış olabilir.

---

---

# ⛔ 0.4 CİHAZA DOKUNMADAN ÖNCE — BEŞ SORU (ZORUNLU ÖN KONTROL)

> Bu bölüm 16.10.2026'da **kendi davranış hatamdan** doğdu. Kural yazmak yetmedi;
> **her adımdan önce okumak** gerektiği için burada, en başta duruyor.

## Hata
Kullanıcı **çalışan** bir uygulama verdi. Bugün cihaza **8 kez**, toplam **26 kez** APK kurdum.
Ses **00:31–10:33 arası 6 kez çalıştı**; **10:39'daki kurulumdan sonra bir daha çalışmadı.**
Kullanıcının şikayeti *"iPhone'a hiç akmamaya başladı"* — **ve ben sebebi oldum.**

Üstelik **kendi yazdığım kuralları çiğnedim:**
- **6.1** *"ses yoluna dokunan değişikliği doğrulamadan gönderme"* → `AUDIO-STATE-REPEAT`'i
  doğrulamadan kurdum (tur 171).
- **0.2** *"kullanıcının gördüğü yan etkiyi modelle"* → 26 kurulumun **26 kez telefonda
  göründüğünü** modellemedim.
- **6.2** *"tek build'de tek risk"* → üst üste farklı değişiklikler kurdum, hangisinin ne
  yaptığı **ayrıştırılamaz** hâle geldi.

## KURAL — cihaza yazmadan (install / am start / settings / pm) ÖNCE beş soruyu YANITLA

1. **Bu kurulum gerçekten gerekli mi?** Kurulu sürüm bu işi yapamıyor mu?
   → *Yeni APK, yeni `MY_PACKAGE_REPLACED`, yeni süreç, sıfırlanan durum.*
2. **Kullanıcı bunu kaç kez görecek?** (0.2) Telefonunda kaç kez değişiklik olur?
3. **Kurduğum şey neyi değiştiriyor — tek bir şey mi?** (6.2)
   → Birden fazla risk varsa **kurma**; ayır.
4. **Ses yoluna dokunuyor mu?** (6.1) Dokunuyorsa **gerçek çağrı doğrulaması olmadan kurma.**
5. **Kurulumdan sonra NE ÖLÇECEĞİM ve o ölçüm ŞU AN mümkün mü?**
   → Çağrı yokken ses değişikliği kurmak = **doğrulanamayacak bir riski cihaza bırakmak.**

**Beşinin hepsi net değilse: KURMA. Kodu yaz, derle, test et — kurulumu doğrulama
mümkün olana kadar beklet.**

## Ve: kurulum sayısını SAY
```
adb shell "grep -c 'MY_PACKAGE_REPLACED' <tanilama dosyasi>"
```
**Bu sayı bir teşhis aracıdır.** Yüksekse, cihazdaki durum **benim kurulumlarımın** ürünüdür —
ve "uygulama bozuk" demek yerine **önce kendi izimi** okumam gerekir.

## Ve: kurulum sayısı bir **BERAAT** aracıdır

Kullanıcı *"bozdun mu?"* diye sorduğunda **tartışma değil kanıt** gerekir. Sıra:
1. **Kendi izini göster:** kurulum sayısı + **son kurulumun saati** (olaydan ne kadar önce).
2. **Platformun kendi gerekçesini oku:** olayın nedeni çoğu zaman sistem logunda **açıkça** yazılıdır.
3. Ancak ikisinden sonra uygulamaya bak.

**Örnek (16.10.2026 21:14):** Kullanıcı *"telefon çaldı, sadece cevapsız çağrı bildirimi geldi —
naptın bozdun mu?"* dedi. Kanıt: kurulum **27**, son kurulum **16:04:40** (olaydan **5 saat 10 dakika
önce**) ve neden sistemde yazılıydı:
```
ImsReasonInfo :: {510 : CODE_USER_TERMINATED_BY_REMOTE, 0, user ends call}
DisconnectCause [ Code: (MISSED) ... Reason: (INCOMING_MISSED) ]
```
Yani **arayan taraf çağrıyı 112 ms'de kapatmıştı**; uygulama doğru davrandı (cevapsız bildirimini de
o yazdı). **Ders:** "Bozdun mu?" sorusunun cevabı savunma değil, **iki okuma**dır: kendi izin +
platformun gerekçesi.

## 0.5 adb Wi-Fi üzerinden bağlıyken Wi-Fi'a DOKUNMA

**Hata (25.10.2026 10:51):** adb Wi-Fi üzerinden (`192.168.1.111:5555`) bağlıydı. Hotspot süresini
ölçmek için cihazda kendini geri alan bir komut başlattım:
`nohup sh -c "svc wifi disable; sleep 40; svc wifi enable" &`. `adb shell` oturumu kapanınca
**arka plandaki süreç öldürüldü** (`/data/local/tmp/wifitest.txt` içinde yalnızca `start` satırı
vardı). Wi-Fi kapalı kaldı, Redmi ağdan düştü ve **kullanıcı telefonu eline alıp Wi-Fi'ı açmak
zorunda kaldı.** `teslim-kapisi.ps1 -Hotspot` de aynı hatayı yapar: `finally` içindeki
`svc wifi enable` Wi-Fi kapalıyken cihaza ulaşamaz.

**KURAL:**
1. adb Wi-Fi üzerindeyken `svc wifi disable`, `cmd wifi set-wifi-enabled disabled`, ağ ayarı
   değişikliği **YAPILMAZ.** Bu testler yalnızca **USB** ile yapılır.
2. `adb shell` ile başlatılan `nohup ... &` sürecine **güvenilmez**; oturum bitince ölebilir.
3. Kendini geri alması gereken her cihaz işleminin geri alma adımı, bağlantı koptuğunda da
   çalışacağı **kanıtlanmadan** başlatılmaz.
4. **İş bitince adb Wi-Fi bağlantısı KESİLİR** (`adb disconnect <ip>:5555`). Bağlı kalan adb sunucusu
   (PC 192.168.1.114) TCP keepalive ile Redmi'yi ~1,3 sn'de bir uyandırdı: 25.10.2026 10:16'dan
   itibaren saatte 2.400+ uyanma, evde boşta tüketim ~2 saatte %1'den **~32 dakikada %1'e** çıktı
   (4 kat). Kullanıcı bunu "pil çabuk bitiyor" diye yaşar. Varsayılan bağlantı USB'dir.

## 0.6 Kurulumdan ve test aramasından ÖNCE: telefonda arama VAR MI — iki SIM yuvası birden

**Hata (25.10.2026 15:05):** 2.0.10'u kurmadan önce `dumpsys telephony.registry | grep -m1 mCallState`
ile "arama yok" dedim. `-m1` **ilk** telefonu (boş SIM yuvası 1) okudu; SIM yuva 2'deydi ve orada
15:01'den beri **gerçek bir görüşme** sürüyordu (ses iPhone'da). Kurulum süreci yeniden başlattı, üstüne
532 test aramasını açtım; kullanıcının görüşmesi 15:05:37'de `ERROR` ile **düştü**, 15:06:33'te
geri arayan kişi bekleyen çağrıda kalıp **cevapsız** oldu.

**KURAL:** Cihaza yazan her işlemden (install, test araması, ayar) hemen önce:
```
adb -s <seri> shell 'dumpsys telecom | sed -n "/^  mCalls:/,/^  mCallAudioManager/p"'
```
Liste **boş** değilse işlem **yapılmaz**. Ek kanıt: tanılama dosyasının son satırlarında
`RELAY_HEALTH owner=IPHONE` varsa görüşme sürüyordur. `mCallState` okunacaksa **tüm** satırlar okunur.
Komut **hiç çıktı vermezse** bu "arama yok" değil **"bilinmiyor"**dur: işlem yapılmaz, kontrol tekrarlanır
(25.10.2026 15:09'da betik boş döndü ve boş "0" sayıldı; o an şans eseri arama yoktu).

**Kurulum yalnızca `RedmiGateway/scripts/device/guvenli-kur.ps1` ile yapılır.** Arama varken, durum
okunamazken ya da birden fazla cihaz bağlıyken kurmaz, kurduktan sonra sürüm, izin, süreç ve rolü
doğrular. 29.10.2026 13:51'de kontrol satırı ACTIVE arama gösterdiği halde aynı komut zincirindeki
`adb install` çalıştı: arama ROM'un telefon uygulamasına düştü, 84 çökme oldu, ses iPhone'a dönmedi.

## Genel ders
**Not almak = düzeltmek değildir.** Kural, **kullanılmadıkça** hiçbir şeyi engellemez.
Bu yüzden bu bölüm dosyanın **en başında**, kural 0'ın içinde duruyor: her oturumda,
her mesajda, **her cihaz işleminden önce** okunur.

## 1. KABUK (PowerShell) — EN SIK ZAMAN KAYBI

### 1.1 `<` ve `>` kabuk operatörleridir — metin içinde kullanma
**Hata (16.10.2026):**
```powershell
git commit -m "fix: CallKit refused ... <code> ..."   # ❌
# → The '<' operator is reserved for future use.
```
**KURAL:** Commit mesajı, JSON, kod parçası, uzun metin — **ASLA `-m "..."` ile satır içi verilmez.**
Önce `write` ile dosyaya yaz, sonra `-F` / `--file` / `-File` ile kullan:
```powershell
write → $env:TEMP\rg-commit-msgN.txt
git commit -F "$env:TEMP\rg-commit-msgN.txt"          # ✅
```

### 1.2 `dumpsys ... | grep` stderr gürültüsü
`adb.exe : Failed to write while dumping service power: Broken pipe` — zararsız ama çıktıyı kirletir ve `[exit code: 1]` üretir.
**KURAL:** `2>$null` ekle. Çıkış kodunu hata sanma.

### 1.3 `Invoke-WebRequest` NonInteractive modda ölür
**Hata:** `Windows PowerShell is in NonInteractive mode. Read and Prompt functionality is not available.`
**KURAL:** **`Invoke-RestMethod`** kullan. İkili veri (zip/log) indirirken `-OutFile` ile `Invoke-WebRequest` şart ise `-UseBasicParsing` ekle.

### 1.4 `git credential fill` her seferinde token ister
**KURAL:** Token'ı **bir kez** al, `$tok` değişkeninde tut, tüm isteklerde `$h` header hashtable'ını kullan.

### 1.5 Türkçe karakterler çıktıda bozulur
`SaaÖ`, `GÃ¶rÃ¼ÅŸme` gibi. Bu bir hata değil, konsol kod sayfası.
**KURAL:** Log içeriğini karşılaştırırken ASCII bekle; bozulmayı hata sanıp yeni teşhis üretme. Gerekirse `-match 'G.r.me'` gibi esnek desen kullan.

### 1.6 Çok adımlı işleri tek `pwsh` çağrısında topla
Her `pwsh` çağrısı yeni süreç. Değişkenler taşınmaz.
**KURAL:** İlgili adımlar (token al → push → build tetikle) **tek çağrıda** yapılmalı.

### 1.7 BOM'suz UTF-8 PowerShell betiği Windows PowerShell 5.1'de **HİÇ ÇALIŞMAZ**
**Hata (16.10.2026):** `capture-call-diagnostics.ps1` ve `analyze-call-diagnostics.ps1` **BOM'suz
UTF-8** kaydedilmişti ve içlerinde Türkçe karakter + **em dash** (`—`) vardı.
`powershell` (Windows PowerShell **5.1**) BOM'suz dosyaları **ANSI (CP1252)** okur:
```
em dash "—"  UTF-8: E2 80 94   →   CP1252: â € ”
                                          ^^^ U+201D SAĞ AKILLI TIRNAK
```
PowerShell **akıllı tırnakları string sonlandırıcı sayar** → string erken kapanır →
```
The string is missing the terminator: ".
```
→ **betik hiç çalışmaz.** `pwsh` (PowerShell 7) aynı dosyayı UTF-8 okur ve **sorunsuz çalıştırır**
→ **hata ortama bağlıdır ve yalnızca `pwsh` ile test edilirse görünmez.**

**KURAL:**
1. İçinde **ASCII-dışı karakter** (Türkçe, em dash, akıllı tırnak) olan her `.ps1`
   **UTF-8 BOM ile** kaydedilir:
   ```powershell
   $t = [IO.File]::ReadAllText($f, [Text.Encoding]::UTF8)
   [IO.File]::WriteAllText($f, $t, (New-Object System.Text.UTF8Encoding($true)))   # ✅ BOM ekler
   ```
2. Betik metninde **em dash `—`, en dash `–`, akıllı tırnak `“ ” ‘ ’` KULLANMA** —
   düz `-` ve `"` kullan. Bozulduklarında **tırnak üretirler.**
3. Betiği **`powershell` (5.1) ile de çalıştır**, sadece `pwsh` ile değil. Kullanıcı
   `powershell -File ...` çağırıyor.
4. `Parser::ParseFile` ile sözdizimi taraması yapıyorsan **BOM'lu kopyayla** doğrula —
   BOM'suz dosyada **yanlış sonuç** verir (0 hata diyebilir veya olmayan hata uydurabilir).
5. Tarama: `BOM yok + ASCII-dışı var` → şüpheli. Özellikle **em dash** sayısına bak.

### 1.8 `Set-Content -Encoding UTF8` Windows PowerShell 5.1'de **BOM EKLER** (ters yön)
**Hata (16.10.2026):** iOS varlık kataloğu için `Contents.json` üretildi:
```powershell
Set-Content -Path "$set\Contents.json" -Value $contents -Encoding UTF8   # ❌ BOM ekler
```
Sonuç: dosya `EF BB BF` ile başlıyor → `json.load` **"Unexpected UTF-8 BOM"** ile reddediyor.
Xcode'un varlık ayrıştırıcısı da takılabilir.

**Referans:** aynı katalogdaki **çalışan** `AppIcon/Contents.json` **BOM'suz**. Yani doğru olan
zaten projede vardı; sapma bendeydi.

**KURAL:**
1. **Veri dosyaları** (JSON, XML, plist, csv, .gitattributes) **BOM'suz** yazılır:
   ```powershell
   $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
   [IO.File]::WriteAllText($path, $text, $utf8NoBom)                        # ✅
   ```
2. **`Set-Content`/`Out-File` `-Encoding UTF8` KULLANMA** — 5.1'de BOM ekler. Ya `[IO.File]` ya
   da `-Encoding utf8NoBOM` (yalnızca PS 7).
3. `.ps1` betikleri ise **BOM'LU** olmalı (kural 1.7) — **iki yön ters**:
   | Dosya | BOM |
   |---|---|
   | `.ps1` (betik) | **OLMALI** (Türkçe/ASCII-dışı varsa) |
   | `.json` / `.plist` / `.xml` (veri) | **OLMAMALI** |
4. **Referans kuralı:** bir dosyayı üretmeden önce **aynı projede çalışan eşdeğerini** aç ve
   baytlarını karşılaştır (`[IO.File]::ReadAllBytes($f)[0..2]`). Çalışan örnek varsa **odur.**
5. **Doğrulama:** `json.load` / `plistlib` gibi **gerçek ayrıştırıcıyla** aç. Ham metni gözle
   okumak BOM'u göstermez.

---

## 2. SWIFT / iOS

### 2.1 Optional `Error`'ı doğrudan `NSError`'a cast etme
**Hata (16.10.2026, iOS build #82 ve #84 iki tur kırmızı kaldı):**
```swift
(error as NSError).code                       // ❌
// error: '(any Error)?' is not convertible to 'NSError'
```
**KURAL:**
```swift
let code = error.map { ($0 as NSError).code } ?? -1   // ✅
```

### 2.2 Yeni `.swift` dosyası → Xcode projesine **4 YER**
**Hata (16.10.2026, iOS #82):** `BLEReassembly.swift` dosyası yazıldı, `swiftc` testi **geçti**, ama `Build for iOS Simulator` **kırıldı** ("cannot find BLEFrameAssembler in scope").
**KURAL:** `RedmiGateway.xcodeproj/project.pbxproj` içinde **dört** yeri de yaz:
1. `PBXBuildFile` bölümü → `ID /* X.swift in Sources */ = {isa = PBXBuildFile; fileRef = ID2 /* X.swift */; };`
2. `PBXFileReference` bölümü → `ID2 /* X.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = X.swift; sourceTree = "<group>"; };`
3. İlgili `PBXGroup` `children` listesi
4. `PBXSourcesBuildPhase` `files` listesi

**Boş ID doğrula:** `Select-String -Pattern "\b1024\b"` → 0 sonuç vermeli.
**Kural:** `swiftc` ile tek dosya derlemesi geçse bile **Xcode derlemesi ayrı doğrulanır.**

### 2.3 Sessiz push (content-available) için `remote-notification` ŞART
**Hata (16.10.2026):** Uyanma özelliği için `ApnsSender.sendBackground` (Redmi) + `didReceiveRemoteNotification` (iOS) yazıldı, **84+ test geçti, build yeşil oldu** — ama `Info.plist`'teki `UIBackgroundModes` yalnızca `voip`, `audio`, `bluetooth-central` içeriyordu. **`remote-notification` yoktu.**
Sonuç: iOS push'u **teslim eder ama askıdaki uygulamayı uyandırmaz** → handler hiç çalışmaz → özellik **sessizce ölü**. Ne derleme ne test bunu yakalar.

**KURAL — iOS'ta "push ile uyandırma" özelliği yazdığında ŞU ZİNCİRİN TAMAMINI doğrula:**
```
1. Entitlements:      aps-environment = production (TestFlight) | development (debug)
2. Info.plist:        UIBackgroundModes içinde remote-notification  ← EN SIK ATLANAN
3. Kod:               application.registerForRemoteNotifications()
4. Kod:               didRegisterForRemoteNotificationsWithDeviceToken → token'ı karşı tarafa gönder
5. Kod:               didReceiveRemoteNotification:fetchCompletionHandler:
6. Gönderen:          apns-push-type: background + apns-priority: 5 + content-available: 1
7. Token tipi:        sessiz push NORMAL APNs token'ına gider; VoIP token'ında Apple yalnızca
                      `.voip` topic kabul eder → sessiz push oraya GÖNDERİLEMEZ
```
**Genel ders:** Bir özellik "kodda tamam" olabilir ama **platform beyanı** (plist/entitlement/manifest) eksikse ölüdür. Derleme ve test bunu **görmez**. Yeni bir platform yeteneği kullanıyorsan (push, arka plan modu, izin, servis) **beyan dosyalarını ayrıca aç ve oku** — "derlendi" kanıt değildir.

---

## 3. DOĞRULAMA DİSİPLİNİ

### 3.1 Komut çıktısını sayı sanma
**Hata:** Nöbetçi betiği ADB hata metnini sayı olarak aldı → taban 176357.
**KURAL:** `if ($raw -match '^\d+$')` ile doğrula, değilse yok say.

### 3.2 Tarih/saat çevirisi yapma
**Hata:** Log damgası elle çevrildi → "yeni çağrı var ama logda yok" sanrısı, boşa teşhis.
**KURAL:** Log'daki damgayı **olduğu gibi** kullan (`grep '10:33'`). Çeviri gerekiyorsa cihazın kendi saatini oku.

### 3.3 CI ≠ yerel
CI eski bir commit'i test edebilir. Test sayısı tutmuyorsa (72 vs 74) **`head_sha`'yı kontrol et**.
**KURAL:** CI hatası gördüğünde önce "hangi commit?" sorusunu sor.

### 3.4 "Derlendi" ≠ "çalışıyor"
**KURAL:** Her değişiklik için sırayla: **derle → test → cihaza kur → log ile doğrula → ekran görüntüsü.**

### 3.5 Dormant özelliği "çalışıyor" sayma
FEC bayrağı kapalıyken "FEC eklendi" demek yanıltıcı. **Bayrağın durumunu her raporda yaz.**

---

## 4. GITHUB İŞ AKIŞI (bu ağda)

### 4.1 `git push` engelli
`github.com:443` bu ağda bloklu.
**KURAL:** **Git Data API** kullan:
```
POST /git/blobs (base64)  →  POST /git/trees (base_tree)  →  POST /git/commits (parents)  →  PATCH /git/refs/heads/<branch>
```
Yerel commit yine atılır (kayıt için), ama uzak güncelleme API ile yapılır.

### 4.2 İki repo senkron
`redmi-gateway` (ana) + `redmi-gateway-archive` (arşiv/loglar).
**KURAL:** Her push **ikisine** de yapılır.

### 4.3 `workflow_dispatch` parametrelerini OKUMADAN tetikleme
**Hata (16.10.2026):** iOS workflow'u `ref` ile tetiklendi, "build 88 çalışıyor" denildi ve kullanıcıdan TestFlight'ta görünmesi beklendi. Ama `build-ios.yml`'de:
```yaml
testflight:
  if: github.event_name == 'workflow_dispatch' && inputs.release == true
```
`release=true` verilmediği için **yalnızca `compile` job'u çalıştı**, TestFlight'a **hiçbir şey yüklenmedi**. Ortada "build 88" diye bir şey hiç var olmadı; boşuna beklendi.

**KURAL:** `workflow_dispatch` ile tetiklemeden **önce** workflow dosyasını aç ve `inputs.*` koşullarını oku. Zorunlu girdileri (`required: true`) **eksiksiz** gönder:
```powershell
$body = @{ ref = "<branch>"; inputs = @{ release = "true"; promote_build = "" } } | ConvertTo-Json -Depth 4 -Compress
```
**TestFlight'a build düşmüyorsa sırayla kontrol et:**
1. `release=true` gönderildi mi? → `testflight` job'u çalıştı mı (`Sign and upload` = success)
2. Build numarası ne? → `CFBundleVersion = $GITHUB_RUN_NUMBER` (run numarası, sabit değil)
3. Sürüm ne? → `CFBundleShortVersionString` (TestFlight'ta **bu sürümün altında** aranır)
4. Gruba eklendi mi? → `promote_build=<N>` ile `promote_existing` job'unu tetikle
5. `ITSAppUsesNonExemptEncryption` var mı? → yoksa Apple build'i "Missing Compliance"da tutar
6. `UIBackgroundModes` / entitlement beyanları → **madde 2.3**

---

## 5. GENEL ÇALIŞMA KURALLARI

1. **Uzun/özel karakterli metin → dosyaya yaz, komuta dosya yolu ver.**
2. **Her turda somut ilerleme:** ya kod, ya ölçüm, ya doğrulama. Boş durum raporu üretme.
3. **Ölçümsüz iddia yok:** "iyileşti" demek için önce/sonra sayısı gerekir.
4. **Bozmama kanıtı:** değişiklikten sonra korunması gereken metrikleri (silence, dropped, maxWriteMs) tekrar oku.
5. **Paralel uzman + bağımsız denetçi:** yazma alanları ayrık; denetçi üretim koduna dokunmaz.
6. **Hata yaptıysan önce buraya yaz**, sonra düzelt — böylece bir daha olmaz.

---

## 6. SÜRÜM DİSİPLİNİ — SES YOLU KUTSALDIR

> Bu bölüm 16.10.2026'da **sesi tamamen kesen** bir regresyondan doğdu. Kullanıcı
> telefonlarını yanına alıp gitmek zorunda kaldı. Bu, ödenebilecek en pahalı bedel.

### 6.1 Ses yoluna dokunan değişiklik, cihazda GERÇEK ÇAĞRI testi olmadan gönderilmez
**Hata (16.10.2026):** `firstPcm` gecikmesini düzeltmek için motor ayrımı
(`startLocal` / `attachNetwork`) yapıldı. 84+ test geçti, CI yeşil oldu, TestFlight'a gitti
— **ve ses tamamen kesildi** (*"iPhone'a hiç akmamaya başladı"*).
Bağımsız denetçi diff'ten **4 sessizlik yolu** buldu. En güçlüsü:
`startLocal` içindeki **4 saniyelik zamanlayıcı** motoru durduruyor → sonra gelen
`attachNetwork`, **sessiz** bir `guard running, !attached else { return }` içinde düşüyor →
`audio_start` **arm başına bir kez** geldiği için bir daha denenmiyor → **ses yok**.
Aktarım 4 sn'yi aşıyor (uyanma 1-3 sn + kurulum + arm gecikmesi), yani yol **deterministik**.

**KURAL — şu dosyalara dokunan her değişiklik:**
`AudioEngine.swift`, `CallKitManager.swift`, `CallAudioTiming.swift`,
`CallAudioRelay.kt`, `UplinkJitterBuffer.kt`, `CallGatewayInCallService.kt`
1. **Derleme + test YETMEZ.** Cihazda **gerçek çağrı** ile denenir.
2. **Sessiz çıkış yolu bırakma:** `guard ... else { return }` **log basmalı**. Sessiz düşen
   bir guard, "ses yok"un tek açıklaması olur ve teşhis edilemez.
3. **Kendini durduran zamanlayıcı yasak:** bir kaynağı zamanlayıcıyla kapatıp, sonra gelen
   isteği sessizce reddetmek = kalıcı sessizlik. Kapatma **idempotent ve tekrar denenebilir** olmalı.

### 6.2 Tek build'de tek risk — yoksa regresyon diğerini gizler
**Hata (aynı gün):** Uyanma düzeltmesi (`remote-notification`) ile ses optimizasyonu
**aynı build'de** gitti. Ses kesilince *"uyanma çalışıyor mu?"* sorusu **cevapsız kaldı**.
**KURAL:** Bir build'de **tek davranış değişikliği**. Ses + ağ + UI + uyanma ayrı build'lerde gider.

### 6.3 Geri alma (revert), düzeltmeye çalışmaktan ÖNCE gelir
**KURAL:** Kullanıcı "çalışmıyor" dediğinde **önce bilinen-çalışan sürüme dön**, sonra teşhis et.
Kullanıcının cihazı üzerinde hata ayıklanmaz.
Geri alma **birebir** olmalı ve **bağımsız denetçiyle** doğrulanmalı:
```
git diff <bilinen-iyi-commit> <revert-commit> -- <dosyalar>   →  0 satır olmalı
```
Ayrıca kaldırılan sembollerin repo genelinde **hiç kalmadığını** grep'le doğrula
(`startLocal`, `attachNetwork`, `earlyStartToken`, …).

### 6.4 Ölçüm tanımı değişirse "iyileşti" denemez
**Hata (aynı gün):** `firstPcm` metriği `arm()` anından ölçülüyordu; ayrım `arm()`'ı kurulumun
**sonrasına** taşıdı → sayı 592 → 40-70 ms "düştü" ama bunun bir kısmı **tanım kayması**.
**KURAL:** Bir metriğin **ölçüm başlangıcı/noktası** değiştiyse, önce/sonra sayıları
karşılaştırılamaz. **Kullanıcının hissettiği** uçtan uca metriği (`take on iPhone requested →
firstSend`) esas al ve **iki metriği birlikte** raporla.

### 6.5 "Hiç çalışmıyor" → ÖNCE ortam/ön koşul, SONRA kod
**Hata (16.10.2026):** Kullanıcı *"iPhone'a hiç akmamaya başladı"* dedi. Ben **doğrudan koda**
baktım, motor ayrımını suçlu buldum ve geri aldım. Doğru bir refleksti (6.3) — **ama**
`RedmiGateway/docs/HANDOVER.md:37` zaten şöyle diyordu:
> *"iPhone audio does not work without the fix. … **A ROM update can replace `/vendor`**;
> run `status` after any system update."*

Ve `vendor_audio_fix.py` kök nedeni ölçmüş: bu ROM, cihazın kullandığı **SIM slot 2** için
`USECASE_INCALL_MUSIC_UPLINK2`'yi eşlemiyor → her yazma `pcm_prepare` hatası veriyor → HAL
stream'i **saniyede ~14 kez** yeniden başlatıyor → **"helikopter" sesi** (= kullanıcının
"cızırtı" dediği şey) ve **düzeltme olmadan iPhone sesi HİÇ çalışmıyor.**
ROM güncellemesi `/vendor`'u değiştirirse düzeltme **sessizce kaybolur**.

Yani sesin hiç akmamasının sebebi **vendor düzeltmesi** olabilirdi; o durumda **hiçbir kod
değişikliği** sesi geri getirmezdi ve saatler yanlış yerde harcanırdı.

**KURAL — bir özellik "hiç çalışmıyor"sa, koda bakmadan ÖNCE şu sırayı işlet:**
1. **Cihaz/ortam ön koşulu** var mı? (vendor yaması, root, izin, ROM sürümü, servis ayakta mı)
2. **Uygulama kendi kendini raporluyor mu?** (bu projede: tanılama `vendorFix` alanı, Ayarlar
   ekranı "iPhone ses kanalı (SIM 2)" satırı, `RELAY_HEALTH` sayıları)
3. **Belirti koda mı ait?** Cızırtı + `pcm_prepare` hatası = **ortam**, kod değil.
4. **Ancak bundan sonra** diff'lere ve sessizlik yollarına bak.

**Genel ders:** "Derlendi + testler yeşil" ile "cihazda çalışıyor" arasında **ortam** vardır.
Ve bir özellik "tamamen ölü" ise, sebep genellikle **ikili** bir şeydir (var/yok), ince bir
zamanlama değil. İkili şeyleri **önce** ele.

---

### 6.6 "Hızlandırma" değişikliği sesi tamamen kesebilir — sıfırdan başlayan örnekleme kilidi

**Hata (16.10.2026, İKİNCİ kez ses kesildi):** Devir teslim kapısı **ömür boyu ortalama** hız
kullanıyordu ve çalışıyordu (10:33'teki çağrı `AUDIO_OWNER=IPHONE` ile bitti). "Kapı 1431 ms
bekliyor, son saniye zaten 53/s" gerekçesiyle **pencere tabanlı** örneklemeye geçildi
(`GATE_SAMPLE_MS = 140L`). Kuruldu ve **ses bir daha iPhone'a geçmedi.**

**Kök neden — saf kilitlenme:**
```kotlin
private var gateSampleAt = 0L                                    // sifirdan baslar
val sampleMs = if (gateSampleAt == 0L) 0L else now - gateSampleAt // 0 iken ZORLA 0
val recentRate = if (sampleMs >= GATE_SAMPLE_MS) ... else 0       // 0 >= 140 -> hep 0
if (sampleMs >= GATE_SAMPLE_MS) {
    gateSampleAt = now                                            // >>> HIC CALISMAZ <<<
}
```
**`gateSampleAt`'ı atayan koşul, `gateSampleAt` sıfırken `sampleMs`'e bağlı. Ve `sampleMs`
sıfırken koşul yanlış. Yani kendini besleyen bir kilit: hiç açılmaz.** Her `arm()` sıfırladığı
için **her çağrıda** kilitleniyordu.

**Belirti:** `GATE_OPEN` yok · `startMedia()` hiç çağrılmıyor · 8 sn sonra
`iphone_audio_not_ready` · **log'da hiçbir sebep yok.**

**KURAL:**
1. **Zamanlayıcı/örnekleme durumu asla "sıfır = başlatılmadı" ile "sıfır = geçen süre"yi
   karıştırmasın.** İlk geçişte origin'i **açıkça** ata:
   ```kotlin
   if (gateSampleAt == 0L) { gateSampleAt = now; gateSampleFrames = framesNow }
   ```
   ya da **hiç durum tutma** — ortalamayı kullan (bu, kanıtlanmış çalışan hâldi).
2. **"Yavaş" bir yolu "hızlandırmak" ses yolunda en pahalı değişikliktir.** 6.1 zaten
   söylüyor: gerçek çağrı olmadan gönderilmez. **Bu hata o kuralın çiğnenmesiyle oldu.**
3. **Bir metrik "kötü" görünüyorsa önce ÖLÇ, sonra değiştir.** `gateMs=1431 ms` rahatsız
   ediciydi ama **çalışıyordu**; değişiklik onu **sonsuza** çevirdi.
4. **Belirti "hiç çalışmıyor" ise (ikili), sebep genellikle bir MANTIK kilididir** — ince
   zamanlama değil. Kodu **okuyarak** bulunur: bir koşulun hiç doğru olamayacağını görmek
   yeterliydi.
5. **Geri alma kanıtı:** kurulu APK'da ölü sabitin **olmadığını** doğrula
   (`b'GATE_SAMPLE_MS' in dex` → False).

**DOĞRULAMA — kapatıldı (16.10.2026 16:06, cihaz, gerçek çağrı, kurulum 27):**
```
16:06:21.361  AUDIO_OWNER=PREPARING_IPHONE reason=waiting_for_iphone_audio
16:06:22.383  Call audio media started; verifying before handing over (gate=431ms frames=21 rate=50/s)
16:06:22.796  AUDIO_OWNER=IPHONE reason=iphone_confirmed
16:06:22.797  Call audio moved to iPhone (uplink=50/s downlink=43/s firstPcm=524ms startMedia=955ms gateMs=380 ...)
```
- Kapı **açıldı** (10:39'dan sonra ilk kez), ses **iPhone'a geçti** — kullanıcı da doğruladı.
- `startMedia = 955 ms` · 10:33'te **2023 ms**. **Aynı metrik** (kural 6.4 ihlali yok):
  `firstPcm + gate` → 592+1431=2023 ✓ ve 524+431=955 ✓.
- HAL kanıtı: `usecase(51: incall_music_uplink2)` **tek kesintisiz stream** olarak açıldı
  (16:06:22.332 → 16:06:30.208) ve tamponda **`pcm_prepare` eşleşmesi 0** → 6.5'teki
  "helikopter" imzası **yok** (vendor düzeltmesi çalışıyor).

### 6.7 Tek çağrı "çalışıyor"u kanıtlar, "hedefi tuttum"u kanıtlamaz

**Gözlem (aynı çağrılar):** `gateMs` çağrıdan çağrıya **değişiyor** — 1431 ms (10:33, iPhone
14/s ile başladı) vs 380-431 ms (16:06, iPhone 50/s ile başladı). Yani `<1 sn` hedefi bu
çağrıda tuttu; **her çağrıda tutacağı kanıtlanmadı.** Kapı sabit değil, **iPhone'un sesi ne
kadar hızlı başlarsa** o kadar hızlı açılıyor.

**Sonuç (16.10.2026, düzeltme sonrası n=2 — kural işe yaradı):**

| Çağrı | firstPcm | gate | startMedia | hedef <1 sn |
|---|---|---|---|---|
| 16:06 | 524 ms | 431 ms | **955 ms** | ✓ |
| 16:15 | 505 ms | 683 ms | **1236 ms** | ✗ |

Yani **min 955 · ort 1096 · max 1236 ms** → hedef **bir çağrıda tuttu, bir çağrıda tutmadı.**
Sabit olan `firstPcm` (505-524 ms, iPhone motoru); değişen **kapının beklediği süre**. Eğer
16:06'daki tek ölçümle "hedefi tuttum" denseydi, **yanlış bir rapor** verilmiş olurdu.

**KURAL:**
1. Bir gecikme hedefi için **en az 3 çağrı** ölç ve **dağılımı** yaz (min / ort / max).
   Tek ölçümü "hedefi tuttum" diye raporlamak, 6.4'ün kardeşi bir hatadır.
2. Çağrı **çok kısaysa** (konuşma penceresi < ~5 sn) devir teslim **sonrası** `RELAY_HEALTH`
   satırı hiç yazılmaz → `underruns/dropped/silence` **ölçülemez** ve ses kalitesi maddesi
   **kapanmaz**. "Ses geçti" ile "ses temiz" **ayrı** iki iddiadır; ikincisi için ≥5 sn konuşma
   gerekir.
3. **Kapsamayan tamponda yokluk kanıt değildir (3.1'in log hâli):** cihaz logcat tamponu bu
   oturumda 15:33:55'te başlıyordu; 10:33 çağrısı tamponda **yoktu**. "Eski çağrıda bu satır
   yoktu" demek için **tamponun o pencereyi kapsadığını** önce doğrula.

### 6.8 Tavan ile ihtiyaç formülü çakışırsa kayıp "yapısal" olur — sayıyı koddan çıkar

**Bulgu (16.10.2026, madde 4 — cızırtı/kesilme):** İki çağrının da devir teslim **sonrası**
sağlık satırları okundu. HAL temizdi (`pcm_prepare`=0, stream tek ve kesintisiz), kuyruk temizdi
(`dropped`=0, `expired`=0, `partial`=0, `maxWriteMs`=1 ms) — **ama** `underruns` 5.1 saniyede
12 → 28 çıktı: 16 çerçeve (320 ms) gerçek ses yerine gizleme ile dolduruldu = **~%6**.
10:33'teki çağrıda aynı ölçüm **%1.6**'ydı.

Fark ağın geliş desenindeydi: `maxAuthenticatedArrivalGapMs` **197-204 ms** (10:33'te 52 ms),
`maxAuthenticatedArrivalBurstFrames` **7-8** (10:33'te 3).

Ve tampon tavanı bunu karşılayamıyor:
```kotlin
const val LOCAL_TARGET_CEILING = 12                      // 12 x 20 ms = 240 ms (yerel link)
val gapFrames = ((gapMs - FRAME_MS + FRAME_MS - 1) / FRAME_MS)   // ceil((gap-20)/20)
val need = maxOf(gapFrames, burst) + 1
needFrames[needIndex] = minOf(need, ceiling())           // >>> TAVANDA KIRPILIYOR <<<
```
`need = gapFrames + 1` ve tavan 12 olduğu için **240 ms'den büyük her geliş boşluğu yapısal
olarak karşılanamaz** (241 ms → gapFrames 12 → need 13 → 12'ye kırpılır). Bugünkü ağın en kötü
boşluğu (197-204 ms) tavanın **~40 ms altında**, yani tampon sürekli sınırda çalışıyor ve her
ekstra jitter'da kuru kalıyor (`queued=0`).

**KURAL:**
1. "Ses bozuk" dendiğinde **hangi katman olduğunu ayır**: HAL (`pcm_prepare`, stream tekrarı) ·
   kuyruk (`dropped`, `expired`) · yazıcı (`maxWriteMs`) · **ağ deseni (`gap`, `burst`)**.
   Dördü de temizse kalan şey **tamponun matematiksel sınırıdır** — kod hatası değil.
2. **Ölçüyü tavana çevir:** `need = ceil((gap-20)/20)+1` ile `ceiling`'i karşılaştır. Ağın en
   kötü boşluğu tavana yaklaşıyorsa o link için tampon **tasarım gereği** yetersizdir; bu, test
   yazmakla değil ancak **tavanı ya da link sınıflandırmasını** değiştirmekle düzelir.
3. Böyle bir değişiklik **ses yoludur** (6.1): gerçek çağrıyla doğrulanmadan kurulmaz ve bedeli
   (gecikme 240 → 320-480 ms) **kullanıcıya söylenir**. "Sessizce iyileştirme" yok.

### 6.9 Kontrol mesajı, karşı tarafın "kapatmaya karar verdiği" kanaldan gidiyorsa salınım olur

**Bulgu (16.10.2026 16:23, "iPhone'a aktar"):** Kullanıcı Redmi'den arama yaptı ve aktar dedi.
21 saniyelik çağrıda **ses sahipliği 6 kez el değiştirdi**: iki kez iPhone'a geçti (632 ms ve
486 ms), 0.3 sn ve 2.4 sn sonra geri düştü, son denemede kapı hiç açılmadı
(`GATE_WAIT reason=rateLow ... rate=35/s`) ve **kullanıcı beklemekten vazgeçip kapattı.**

Log: soket **32 saniyede 9 kez** açıldı; her kapanış `code=1006` (el sıkışmasız ölüm) ve
**`sinceLastMessageMs ≈ sessionMs`** — yani soket açıldıktan sonra neredeyse hiç mesaj gelmedi,
sonra öldü. Android tarafında keepalive/idle timeout **yok**; her ölüm
`AUDIO_OWNER=REDMI reason=iphone_disconnected` üretip ses oturumunu kapatıyor.

Kök neden koddan okundu:
```swift
// iOS: arka planda çağrı yoksa bağlantı BİLEREK kapatılıyor (25 sn grace sonra / catchUp sonrası)
if closeAfterCatchUp, applicationState == .background, currentCall == nil { closeSocket() }
```
Ama aktar isteği **aynı soketten** gidiyor (`audio_state` → `request_iphone_audio`), ve bu mesajın
**Bluetooth yedeği yok** (oysa `answer`/`reject`/`hangup` komutlarını Bluetooth da taşıyor).
Çağrı henüz Redmi'de olduğu için iPhone'da `currentCall == nil` → iPhone bağlantıyı kapatıyor →
istek teslim edilemiyor → ses Redmi'de kalıyor → tekrar deneniyor. **Kararsız döngü.**

**KURAL:**
1. **Gönderim koşulu ile kapanma koşulu çakışmasın.** Bir kontrol mesajı, karşı tarafın
   *"şu durumda kapatırım"* dediği kanaldan gidiyorsa ve mesajın amacı **tam o durumu değiştirmekse**,
   kilitlenme/kararsızlık kaçınılmazdır. (Burada: "çağrım yok → kapat" ↔ "çağrıyı devral".)
2. **Kritik komut tek kanala bağlanmaz.** Yedek kanal varsa (burada Bluetooth) kritik mesaj
   oradan da gider. Yedek yoksa, o mesaj için **bir yedek yazmak** işin parçasıdır.
3. **"Gitti geldi / takıldı" belirtisi = sahiplik salınımıdır.** Teşhis sırası:
   `AUDIO_OWNER` geçişlerini **say** → kanal kapanışlarını **oku** (`code=1006` +
   `sinceLastMessageMs ≈ sessionMs` = peer susuyor, ağ ya da askıya alınma) → **uygulama mantığını
   en son** suçla. Bu vakada kapı, HAL ve ses yolu temizdi; suçlu bağlantı politikasıydı.

### 6.10 Yeniden deneme döngüsü TEK UÇUŞ olmalı — paralel denemeler birbirini bozar

**Hata (22.10 build 114 ve 25.10.2026 11:52 build 139):** iPhone'dan cevaplanan gelen aramada
`didActivate`, `audio_ready`'den önce geldi; ilk `AudioEngine.start` girişi 0 Hz gördü. Build 117'deki
"20 kez 150 ms'de yeniden dene" düzeltmesi tek döngü varsaymıştı; oysa her `audio_ready`, her çağrı
olayı ve her yeniden deneme **kendi** döngüsünü başlattı: **11 döngü** aynı anda, her biri önce motoru
durdurup ses işlemeyi yeniden açtı ve giriş arama boyunca hiç format vermedi. Ses Redmi'de kaldı.
Telemetrideki imza: aynı `attempt N/20` satırının **aynı saniyede 11 kez** görünmesi.

**KURAL:**
1. Bir kaynağı "hazır olana kadar" yeniden deneyen her yol **tek uçuş**tur: bekleyen bir deneme
   varken gelen yeni istek **yeni döngü açmaz**, bekleyenin hedefini günceller.
2. Yeniden deneme her adımda kaynağı yıkıp kuruyorsa (stop + yeniden yapılandırma), paralel iki
   deneme **kesinlikle** birbirini bozar. Döngü sayısını telemetride say.
3. Sorun yereldeyken (iPhone mikrofonu) karşı tarafa tekrar istek gönderme: Redmi her `audio_start`'ta
   yeni oturum açar ve bekleyen deneme hareketli bir hedefi kovalar.

---

## 7. OTOMASYON VE BAYAT YOLLAR — SESSİZ GERİ ALMA

> Bu bölüm 16.10.2026'da, **mevcut tüm düzeltmeleri sessizce geri alabilecek** bir betiğin
> bulunmasıyla doğdu. Cihaz kullanıcıdaydı; o an bağlı olsaydı kurulum **başarılı olurdu**.

### 7.1 Bayat yol = sessizce ESKİ sürümü kurar
**Hata (16.10.2026):** `watch_and_configure.py` ve `autonomous_deployer.py` tüm yolları
`C:\Users\ilkay.turna\Documents\antigravity\elegant-borg\` govdesine işaret ediyordu —
**başka bir checkout**. O dizin **hâlâ duruyor** ve içinde **26.09.2026 tarihli** `app-debug.apk` var.

`watch_and_configure.py` **sonsuz döngüde** çalışıp gateway'i göremediğinde o APK'yi kuruyordu:
```python
while not (gateway_configured and magisk_configured):     # SONSUZ
    ...
    run([ADB, "install", "-r", r"...\elegant-borg\...\app-debug.apk"])   # 3 HAFTA ESKİ
```
**Sonuç olsaydı:** uyanma push'u, kapı loglaması, 86 test — **hepsi giderdi** ve belirti
*"uyanma çalışmıyor"* gibi görünürdü → **yanlış katmana geri dönüş.**

**KURAL:**
1. Bir betik **kuruyorsa/yazıyorsa**, hedef yolu **çalışma zamanında bu depodan** çözsün
   (`os.path.dirname(__file__)` temelli), sabit mutlak yol **yazmasın**.
2. Kurmadan **önce ne kurduğunu ve kaç günlük olduğunu yazsın**.
3. `> 7 gün` ise **uyarsın**; yol eski bir checkout'a işaret ediyorsa **REDDETSİN**.
4. **Sonsuz `while` yok** — adım bütçesi olsun, reddedilen yolu tekrar denemesin.

### 7.2 `if __name__ == "__main__"` koruması olmayan modül IMPORT EDİLEMEZ
**Hata (aynı gün, kendi hatam):** `watch_and_configure.py`'nin **tüm gövdesi en üst seviyede**ydi,
main guard'ı yoktu. Guard'ı test etmek için modülü **import ettim** → **sonsuz döngü başladı ve
APK kurmaya başladı.** (Cihaz bağlı olmadığı için kurulum başarısız oldu; zarar yok.)

**KURAL:**
1. Yan etki üreten her betiğin gövdesi **`def main():`** içinde olsun ve
   **`if __name__ == "__main__": main()`** ile çağrılsın. **İstisnasız.**
2. Bir betiği import ederek test edeceksen, **önce** import'un yan etkisiz olduğunu
   **zaman aşımıyla** doğrula:
   ```powershell
   $job = Start-Job { python -c "import modul; print('IMPORT-OK')" }
   if (-not (Wait-Job $job -Timeout 30)) { Stop-Job $job; "*** IMPORT YAN ETKI URETIYOR ***" }
   ```
3. Cihaza yazan bir betiği çalıştırmadan önce **çalışmadığından emin ol** ve **ne yazacağını**
   önce oku.

### 7.3 Aynı sınıf: cihazda çalışan izleyiciler
`device_watcher_daemon.py` ve `nightly_autonomous_worker.py` **sonsuz döngülü** izleyicilerdir
(ikincisi `fastboot devices` + **okuma amaçlı** `getvar unlocked` — kilit açmıyor, flash etmiyor).
Kullanıcı yokken bunların **çalışmadığı** doğrulanmalı:
```powershell
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match "betik_adi" }
schtasks /query /fo LIST | Select-String "ilgili"
```
**KURAL:** Cihaza dokunabilecek bir otomasyon bırakılıyorsa, bırakıldığı anda **çalışmadığı**
kanıtlanır (süreç + zamanlanmış görev + başlangıç klasörü).

---

## 8. LOG SATIRI — DÖNGÜ İÇİNDEKİ KAYIT, DÖNGÜ HIZINDA YAZAR

> Bu bölüm 16.10.2026'da **kendi eklediğim bir satırın** cihazda bir dakika içinde kusurunu
> göstermesinden doğdu.

### 8.1 Değerlendirme döngüsü içine kayıt koymadan önce "bu kaç kez çalışır?" diye sor

**Hata (16.10.2026):** `SmartHotspot` kararını tanılama dosyasına yazmak için `TRY_KNOWN_NETWORK`
dalına bir `DiagnosticsLog.record` eklendi. **Değerlendirme her 20 saniyede çalışıyor**, durum ise
genelde **değişmemiş** oluyor. Sonuç, cihazda **bir dakika içinde** görüldü:

```
15:32:13.150  Wi-Fi lost; trying a known network...
15:32:33.206  Wi-Fi lost; trying a known network...
15:32:53.227  Wi-Fi lost; trying a known network...
```

Tanılama dosyası **4000 satırda döngüye giriyor**; dakikada 3 aynı satır **gerçek geçmişi itiyor.**
Yani bozulan şey sadece gürültü değil — **teşhis edilebilirliğin kendisi.**

**KURAL:**
1. Bir döngü içine kayıt koyacaksan, **karar/ durum DEĞİŞTİĞİNDE** yaz:
   ```kotlin
   private fun recordDecision(action: Action, message: String) {
       Log.i(TAG, message)                        // logcat: her gecis (orasi dongu icin)
       if (action == lastRecordedAction) return   // dosya: yalnizca degisim
       lastRecordedAction = action
       DiagnosticsLog.record(TAG, message)
   }
   ```
2. **Logcat ile dosya farklı işler:** logcat döngüyü gösterebilir (döner, sorun değil), **dosya
   geçmişi tutar** — oraya yalnızca **geçiş** yazılır.
3. **Kural 0.2 log için de geçerli:** "kullanıcı bunu kaç kez görür?" → "dosya bunu kaç kez yazar,
   neyi iter?"
4. **Doğrulaması cihazda yapılır ve hızlıdır:** sayacı 2 dakika izle — **artmıyorsa** doğru.
   ```
   dosya  : 6 -> 6      (120 sn, ARTMADI)  ✅
   logcat : 10 satir    (devam ediyor)     ✅
   ```

**Genel ders:** Log eklemek "zararsız" görünür; ama **döngü içinde** eklendiğinde dosyanın
**rotasyonunu** hızlandırır ve **tanılamayı yok eder** — yani eklediğin şeyin amacını tersine
çevirir.

