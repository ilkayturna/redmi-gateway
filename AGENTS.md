# KALICI DERSLER — BU HATALARA BİR DAHA DÜŞÜLMEYECEK

> Bu dosya her oturumda otomatik okunur. Buradaki kurallar **pazarlık konusu değil**.
> Her kural gerçek bir hatadan doğdu; tarih ve kanıtı yanında yazılıdır.
> Yeni bir hata yapıldığında **önce buraya yazılır**, sonra düzeltilir.

---

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
