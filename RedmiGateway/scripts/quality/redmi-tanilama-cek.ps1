# redmi-tanilama-cek.ps1 - Redmi'deki butun tanilama kayitlarini TEK komutla PC'ye ceker ve ozetler.
#
# NEDEN VAR: Kullanici 29.10.2026'da "iPhone'dan bir daha log yapistirmak istemiyorum" dedi. iPhone'da
# USB hata ayiklama yok; kendisi hakkinda bildirebildigi her seyi (log satirlari, durum, cokme ve
# takilma raporlari) Redmi'ye gonderir, Redmi de bunlari uygulamanin diagnostics/ klasorune yazar.
# Bu betik o klasorun tamamini (ios-diag/ ve iphone-status.json dahil), pil durumunu ve uygulama
# surumunu tek baglantida ceker ve kisa bir ozet yazar.
#
# KULLANIM:
#   powershell -NoProfile -ExecutionPolicy Bypass -File RedmiGateway\scripts\quality\redmi-tanilama-cek.ps1
#   ... -Adres 192.168.1.142:42109   (mDNS Redmi'yi bulamazsa kablosuz hata ayiklama adresi)
#   ... -Klasor <cekilmis klasor>     (adb yok; daha once cekilmis bir klasoru yeniden ozetler)
#
# SIRA: USB'de yetkili Redmi varsa o kullanilir (yeni baglanti yok). Yoksa zaten acik bir kablosuz
# baglanti aranir, o da yoksa 'adb mdns services' ile _adb-tls-connect kaydi bulunur ve BIR KEZ
# baglanilir.
#
# KURALLAR (AGENTS.md 0.4-0.6):
#   - Yalnizca OKUR: logcat -c, svc wifi, install, settings, pm, am YOK. Cihazda hicbir sey degismez.
#   - Tek baglanti: Redmi her kablosuz baglantida bir bildirim gosterir; tum okumalar ayni baglantida.
#   - Is bitince, hata olsa bile, kablosuz baglanti finally icinde kesilir: bagli kalan adb sunucusu
#     Redmi'yi saatte 2.400 kez uyandirip bosta pili 4 kat hizli bitirdi (25.10.2026). adb sunucusunu
#     bu betik baslattiysa sonda durdurulur; acik kalirsa mDNS Redmi'ye kendiliginden yeniden baglanir.
#
# CIKIS KODU: 0 = cekildi ve ozetlendi, 1 = Redmi bulunamadi ya da okunamadi.

param(
    [string]$Adb = 'C:\Users\ilkay.turna\platform-tools\adb.exe',
    # Redmi'nin USB seri numarasi; kablosuz mDNS adi da bunu icerir (adb-17df6357-xxxxxx).
    [ValidatePattern('^[A-Za-z0-9._-]+$')][string]$UsbSeri = '17df6357',
    [ValidatePattern('^$|^[A-Za-z0-9.:\[\]%-]+:\d{1,5}$')][string]$Adres = '',
    [string]$Hedef = 'C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\logs',
    [string]$Klasor = '',
    [ValidateRange(1, 50)][int]$SonCagri = 5
)

$ErrorActionPreference = 'Stop'
$cihazKlasoru = '/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics'
$script:ozet = New-Object System.Collections.ArrayList
$script:kablosuzSeriler = New-Object System.Collections.ArrayList
$script:sunucuOnceVardi = $true

function Yaz([string]$metin, [string]$renk = 'Gray') {
    [void]$script:ozet.Add($metin)
    Write-Host $metin -ForegroundColor $renk
}

function Kisalt([string]$metin, [int]$uzunluk = 220) {
    if ($metin.Length -le $uzunluk) { return $metin }
    return $metin.Substring(0, $uzunluk) + '...'
}

function Invoke-Adb([string[]]$Argumanlar, [int]$ZamanAsimiMs = 20000) {
    $surec = New-Object System.Diagnostics.Process
    $surec.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $surec.StartInfo.FileName = $Adb
    # Every argument is a constant, the validated serial or address, or a path built here; none
    # holds a quote, and no path ends in a backslash that would escape the closing quote.
    $surec.StartInfo.Arguments = ($Argumanlar | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $surec.StartInfo.UseShellExecute = $false
    $surec.StartInfo.CreateNoWindow = $true
    $surec.StartInfo.RedirectStandardOutput = $true
    $surec.StartInfo.RedirectStandardError = $true
    $surec.StartInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
    $surec.StartInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
    try {
        [void]$surec.Start()
        $cikti = $surec.StandardOutput.ReadToEndAsync()
        $hata = $surec.StandardError.ReadToEndAsync()
        if (-not $surec.WaitForExit($ZamanAsimiMs)) {
            try { $surec.Kill() } catch { }
            return @{ Tamam = $false; Kod = -1; Cikti = ''; Hata = ('zaman asimi ' + [int]($ZamanAsimiMs / 1000) + ' sn') }
        }
        $surec.WaitForExit()
        [void]$cikti.Wait(2000)
        [void]$hata.Wait(2000)
        return @{ Tamam = ($surec.ExitCode -eq 0); Kod = $surec.ExitCode; Cikti = [string]$cikti.Result; Hata = [string]$hata.Result }
    } catch {
        return @{ Tamam = $false; Kod = -1; Cikti = ''; Hata = $_.Exception.Message }
    } finally {
        $surec.Dispose()
    }
}

function Cihaz-Listesi {
    $sonuc = Invoke-Adb @('devices') 15000
    $liste = New-Object System.Collections.ArrayList
    if (-not $sonuc.Tamam) {
        Write-Host ('adb devices okunamadi: ' + ($sonuc.Hata.Trim())) -ForegroundColor Yellow
        return $liste
    }
    foreach ($satir in ($sonuc.Cikti -split "`r?`n")) {
        if ($satir -match '^(\S+)\t(.+?)\s*$') {
            [void]$liste.Add([pscustomobject]@{ Seri = $matches[1]; Durum = $matches[2] })
        }
    }
    return $liste
}

# ip:port (adb connect) and mDNS instance names (adb-<seri>-<id>._adb-tls-connect._tcp) are both
# network transports; teslim-kapisi.ps1 uses the same test for its Wi-Fi guard.
function Kablosuz-Mu([string]$seri) {
    return ($seri.Contains(':') -or $seri -match '_adb-tls-connect|\._tcp')
}

function Redmi-mDNS-Adi-Mi([string]$seri) {
    return ($seri -like ('adb-' + $UsbSeri + '-*'))
}

function Seri-No([string]$seri) {
    $sonuc = Invoke-Adb @('-s', $seri, 'shell', 'getprop', 'ro.serialno') 15000
    if (-not $sonuc.Tamam) { return '' }
    return $sonuc.Cikti.Trim()
}

function Mdns-Ara {
    # Discovery needs a moment after the server starts, so an empty answer is asked again twice.
    $desen = '(?m)^\s*(\S+)\s+_adb-tls-connect\._tcp\.?\s+(\S+:\d+)\s*$'
    $digerleri = @()
    for ($deneme = 1; $deneme -le 3; $deneme++) {
        $sonuc = Invoke-Adb @('mdns', 'services') 10000
        $kayitlar = @()
        foreach ($eslesme in [regex]::Matches($sonuc.Cikti, $desen)) {
            $kayitlar += [pscustomobject]@{ Ad = $eslesme.Groups[1].Value; Adres = $eslesme.Groups[2].Value }
        }
        $redmi = @($kayitlar | Where-Object { Redmi-mDNS-Adi-Mi $_.Ad })
        if ($redmi.Count -gt 0) {
            # IPv4 first: an IPv6 link-local address needs a zone the adb client may not carry.
            $ipv4 = @($redmi | Where-Object { $_.Adres -match '^\d+\.\d+\.\d+\.\d+:\d+$' })
            if ($ipv4.Count -gt 0) { return $ipv4[0] }
            return $redmi[0]
        }
        $digerleri = $kayitlar
        if ($deneme -lt 3) { Start-Sleep -Milliseconds 1500 }
    }
    if ($digerleri.Count -gt 0) {
        Write-Host ('mDNS baska cihazlar buldu (Redmi degil): ' + (($digerleri | ForEach-Object { $_.Ad }) -join ', ')) -ForegroundColor Yellow
    } else {
        $kontrol = Invoke-Adb @('mdns', 'check') 10000
        Write-Host ('mDNS kaydi yok. adb mdns check: ' + (($kontrol.Cikti + $kontrol.Hata).Trim())) -ForegroundColor Yellow
    }
    return $null
}

# Picks the Redmi without opening a connection when one is already there. Returns the serial to use;
# throws with the reason when the Redmi cannot be reached.
function Redmi-Sec {
    $liste = @(Cihaz-Listesi)
    $usb = @($liste | Where-Object { $_.Seri -eq $UsbSeri })
    if ($usb.Count -gt 0 -and $usb[0].Durum -eq 'device') {
        Write-Host ('Redmi USB ile bagli: ' + $UsbSeri) -ForegroundColor Green
        return $UsbSeri
    }

    # An open wireless transport is reused: a new connect would post another notification.
    foreach ($kayit in @($liste | Where-Object { $_.Durum -eq 'device' -and (Kablosuz-Mu $_.Seri) })) {
        $redmiMi = Redmi-mDNS-Adi-Mi $kayit.Seri
        if (-not $redmiMi) { $redmiMi = ((Seri-No $kayit.Seri) -eq $UsbSeri) }
        if ($redmiMi) {
            [void]$script:kablosuzSeriler.Add($kayit.Seri)
            Write-Host ('Redmi zaten kablosuz bagli: ' + $kayit.Seri + ' (yeni baglanti acilmadi)') -ForegroundColor Green
            return $kayit.Seri
        }
    }

    $hedefAdres = $Adres
    if (-not $hedefAdres) {
        $bulunan = Mdns-Ara
        if ($bulunan) {
            $hedefAdres = $bulunan.Adres
            Write-Host ('mDNS: ' + $bulunan.Ad + ' -> ' + $bulunan.Adres)
        }
    }
    if (-not $hedefAdres) {
        $neden = 'USB listesinde yok'
        if ($usb.Count -gt 0) { $neden = 'USB durumu: ' + $usb[0].Durum + ' (Redmi ekranindaki USB hata ayiklama iznini onaylayin)' }
        throw ('Redmi bulunamadi. ' + $neden + '; mDNS kaydi da yok. Redmi''de Kablosuz hata ayiklama acik ve PC ayni agda olmali, ya da -Adres ip:port verin.')
    }

    # The mDNS query above can make a paired adb server connect by itself; reuse that if it did.
    foreach ($kayit in @(Cihaz-Listesi | Where-Object { $_.Durum -eq 'device' -and ((Redmi-mDNS-Adi-Mi $_.Seri) -or $_.Seri -eq $hedefAdres) })) {
        [void]$script:kablosuzSeriler.Add($kayit.Seri)
        Write-Host ('Redmi kendiliginden baglanmis: ' + $kayit.Seri + ' (yeni baglanti acilmadi)') -ForegroundColor Green
        return $kayit.Seri
    }

    [void]$script:kablosuzSeriler.Add($hedefAdres)
    $baglan = Invoke-Adb @('connect', $hedefAdres) 20000
    $yanit = ($baglan.Cikti + ' ' + $baglan.Hata).Trim()
    Write-Host ('adb connect ' + $hedefAdres + ': ' + $yanit)
    $durum = ''
    for ($deneme = 0; $deneme -lt 10; $deneme++) {
        $kayit = @(Cihaz-Listesi | Where-Object { $_.Seri -eq $hedefAdres })
        if ($kayit.Count -gt 0) { $durum = $kayit[0].Durum }
        if ($durum -eq 'device') { return $hedefAdres }
        Start-Sleep -Milliseconds 500
    }
    if (-not $durum) { $durum = 'listede yok' }
    throw ('Kablosuz baglanti kurulamadi (' + $hedefAdres + ', durum: ' + $durum + '). Eslesme gerekiyorsa Redmi''de Kablosuz hata ayiklama > Eslestirme kodu ile cihaz eslestir, sonra adb pair.')
}

function Baglantiyi-Kes {
    # Redmi transports still listed now (an mDNS reconnect included) are closed as well as the
    # ones this run used.
    $kapatilacak = New-Object System.Collections.ArrayList
    foreach ($seri in $script:kablosuzSeriler) { if (-not $kapatilacak.Contains($seri)) { [void]$kapatilacak.Add($seri) } }
    foreach ($kayit in @(Cihaz-Listesi | Where-Object { Redmi-mDNS-Adi-Mi $_.Seri })) {
        if (-not $kapatilacak.Contains($kayit.Seri)) { [void]$kapatilacak.Add($kayit.Seri) }
    }
    foreach ($seri in $kapatilacak) {
        $sonuc = Invoke-Adb @('disconnect', $seri) 10000
        Write-Host ('adb disconnect ' + $seri + ': ' + (($sonuc.Cikti + ' ' + $sonuc.Hata).Trim())) -ForegroundColor DarkGray
    }
    if (-not $script:sunucuOnceVardi) {
        # Started for this run: stopping it also keeps mDNS from reconnecting the Redmi later.
        $dur = Invoke-Adb @('kill-server') 10000
        if ($dur.Tamam) {
            Write-Host 'adb sunucusu durduruldu (bu betik baslatmisti); Redmi''ye bagli adb kalmadi.' -ForegroundColor Green
        } else {
            Write-Host ('UYARI: adb sunucusu durdurulamadi (' + $dur.Hata.Trim() + '). Pil icin elle: adb kill-server') -ForegroundColor Red
        }
        return
    }
    if ($kapatilacak.Count -eq 0) { return }
    Start-Sleep -Seconds 2
    $kalan = @(Cihaz-Listesi | Where-Object { $kapatilacak.Contains($_.Seri) -or (Redmi-mDNS-Adi-Mi $_.Seri) })
    if ($kalan.Count -gt 0) {
        Write-Host ('UYARI: Redmi hala kablosuz bagli (' + (($kalan | ForEach-Object { $_.Seri }) -join ', ') + '). adb sunucusu bu betikten once de calisiyordu ve mDNS ile yeniden baglaniyor olabilir. Pil 4 kat hizli biter; elle: adb kill-server') -ForegroundColor Red
    } else {
        Write-Host 'Kablosuz adb baglantisi kesildi.' -ForegroundColor Green
    }
}

function Satirlar([string]$yol) {
    if (-not (Test-Path -LiteralPath $yol)) { return @() }
    return @([IO.File]::ReadAllLines($yol, [Text.Encoding]::UTF8))
}

function Damga([string]$satir) {
    if ($satir -match '^(\d\d-\d\d \d\d:\d\d:\d\d)') { return $matches[1] }
    return ''
}

# One line per JSON key, nested objects as a.b=..., so iphone-status.json reads whatever keys the
# Redmi version writes.
function Json-Duzlestir($nesne, [string]$onek) {
    $parcalar = New-Object System.Collections.ArrayList
    foreach ($ozellik in $nesne.PSObject.Properties) {
        $ad = $ozellik.Name
        if ($onek) { $ad = $onek + '.' + $ozellik.Name }
        $deger = $ozellik.Value
        if ($deger -is [System.Management.Automation.PSCustomObject]) {
            foreach ($alt in (Json-Duzlestir $deger $ad)) { [void]$parcalar.Add($alt) }
            continue
        }
        if ($deger -is [array]) { $deger = ($deger -join ',') }
        $metin = [string]$deger
        # Epoch milliseconds (receivedAt and the like) also shown as local time.
        if ($ozellik.Name -match 'At$' -and $metin -match '^\d{13}$') {
            $metin += ' (' + [DateTimeOffset]::FromUnixTimeMilliseconds([long]$metin).LocalDateTime.ToString('yyyy-MM-dd HH:mm:ss') + ')'
        }
        [void]$parcalar.Add($ad + '=' + $metin)
    }
    return $parcalar
}

function Ozetle([string]$klasor, [string]$baglanti) {
    $dizin = Join-Path $klasor 'diagnostics'
    if (-not (Test-Path -LiteralPath $dizin)) {
        $bulunan = Get-ChildItem -LiteralPath $klasor -Recurse -File -Include 'call-diagnostics.log', 'iphone-diagnostics.log' -ErrorAction SilentlyContinue | Select-Object -First 1
        $dizin = $klasor
        if ($bulunan) { $dizin = $bulunan.DirectoryName }
    }
    $cagri = Satirlar (Join-Path $dizin 'call-diagnostics.log')
    # The rotated file holds the older half, so it is read first to keep the order.
    $iphone = @(Satirlar (Join-Path $dizin 'iphone-diagnostics.1.log')) + @(Satirlar (Join-Path $dizin 'iphone-diagnostics.log'))

    Write-Host ''
    Yaz '=== REDMI TANILAMA OZETI ===' 'Cyan'
    Yaz ('Klasor   : ' + $klasor)
    if ($baglanti) { Yaz ('Baglanti : ' + $baglanti) }
    Yaz ('Dosyalar : call-diagnostics ' + $cagri.Count + ' satir, iphone-diagnostics ' + $iphone.Count + ' satir')

    # ---- Redmi
    $surum = Satirlar (Join-Path $klasor 'uygulama-surumu.txt')
    if ($surum.Count -gt 0) {
        Yaz ('Redmi    : ' + (($surum | Where-Object { $_ -match '^(versionName|versionCode|lastUpdateTime)=' }) -join '  '))
    } else {
        Yaz 'Redmi    : uygulama surumu bu klasorde yok' 'Yellow'
    }
    $pilMetni = (Satirlar (Join-Path $klasor 'pil.txt')) -join "`n"
    if ($pilMetni -match '(?m)^\s*level:\s*(\d+)') {
        $seviye = $matches[1]
        $durumKodu = ''
        if ($pilMetni -match '(?m)^\s*status:\s*(\d+)') { $durumKodu = $matches[1] }
        $durumAdi = @{ '1' = 'bilinmiyor'; '2' = 'sarj oluyor'; '3' = 'desarj'; '4' = 'sarj olmuyor'; '5' = 'dolu' }[$durumKodu]
        if (-not $durumAdi) { $durumAdi = 'durum ' + $durumKodu }
        $sicaklik = ''
        if ($pilMetni -match '(?m)^\s*temperature:\s*(\d+)') { $sicaklik = ', ' + ([double]$matches[1] / 10).ToString('0.0', [Globalization.CultureInfo]::InvariantCulture) + ' C' }
        $renk = 'Gray'
        if ([int]$seviye -le 20 -and $durumKodu -ne '2') { $renk = 'Red' }
        Yaz ('Pil      : %' + $seviye + ', ' + $durumAdi + $sicaklik) $renk
    } else {
        Yaz 'Pil      : pil.txt yok' 'Yellow'
    }

    # ---- iPhone
    Yaz ''
    Yaz '--- iPhone ---' 'Cyan'
    $bagli = @($iphone | Where-Object { $_ -match 'linked app=\S+ build=\S+' } | Select-Object -Last 1)
    if ($bagli.Count -gt 0 -and $bagli[0] -match 'linked app=(\S+) build=(\S+)') {
        Yaz ('Uygulama : ' + $matches[1] + ' (' + $matches[2] + '), son baglanti ' + (Damga $bagli[0]))
    }
    $durumSatiri = @($iphone | Where-Object { $_ -match ' iPhone-status: ' } | Select-Object -Last 1)
    if ($durumSatiri.Count -gt 0) {
        Yaz ('Durum    : ' + (Kisalt ($durumSatiri[0] -replace '^(\S+ \S+) iPhone-status: ', '$1  ') 400))
    } else {
        Yaz 'Durum    : iPhone-status satiri yok' 'Yellow'
    }
    $durumJson = Join-Path $dizin 'iphone-status.json'
    if (Test-Path -LiteralPath $durumJson) {
        try {
            $nesne = [IO.File]::ReadAllText($durumJson, [Text.Encoding]::UTF8) | ConvertFrom-Json
            Yaz ('Son durum (iphone-status.json): ' + (Kisalt ((Json-Duzlestir $nesne '') -join ' ') 600))
        } catch {
            Yaz ('iphone-status.json okunamadi: ' + $_.Exception.Message) 'Yellow'
        }
    }

    $uyariDesen = 'Voice processing could not be enabled|no IO cycle|Previous run crashed|iPhone-diag'
    $uyarilar = @($iphone | Where-Object { $_ -match $uyariDesen })
    if ($uyarilar.Count -gt 0) {
        $sayilar = @()
        foreach ($ad in @('Voice processing could not be enabled', 'no IO cycle', 'Previous run crashed', 'iPhone-diag')) {
            $adet = @($uyarilar | Where-Object { $_ -match [regex]::Escape($ad) }).Count
            if ($adet -gt 0) { $sayilar += ($ad + '=' + $adet) }
        }
        Yaz ('Uyarilar : ' + ($sayilar -join ', ') + ' (son 8 satir)') 'Yellow'
        foreach ($satir in ($uyarilar | Select-Object -Last 8)) { Yaz ('  ' + (Kisalt $satir)) 'Yellow' }
    } else {
        Yaz 'Uyarilar : VP / IO cycle / cokme / iPhone-diag satiri yok' 'Green'
    }
    $raporDizini = Join-Path $dizin 'ios-diag'
    if (Test-Path -LiteralPath $raporDizini) {
        $raporlar = @(Get-ChildItem -LiteralPath $raporDizini -File | Sort-Object LastWriteTime)
        Yaz ('ios-diag : ' + $raporlar.Count + ' dosya')
        foreach ($rapor in ($raporlar | Select-Object -Last 5)) {
            Yaz ('  ' + $rapor.LastWriteTime.ToString('MM-dd HH:mm') + '  ' + $rapor.Name + '  ' + $rapor.Length + ' bayt')
        }
    }

    # ---- Calls
    Yaz ''
    Yaz ('--- Son ' + $SonCagri + ' ses aktarimi (Call audio moved to iPhone) ---') 'Cyan'
    $aktarimlar = @($cagri | Where-Object { $_ -match 'Call audio moved to iPhone' } | Select-Object -Last $SonCagri)
    if ($aktarimlar.Count -eq 0) { Yaz '  kayit yok' }
    foreach ($satir in $aktarimlar) {
        $alanlar = @()
        $toplam = 0
        $tamToplam = $true
        foreach ($anahtar in @('startMedia', 'settle')) {
            if ($satir -match ('\b' + $anahtar + '=(-?\d+)ms')) { $alanlar += ($anahtar + '=' + $matches[1] + 'ms'); $toplam += [int]$matches[1] } else { $tamToplam = $false }
        }
        if ($satir -match '\bfirstPcm=(-?\d+)ms') { $alanlar += ('firstPcm=' + $matches[1] + 'ms') }
        if ($satir -match '\bgateMs=(\d+)') { $alanlar += ('gateMs=' + $matches[1]) }
        # startMedia (arm -> cutover) + settle (cutover -> verified) is the whole transfer.
        if ($tamToplam) { $alanlar = @('toplam=' + $toplam + 'ms') + $alanlar }
        Yaz ('  ' + (Damga $satir) + '  ' + ($alanlar -join ' '))
    }
    $geriDonus = @($cagri | Where-Object { $_ -match 'Returning call audio to Redmi|Cutover verification failed' })
    if ($geriDonus.Count -gt 0) {
        Yaz ('  ses Redmi''ye dondu / gecis dogrulanamadi: ' + $geriDonus.Count + ' satir, son: ' + (Kisalt ($geriDonus | Select-Object -Last 1) 160)) 'Yellow'
    }

    $saglik = @($cagri | Where-Object { $_ -match 'RELAY_HEALTH' } | Select-Object -Last 1)
    if ($saglik.Count -gt 0) {
        $alanlar = @()
        foreach ($anahtar in @('owner', 'iphone', 'uplink', 'silence', 'underruns', 'iphoneMuted', 'uplinkSpeechDbfs', 'uplinkPeakDbfs', 'uplinkActive', 'downlinkSpeechDbfs', 'downlinkPeakDbfs', 'downlinkActive')) {
            if ($saglik[0] -match ('\b' + $anahtar + '=(\S+)')) { $alanlar += ($anahtar + '=' + $matches[1]) }
        }
        Yaz ('RELAY_HEALTH (son, ' + (Damga $saglik[0]) + '): ' + ($alanlar -join ' '))
        if ($saglik[0] -notmatch 'uplinkSpeechDbfs=') {
            Yaz '  uplink seviyesi yok: kurulu Redmi surumu bunu henuz yazmiyor' 'DarkGray'
        }
    } else {
        Yaz 'RELAY_HEALTH: kayit yok (cagri olmamis ya da eski kayit donmus)'
    }

    # ---- Health
    Yaz ''
    Yaz '--- Saglik sorunlari ---' 'Cyan'
    $sorunlar = @($cagri | Where-Object { $_ -match '\bHealth\b|ROM build changed' } | Select-Object -Last 5)
    if ($sorunlar.Count -eq 0) {
        Yaz '  kayit yok' 'Green'
    } else {
        foreach ($satir in $sorunlar) { Yaz ('  ' + (Kisalt $satir)) 'Yellow' }
    }

    $ozetYolu = Join-Path $klasor 'ozet.txt'
    try {
        [IO.File]::WriteAllText($ozetYolu, (($script:ozet -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
        Write-Host ''
        Write-Host ('Ozet dosyasi: ' + $ozetYolu)
    } catch {
        Write-Host ('ozet.txt yazilamadi: ' + $_.Exception.Message) -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- yalnizca ozet (adb yok)
if ($Klasor) {
    if (-not (Test-Path -LiteralPath $Klasor)) {
        Write-Host ('Klasor yok: ' + $Klasor) -ForegroundColor Red
        exit 1
    }
    Ozetle (Resolve-Path -LiteralPath $Klasor).Path ''
    exit 0
}

if (-not (Test-Path -LiteralPath $Adb)) {
    Write-Host ('adb yok: ' + $Adb) -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- cek
$cikisKodu = 1
$script:sunucuOnceVardi = [bool](Get-Process -Name adb -ErrorAction SilentlyContinue)
try {
    if (-not $script:sunucuOnceVardi) {
        # Started on its own, without redirected output: a server forked by a redirected adb call
        # could hold this script's pipes open and hang the read.
        $baslat = Start-Process -FilePath $Adb -ArgumentList 'start-server' -WindowStyle Hidden -PassThru
        if (-not $baslat.WaitForExit(20000)) { Write-Host 'adb start-server 20 sn icinde bitmedi; devam ediliyor.' -ForegroundColor Yellow }
    }
    $seri = Redmi-Sec
    $baglanti = $seri + ' (USB)'
    if (Kablosuz-Mu $seri) { $baglanti = $seri + ' (kablosuz)' }

    $gercekSeri = Seri-No $seri
    if ($gercekSeri -and $gercekSeri -ne $UsbSeri) {
        throw ('Bagli cihaz Redmi degil: ro.serialno=' + $gercekSeri + ' (beklenen ' + $UsbSeri + ')')
    }

    $cikisKlasoru = Join-Path $Hedef ('redmi-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    [void][IO.Directory]::CreateDirectory($cikisKlasoru)
    $utf8 = New-Object System.Text.UTF8Encoding($false)

    $cek = Invoke-Adb @('-s', $seri, 'pull', $cihazKlasoru, $cikisKlasoru) 300000
    $cekSonu = (($cek.Cikti + "`n" + $cek.Hata).Trim() -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 1)
    if ($cek.Tamam) {
        Write-Host ('Cekildi: ' + $cekSonu) -ForegroundColor Green
    } else {
        Write-Host ('Klasor tam cekilemedi (kod ' + $cek.Kod + '): ' + $cekSonu) -ForegroundColor Yellow
    }

    $pil = Invoke-Adb @('-s', $seri, 'shell', 'dumpsys', 'battery') 20000
    if ($pil.Tamam) {
        [IO.File]::WriteAllText((Join-Path $cikisKlasoru 'pil.txt'), $pil.Cikti, $utf8)
    } else {
        Write-Host ('dumpsys battery okunamadi: ' + $pil.Hata.Trim()) -ForegroundColor Yellow
    }

    $paket = Invoke-Adb @('-s', $seri, 'shell', 'dumpsys', 'package', 'com.ilkay.redmigateway') 20000
    if ($paket.Tamam) {
        # The first occurrence is the active package; a hidden system copy follows it.
        $surum = @()
        foreach ($anahtar in @('versionName', 'versionCode', 'codePath', 'firstInstallTime', 'lastUpdateTime')) {
            if ($paket.Cikti -match ('(?m)\b' + $anahtar + '=([^\s]+(?: [\d:]+)?)')) { $surum += ($anahtar + '=' + $matches[1]) }
        }
        [IO.File]::WriteAllText((Join-Path $cikisKlasoru 'uygulama-surumu.txt'), (($surum -join "`r`n") + "`r`n"), $utf8)
    } else {
        Write-Host ('dumpsys package okunamadi: ' + $paket.Hata.Trim()) -ForegroundColor Yellow
    }

    Ozetle $cikisKlasoru $baglanti
    $cikisKodu = 0
} catch {
    Write-Host ('HATA: ' + $_.Exception.Message) -ForegroundColor Red
    $cikisKodu = 1
} finally {
    Baglantiyi-Kes
}
exit $cikisKodu
