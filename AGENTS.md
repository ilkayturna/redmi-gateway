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
