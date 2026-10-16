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

---

## 5. GENEL ÇALIŞMA KURALLARI

1. **Uzun/özel karakterli metin → dosyaya yaz, komuta dosya yolu ver.**
2. **Her turda somut ilerleme:** ya kod, ya ölçüm, ya doğrulama. Boş durum raporu üretme.
3. **Ölçümsüz iddia yok:** "iyileşti" demek için önce/sonra sayısı gerekir.
4. **Bozmama kanıtı:** değişiklikten sonra korunması gereken metrikleri (silence, dropped, maxWriteMs) tekrar oku.
5. **Paralel uzman + bağımsız denetçi:** yazma alanları ayrık; denetçi üretim koduna dokunmaz.
6. **Hata yaptıysan önce buraya yaz**, sonra düzelt — böylece bir daha olmaz.
