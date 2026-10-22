# zil-izi.ps1 - Bir cagri denemesinin zil zincirini iki cihazin kaydindan tek tabloda cikarir.
#
# NEDEN VAR: "iPhone calmadi" sikayeti tek bir log satiriyla aciklanmiyor; zincir Redmi'de baslar
# (cagri geldi -> zil -> push -> onay) ve iPhone'da surer (push alindi -> CallKit raporu -> ack).
# Bu betik iki dosyayi da okur ve yalnizca zincire ait satirlari sirayla yazar, boylece "nerede
# koptu" sorusu tek ekranda cevaplanir.
#
# KULLANIM:
#   powershell -NoProfile -ExecutionPolicy Bypass -File RedmiGateway\scripts\quality\zil-izi.ps1
#   ... -Dakika 30          (son 30 dakika; varsayilan 360)
#   ... -Tumu               (zaman filtresi yok)
#
# CIKIS KODU: 0 = tablo yazildi, 1 = cihaz/adb okunamadi.

param(
    [int]$Dakika = 360,
    [switch]$Tumu,
    [string]$Adb = 'C:\Users\ilkay.turna\platform-tools\adb.exe',
    [string]$Cikti = ''
)

$ErrorActionPreference = 'Continue'

if (-not (Test-Path $Adb)) {
    Write-Host ('adb yok: ' + $Adb) -ForegroundColor Red
    exit 1
}

$cihazlar = (& $Adb devices 2>$null) | Out-String
if (-not ($cihazlar -match '17df6357\s+device')) {
    Write-Host 'Redmi bagli degil (17df6357)' -ForegroundColor Red
    exit 1
}

$kok = 'C:\Users\ilkay.turna\Documents\deepseek-harness\default-workspace'
$aDosya = Join-Path $kok '_zil-redmi.log'
$iDosya = Join-Path $kok '_zil-iphone.log'

& $Adb pull '/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/call-diagnostics.log' $aDosya 2>$null | Out-Null
& $Adb pull '/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/iphone-diagnostics.log' $iDosya 2>$null | Out-Null

# Zil zincirine ait desenler. Redmi tarafi "Call:"/"Audio:"/"Push:" etiketleriyle, iPhone tarafi
# "iPhone[...]" etiketiyle yazilir; ikisi de ayni zaman damgasi bicimini kullanir.
$zincir = 'RedmiRinger|Incoming call|pushed to the iPhone|APNs|ringing|ringing_ack|showing on the iPhone|AUDIO_OWNER|ANSWER|iPhone connected|iPhone-link|iPhone\[|iPhone-status|VoIP push|CallKit|reportIncoming|reported'

$satirlar = New-Object System.Collections.ArrayList
foreach ($dosya in @($aDosya, $iDosya)) {
    if (-not (Test-Path $dosya)) { continue }
    foreach ($satir in (Get-Content -LiteralPath $dosya)) {
        if ($satir -notmatch $zincir) { continue }
        if ($satir -match '^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)') {
            $damga = [datetime]::new((Get-Date).Year, [int]$matches[1], [int]$matches[2],
                                     [int]$matches[3], [int]$matches[4], [int]$matches[5])
            if (-not $Tumu -and ((Get-Date) - $damga).TotalMinutes -gt $Dakika) { continue }
            [void]$satirlar.Add([pscustomobject]@{ Zaman = $damga; Dosya = (Split-Path $dosya -Leaf); Satir = $satir })
        }
    }
}

if ($satirlar.Count -eq 0) {
    Write-Host 'Zincire ait satir bulunamadi (son ' + $Dakika + ' dakika).' -ForegroundColor Yellow
    exit 0
}

$sirali = $satirlar | Sort-Object Zaman
Write-Host ''
Write-Host ('=== ZIL ZINCIRI (son ' + $Dakika + ' dk, ' + $sirali.Count + ' satir) ===') -ForegroundColor Cyan
foreach ($kayit in $sirali) {
    $etiket = 'redmi '
    $renk = 'Gray'
    if ($kayit.Dosya -like '*iphone*') { $etiket = 'iphone'; $renk = 'Cyan' }
    if ($kayit.Satir -match 'ringing_ack|showing on the iPhone') { $renk = 'Green' }
    if ($kayit.Satir -match 'dropped|refused|failed|not shown|did not show') { $renk = 'Red' }
    $govde = $kayit.Satir -replace '^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+', ''
    Write-Host ('{0} {1} {2}' -f $kayit.Zaman.ToString('HH:mm:ss'), $etiket, $govde) -ForegroundColor $renk
}

$ozet = @()
$ozet += 'Redmi zil: ' + (@($sirali | Where-Object { $_.Satir -match 'Redmi rings' }).Count)
$ozet += 'push: ' + (@($sirali | Where-Object { $_.Satir -match 'pushed to the iPhone|APNs voip push accepted' }).Count)
$ozet += 'ack: ' + (@($sirali | Where-Object { $_.Satir -match 'ringing_ack|showing on the iPhone' }).Count)
$ozet += 'iPhone satiri: ' + (@($sirali | Where-Object { $_.Dosya -like '*iphone*' }).Count)
Write-Host ''
Write-Host ('=== OZET: ' + ($ozet -join ' | ') + ' ===') -ForegroundColor Cyan

if ($Cikti) {
    $sirali | ForEach-Object { $_.Zaman.ToString('HH:mm:ss') + ' ' + $_.Dosya + ' ' + $_.Satir } |
        Set-Content -LiteralPath $Cikti -Encoding UTF8
    Write-Host ('Dosyaya yazildi: ' + $Cikti)
}
exit 0
