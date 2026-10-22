# teslim-kapisi.ps1 - Teslim oncesi TEK komut: olculebilen her sey yesil mi?
#
# NEDEN VAR: Kullanici 22.10.2026'da hakli olarak sordu: "teslim etmeden once testi calistirip
# hepsini yesil gordugun bir sey neden yok?". O gune kadar kanitlar tur tur elle toplaniyordu;
# ayni olcum bir sonraki turda tekrarlanmadigi icin "once/sonra" ve "teslim edilebilir" iddialari
# kisiye bagliydi. Bu betik o bosluga tekrar edilebilir bir kapi koyar.
#
# KULLANIM:
#   powershell -NoProfile -ExecutionPolicy Bypass -File RedmiGateway\scripts\quality\teslim-kapisi.ps1
#   ... -Cihaz              (Redmi uzerinde kurulu surum/izin/tanilama olcumleri)
#   ... -Cihaz -Hotspot     (Wi-Fi kaybi -> hotspot suresi olcumu; Wi-Fi'yi ~20 s kapatir)
#
# CIKIS KODU: 0 = hepsi GECTI, 1 = en az bir KALDI (UNVERIFIED teslimi engellemez ama raporda kalir)
#
# KURAL: Bu betik cihaza YALNIZCA -Hotspot ile ve geri alinabilir sekilde dokunur (Wi-Fi kapat/aс
# ayni calisma icinde; finally blogu her durumda Wi-Fi'yi geri acar).

param(
    [switch]$Cihaz,
    [switch]$Hotspot,
    [string]$Adb = 'C:\Users\ilkay.turna\platform-tools\adb.exe',
    [string]$Repo = 'C:\Users\ilkay.turna\source\repos\redmi-gateway',
    [string]$BeklenenAndroidSurum = '2.0.6',
    [int]$BeklenenVersionCode = 206,
    [string]$AscKlasor = 'C:\Users\ilkay.turna\Documents\asc-cli',
    [string]$AscAppId = '6816340544',
    [int]$HotspotButcesiSn = 8,
    # iPhone build 114+ kendini Redmi'ye loglar; bos birakilirsa yalnizca "akis var mi" olculur.
    [string]$BeklenenIphoneBuild = ''
)

$ErrorActionPreference = 'Continue'
$script:sonuclar = New-Object System.Collections.ArrayList

function Kapi([string]$ad, [string]$durum, [string]$kanit) {
    [void]$script:sonuclar.Add([pscustomobject]@{ Ad = $ad; Durum = $durum; Kanit = $kanit })
    $renk = 'Yellow'
    if ($durum -eq 'GECTI') { $renk = 'Green' }
    if ($durum -eq 'KALDI') { $renk = 'Red' }
    Write-Host ('[{0,-9}] {1} :: {2}' -f $durum, $ad, $kanit) -ForegroundColor $renk
}

function AdbCalistir([string]$komut) {
    return (& $Adb shell $komut 2>$null) | Out-String
}

# ---------------------------------------------------------------- repo
Write-Host ''
Write-Host '=== 1) REPO ===' -ForegroundColor Cyan
if (-not (Test-Path $Repo)) {
    Kapi 'repo' 'KALDI' ("depo yok: " + $Repo)
} else {
    Push-Location $Repo
    $dal = (& git rev-parse --abbrev-ref HEAD 2>$null) | Out-String
    $dal = $dal.Trim()
    $kirli = (& git status --porcelain 2>$null) | Out-String
    if ($kirli.Trim().Length -eq 0) {
        Kapi 'repo temiz' 'GECTI' ('dal=' + $dal + ' (izlenmeyen degisiklik yok)')
    } else {
        $satir = ($kirli.Trim() -split "`n").Count
        Kapi 'repo temiz' 'KALDI' ('commit edilmemis ' + $satir + ' dosya')
    }
    $bas = (& git log -1 --pretty='%h %s' 2>$null) | Out-String
    Write-Host ('  HEAD: ' + $bas.Trim())
    Pop-Location
}

$gradleYolu = Join-Path $Repo 'RedmiGateway\android\app\build.gradle.kts'
if (Test-Path $gradleYolu) {
    $gradleMetni = Get-Content -LiteralPath $gradleYolu -Raw
    $surumVar = ($gradleMetni -match ('versionName = "' + [regex]::Escape($BeklenenAndroidSurum) + '"'))
    $kodVar = ($gradleMetni -match ('versionCode = ' + $BeklenenVersionCode))
    if ($surumVar -and $kodVar) {
        Kapi 'android surum' 'GECTI' ($BeklenenVersionCode.ToString() + ' / ' + $BeklenenAndroidSurum)
    } else {
        Kapi 'android surum' 'KALDI' ('build.gradle.kts beklenen surumle uyusmuyor')
    }
}

# ---------------------------------------------------------------- android testleri
Write-Host ''
Write-Host '=== 2) ANDROID BIRIM TESTLERI ===' -ForegroundColor Cyan
$androidKok = Join-Path $Repo 'RedmiGateway\android'
$gradlew = Join-Path $androidKok 'gradlew.bat'
if (Test-Path $gradlew) {
    $env:JAVA_HOME = 'C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\jdk\jdk-17.0.19+10'
    Push-Location $androidKok
    $testCikti = (& $gradlew testDebugUnitTest --no-daemon 2>&1) | Out-String
    # Gradle'in ciktisindaki "BUILD SUCCESSFUL" bu konsol kod sayfasinda bozuluyor (BU?LD);
    # yalnizca degismeyen parcasi aranir.
    $testGecti = $testCikti -match 'SUCCESSFUL'
    $xmlDizin = Join-Path $androidKok 'app\build\test-results\testDebugUnitTest'
    $testSayisi = 0
    $hataSayisi = 0
    if (Test-Path $xmlDizin) {
        foreach ($xmlDosya in (Get-ChildItem -LiteralPath $xmlDizin -Filter '*.xml' -ErrorAction SilentlyContinue)) {
            try {
                [xml]$rapor = Get-Content -LiteralPath $xmlDosya.FullName -Raw
                $testSayisi += [int]$rapor.testsuite.tests
                $hataSayisi += [int]$rapor.testsuite.failures
                $hataSayisi += [int]$rapor.testsuite.errors
            } catch { }
        }
    }
    if ($testGecti -and $hataSayisi -eq 0 -and $testSayisi -gt 0) {
        Kapi 'android testleri' 'GECTI' ('' + $testSayisi + ' test, 0 hata')
    } else {
        Kapi 'android testleri' 'KALDI' ('gecti=' + $testGecti + ' test=' + $testSayisi + ' hata=' + $hataSayisi)
    }
    Pop-Location
} else {
    Kapi 'android testleri' 'KALDI' 'gradlew.bat yok'
}

# ---------------------------------------------------------------- android derleme
Write-Host ''
Write-Host '=== 3) ANDROID DERLEME ===' -ForegroundColor Cyan
if (Test-Path $gradlew) {
    $env:JAVA_HOME = 'C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\jdk\jdk-17.0.19+10'
    Push-Location $androidKok
    $derlemeCikti = (& $gradlew assembleDebug --no-daemon 2>&1) | Out-String
    Pop-Location
    $apk = Join-Path $androidKok 'app\build\outputs\apk\debug\app-debug.apk'
    if (($derlemeCikti -match 'SUCCESSFUL') -and (Test-Path $apk)) {
        $boyut = [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 1)
        Kapi 'android derleme' 'GECTI' ('app-debug.apk ' + $boyut + ' MB')
    } else {
        Kapi 'android derleme' 'KALDI' 'assembleDebug basarisiz'
    }
} else {
    Kapi 'android derleme' 'KALDI' 'gradlew.bat yok'
}

# ---------------------------------------------------------------- cihaz
if ($Cihaz) {
    Write-Host ''
    Write-Host '=== 4) REDMI (cihaz) ===' -ForegroundColor Cyan
    if (-not (Test-Path $Adb)) {
        Kapi 'adb' 'KALDI' ('adb yok: ' + $Adb)
    } else {
        $cihazlar = (& $Adb devices 2>$null) | Out-String
        $bagli = $cihazlar -match '17df6357\s+device'
        if (-not $bagli) {
            Kapi 'cihaz baglantisi' 'KALDI' 'Redmi (17df6357) bagli degil'
        } else {
            Kapi 'cihaz baglantisi' 'GECTI' '17df6357 device'
            $paket = (& $Adb shell 'dumpsys package com.ilkay.redmigateway' 2>$null) | Out-String
            $surumSatiri = ($paket -split "`n" | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1)
            $kodSatiri = ($paket -split "`n" | Where-Object { $_ -match 'versionCode=' } | Select-Object -First 1)
            if ($kodSatiri -match ('versionCode=' + $BeklenenVersionCode) -and $surumSatiri -match ('versionName=' + [regex]::Escape($BeklenenAndroidSurum))) {
                Kapi 'kurulu surum' 'GECTI' ($BeklenenVersionCode.ToString() + ' / ' + $BeklenenAndroidSurum)
            } else {
                Kapi 'kurulu surum' 'KALDI' (('kurulu: ' + $kodSatiri.Trim() + ' ' + $surumSatiri.Trim()).Trim())
            }

            $eksik = @()
            foreach ($izin in @('android.permission.TETHER_PRIVILEGED', 'android.permission.WRITE_SETTINGS',
                                'android.permission.MODIFY_AUDIO_SETTINGS', 'android.permission.RECORD_AUDIO',
                                'android.permission.POST_NOTIFICATIONS')) {
                $desen = [regex]::Escape($izin) + '.*granted=true'
                if (-not ($paket -match $desen)) { $eksik += $izin.Split('.')[-1] }
            }
            if ($eksik.Count -eq 0) {
                Kapi 'izinler' 'GECTI' 'TETHER/WRITE_SETTINGS/MODIFY_AUDIO/RECORD_AUDIO/POST_NOTIFICATIONS'
            } else {
                Kapi 'izinler' 'KALDI' ('eksik: ' + ($eksik -join ', '))
            }

            $surec = (& $Adb shell 'ps -A -o PID,STIME,NAME | grep -i redmig' 2>$null) | Out-String
            if ($surec -match 'com.ilkay.redmigateway') {
                Kapi 'uygulama sureci' 'GECTI' ($surec.Trim() -replace '\s+', ' ')
            } else {
                Kapi 'uygulama sureci' 'KALDI' 'uygulama calismiyor'
            }

            $tanilamaYolu = '/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/call-diagnostics.log'
            # Toybox stat bu cihazda "%Y" desteklemiyor (denendi: "stat: '%Y': No such file").
            # ls -l tek satirda boyutu ve tarihi veriyor; yas cihazin kendi saatinden hesaplanir.
            $satir = (& $Adb shell ('ls -l ' + $tanilamaYolu) 2>$null) | Out-String
            $satir = $satir.Trim()
            $desen = '^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2})\s'
            if ($satir -match $desen) {
                $bayt = [int]$matches[1]
                $yazim = [datetime]::ParseExact($matches[2], 'yyyy-MM-dd HH:mm', $null)
                $yasDk = [math]::Round(((Get-Date) - $yazim).TotalMinutes, 0)
                if ($bayt -gt 1000 -and $yasDk -le 180) {
                    Kapi 'tanilama yazimi' 'GECTI' ($bayt.ToString() + ' bayt, son yazim ' + $yasDk + ' dk once')
                } else {
                    Kapi 'tanilama yazimi' 'KALDI' ($bayt.ToString() + ' bayt, son yazim ' + $yasDk + ' dk once')
                }
            } else {
                Kapi 'tanilama yazimi' 'KALDI' ('ls ciktisi okunamadi: ' + $satir)
            }

            # iPhone'un kendi log ve durum akisi (build 114+): iPhone'da USB hata ayiklama yok,
            # bu yuzden tek kanit yolu Redmi'ye ittigi telemetri dosyasi.
            $iphoneYolu = '/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/iphone-diagnostics.log'
            $iphoneSatir = (& $Adb shell ('ls -l ' + $iphoneYolu) 2>$null) | Out-String
            $iphoneSatir = $iphoneSatir.Trim()
            if ($iphoneSatir -match $desen) {
                $iphoneBayt = [int]$matches[1]
                $iphoneYazim = [datetime]::ParseExact($matches[2], 'yyyy-MM-dd HH:mm', $null)
                $iphoneYas = [math]::Round(((Get-Date) - $iphoneYazim).TotalMinutes, 0)
                if ($iphoneBayt -gt 0 -and $iphoneYas -le 180) {
                    Kapi 'iPhone telemetrisi' 'GECTI' ($iphoneBayt.ToString() + ' bayt, son yazim ' + $iphoneYas + ' dk once')
                } else {
                    Kapi 'iPhone telemetrisi' 'KALDI' ($iphoneBayt.ToString() + ' bayt, son yazim ' + $iphoneYas + ' dk once')
                }
                if ($BeklenenIphoneBuild) {
                    $bagliSatir = (& $Adb shell ('tail -400 ' + $iphoneYolu) 2>$null) | Out-String
                    $bulunan = ''
                    if ($bagliSatir -match 'linked app=\S+ build=(\d+)') { $bulunan = $matches[1] }
                    if ($bulunan -eq $BeklenenIphoneBuild) {
                        Kapi 'iPhone build' 'GECTI' ('bagli iPhone build ' + $bulunan)
                    } else {
                        Kapi 'iPhone build' 'KALDI' ('beklenen ' + $BeklenenIphoneBuild + ', dosyada: ' + ($bulunan -replace '^$', 'yok'))
                    }
                }
            } else {
                Kapi 'iPhone telemetrisi' 'UNVERIFIED' 'iphone-diagnostics.log yok (iPhone build 114+ kurulmali)'
            }
        }
    }
}

# ---------------------------------------------------------------- hotspot kabul testi
if ($Cihaz -and $Hotspot -and (Test-Path $Adb)) {
    Write-Host ''
    Write-Host '=== 5) HOTSPOT KABUL TESTI (Wi-Fi ~20 s kapanir) ===' -ForegroundColor Cyan
    $wifiGeriAcmali = $true
    try {
        & $Adb logcat -c 2>$null
        $baslangic = Get-Date
        & $Adb shell 'svc wifi disable' 2>$null
        $onSatiri = $null
        $apSatiri = $null
        $kayipSatiri = $null
        $deneme = 0
        while ($deneme -lt 20 -and -not ($onSatiri -and $apSatiri)) {
            Start-Sleep -Milliseconds 700
            $deneme++
            $kayit = (& $Adb logcat -d -v time 2>$null) | Out-String
            if (-not $kayipSatiri -and ($kayit -match 'SmartHotspot.*Wi-Fi lost')) {
                $kayipSatiri = ([regex]::Match($kayit, '(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\w/SmartHotspot.*Wi-Fi lost')).Groups[1].Value
            }
            if (-not $onSatiri -and ($kayit -match 'SmartHotspot.*No Wi-Fi; hotspot on')) {
                $onSatiri = ([regex]::Match($kayit, '(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\w/SmartHotspot.*No Wi-Fi; hotspot on')).Groups[1].Value
            }
            if (-not $apSatiri -and ($kayit -match 'AP-ENABLED')) {
                $apSatiri = ([regex]::Match($kayit, '(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+\w/hostapd.*AP-ENABLED')).Groups[1].Value
            }
        }
        if (-not $kayipSatiri -or -not $onSatiri) {
            Kapi 'hotspot otomatik' 'KALDI' ('Wi-Fi kaybi veya hotspot karari logda yok (beklenen ' + $HotspotButcesiSn + ' sn)')
        } else {
            $bicim = 'MM-dd HH:mm:ss.fff'
            $fark = ([datetime]::ParseExact($onSatiri, $bicim, $null) - [datetime]::ParseExact($kayipSatiri, $bicim, $null)).TotalSeconds
            $farkYuvarlak = [math]::Round($fark, 1)
            if ($fark -le $HotspotButcesiSn) {
                Kapi 'hotspot otomatik' 'GECTI' ($farkYuvarlak.ToString() + ' sn icinde acildi (butce ' + $HotspotButcesiSn + ' sn), AP-ENABLED=' + $apSatiri)
            } else {
                Kapi 'hotspot otomatik' 'KALDI' ($farkYuvarlak.ToString() + ' sn (butce ' + $HotspotButcesiSn + ' sn)')
            }
        }
    } finally {
        if ($wifiGeriAcmali) {
            & $Adb shell 'svc wifi enable' 2>$null
            $geriDeneme = 0
            $geriGeldi = $false
            while ($geriDeneme -lt 40 -and -not $geriGeldi) {
                Start-Sleep -Milliseconds 1000
                $geriDeneme++
                $durum = (& $Adb shell 'cmd wifi status' 2>$null) | Out-String
                if ($durum -match 'connected to') { $geriGeldi = $true }
            }
            if ($geriGeldi) {
                Kapi 'wifi geri dondu' 'GECTI' 'Wi-Fi yeniden bagli'
            } else {
                Kapi 'wifi geri dondu' 'KALDI' 'Wi-Fi 40 sn icinde baglanmadi'
            }
            # Wi-Fi geri geldikten sonra hotspot STABLE_MS (15 s) bekleme sonrasi kapanir; olcum
            # tek seferde degil, 45 s boyunca yoklanarak yapilir.
            $kapandi = $false
            $kapanmaDeneme = 0
            while ($kapanmaDeneme -lt 45 -and -not $kapandi) {
                Start-Sleep -Seconds 1
                $kapanmaDeneme++
                $kapanma = (& $Adb logcat -d -v time 2>$null) | Out-String
                if ($kapanma -match 'Known Wi-Fi in use; hotspot off') { $kapandi = $true }
            }
            if ($kapandi) {
                Kapi 'hotspot kapandi' 'GECTI' ('Wi-Fi gelince hotspot kapandi (' + $kapanmaDeneme + ' sn icinde)')
            } else {
                Kapi 'hotspot kapandi' 'KALDI' '45 sn icinde kapanmadi'
            }
        }
    }
}

# ---------------------------------------------------------------- iOS: CI + TestFlight
Write-Host ''
Write-Host '=== 6) iOS: CI ve TestFlight ===' -ForegroundColor Cyan
$ascBetik = Join-Path $AscKlasor 'asc.mjs'
if (-not (Test-Path $ascBetik)) {
    Kapi 'TestFlight' 'UNVERIFIED' ('asc.mjs yok: ' + $ascBetik)
} else {
    $env:ASC_APP_ID = $AscAppId
    Push-Location $AscKlasor
    $buildJson = (& node asc.mjs builds 2>$null) | Out-String
    Pop-Location
    if (-not $buildJson.Trim()) {
        Kapi 'TestFlight' 'KALDI' 'ASC build listesi okunamadi'
    } else {
        try {
            $buildler = $buildJson | ConvertFrom-Json
            $enYeni = $buildler | Select-Object -First 1
            Push-Location $AscKlasor
            $grupJson = (& node asc.mjs group-builds '2268c752-72d2-4f2f-8b7e-a707f3567b01' 2>$null) | Out-String
            $detayJson = (& node asc.mjs get ('/v1/builds/' + $enYeni.id + '/buildBetaDetail') 2>$null) | Out-String
            Pop-Location
            $grupta = $grupJson -match ('"version": "' + [regex]::Escape($enYeni.version) + '"')
            $icDurum = ''
            if ($detayJson -match 'IN_BETA_TESTING') { $icDurum = 'IN_BETA_TESTING' }
            if ($enYeni.processingState -eq 'VALID' -and $grupta -and $icDurum -eq 'IN_BETA_TESTING') {
                Kapi 'TestFlight build' 'GECTI' ('build ' + $enYeni.version + ' VALID, ic grupta, IN_BETA_TESTING')
            } else {
                Kapi 'TestFlight build' 'KALDI' ('build ' + $enYeni.version + ' processing=' + $enYeni.processingState + ' grupta=' + $grupta + ' icDurum=' + $icDurum)
            }
        } catch {
            Kapi 'TestFlight' 'KALDI' ('ASC yaniti okunamadi: ' + $_.Exception.Message)
        }
    }
}

$cred = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
$tokenSatiri = ($cred | Select-String '^password=').Line
if ($tokenSatiri) {
    $token = $tokenSatiri.Split('=', 2)[1].Trim()
    $basliklar = @{ Authorization = ('Bearer ' + $token); Accept = 'application/vnd.github+json' }
    try {
        $kosumlar = (Invoke-RestMethod -Uri 'https://api.github.com/repos/ilkayturna/redmi-gateway/actions/runs?per_page=5' -Headers $basliklar).workflow_runs
        $sonKosum = $kosumlar | Where-Object { $_.status -eq 'completed' } | Select-Object -First 1
        if ($sonKosum) {
            if ($sonKosum.conclusion -eq 'success') {
                Kapi 'iOS CI' 'GECTI' ('run ' + $sonKosum.run_number + ' yesil, sha=' + $sonKosum.head_sha.Substring(0, 7))
            } else {
                Kapi 'iOS CI' 'KALDI' ('run ' + $sonKosum.run_number + ' ' + $sonKosum.conclusion)
            }
        } else {
            Kapi 'iOS CI' 'UNVERIFIED' 'tamamlanmis kosum yok'
        }
    } catch {
        Kapi 'iOS CI' 'UNVERIFIED' ('API okunamadi: ' + $_.Exception.Message)
    }
} else {
    Kapi 'iOS CI' 'UNVERIFIED' 'GitHub token alinamadi'
}

# ---------------------------------------------------------------- gercek cagri (otomatiklestirilemez)
Kapi 'gercek cagri (zil/ses)' 'UNVERIFIED' 'arayan bir hat gerektirir: Redmi aranacak, iPhone zilcalacak, ses iki yonde kontrol edilecek'

# ---------------------------------------------------------------- ozet
Write-Host ''
Write-Host '=== OZET ===' -ForegroundColor Cyan
# @() ile sarilir: tek sonuc dondugunde Where-Object tek nesne verir ve .Count bos gorunebilir.
$gecti = @($script:sonuclar | Where-Object { $_.Durum -eq 'GECTI' }).Count
$kaldi = @($script:sonuclar | Where-Object { $_.Durum -eq 'KALDI' }).Count
$belirsiz = @($script:sonuclar | Where-Object { $_.Durum -eq 'UNVERIFIED' }).Count
Write-Host ('  GECTI=' + $gecti + '  KALDI=' + $kaldi + '  UNVERIFIED=' + $belirsiz)
if ($kaldi -gt 0) {
    Write-Host '  TESLIM EDILMEZ: kirmizi kapi var.' -ForegroundColor Red
    exit 1
}
if ($belirsiz -gt 0) {
    Write-Host '  Olculebilen kapilar yesil; kalan UNVERIFIED maddeler raporda acikca yazilir.' -ForegroundColor Yellow
}
Write-Host '  CIKIS: 0' -ForegroundColor Green
exit 0
