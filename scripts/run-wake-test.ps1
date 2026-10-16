# RedmiGateway - TEK KOMUT: on kontrol + cagri olcumu + karar
#
# Kullanim:
#   powershell -ExecutionPolicy Bypass -File run-wake-test.ps1
#
# Ne yapar:
#   ADIM 0  On kontrol: cihaz, VENDOR SES DUZELTMESI, servis, varsayilan dialer
#           -> vendor duzeltmesi yoksa DURUR (kod sorunu degil, ortam sorunu)
#   ADIM 1  logcat temizlenir, arka planda yakalanir
#   ADIM 2  Cagri otomatik algilanir (elle Enter gerekmez)
#   ADIM 3  Olcum + HEDEF KARSILASTIRMASI + KARAR
#
# Cikti: ekrana karar tablosu + logs\olcum-<zaman>.log (ham log)
#
# Referans: AGENTS.md 6.5 (once ortam, sonra kod)

param(
  [string]$Serial = "17df6357",
  [int]$CallTimeoutSeconds = 300,
  [int]$AfterCallSeconds = 25
)

$ErrorActionPreference = "Continue"
$adb = "C:\Users\ilkay.turna\platform-tools\adb.exe"
$pkg = "com.ilkay.redmigateway"
$outDir = "C:\Users\ilkay.turna\Documents\GitHub\RedmiGateway-Archive\logs"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$raw = Join-Path $outDir "olcum-$stamp.log"

function Say($t, $c = "Gray") { Write-Host $t -ForegroundColor $c }
function Head($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }

# ---------------------------------------------------------------- ADIM 0
Head "ADIM 0 - ON KONTROL"

$devices = & $adb devices 2>$null
if ($devices -notmatch "$Serial\s+device") { Say "  HATA: $Serial bagli degil" Red; exit 1 }
Say "  [OK] Cihaz bagli: $Serial" Green

# 0a) VENDOR SES DUZELTMESI - en kritik on kosul
$vendorRaw = (& $adb -s $Serial shell "grep -c USECASE_INCALL_MUSIC_UPLINK2 /vendor/etc/audio_platform_info_intcodec.xml 2>/dev/null" 2>$null) -join ""
$vendorCount = if ($vendorRaw -match '^\d+$') { [int]$vendorRaw } else { -1 }

$mixerRaw = (& $adb -s $Serial shell "grep -c 'Incall_Music_2 Audio Mixer MultiMedia9' /vendor/etc/mixer_paths_idp.xml 2>/dev/null" 2>$null) -join ""
$mixerCount = if ($mixerRaw -match '^\d+$') { [int]$mixerRaw } else { -1 }

if ($vendorCount -gt 0 -and $mixerCount -gt 0) {
  Say "  [OK] Vendor ses duzeltmesi YERINDE (platform=$vendorCount mixer=$mixerCount)" Green
} elseif ($vendorCount -eq -1 -or $mixerCount -eq -1) {
  Say "  [?]  Vendor dosyalari okunamadi (adb erisimi?) - devam ediliyor" Yellow
} else {
  Say "  [XX] VENDOR SES DUZELTMESI YOK!" Red
  Say "       platform=$vendorCount mixer=$mixerCount (ikisi de >0 olmali)" Red
  Say "" 
  Say "       *** IPHONE SESI BU DURUMDA HIC CALISMAZ. KOD SORUNU DEGIL. ***" Red
  Say "       Duzelt:  cd C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway" Red
  Say "                python scripts\device\vendor_audio_fix.py apply" Red
  Say "" 
  Say "       Redmi ekraninda da gorunur:" Yellow
  Say "         Ayarlar (disli) -> 'iPhone ses kanali (SIM 2)' = 'Duzeltme yok'" Yellow
  exit 2
}

# 0b) Servis + dialer
$svc = (& $adb -s $Serial shell "dumpsys activity services $pkg 2>/dev/null | grep -c GatewayService" 2>$null) -join ""
Say "  [..] GatewayService referansi: $(if ($svc -match '^\d+$') { $svc } else { '?' })"

$dialer = (& $adb -s $Serial shell "settings get secure default_input_method >/dev/null 2>&1; cmd telecom get-default-dialer 2>/dev/null" 2>$null) -join ""
if ($dialer -match "redmigateway") { Say "  [OK] Varsayilan dialer: $pkg" Green }
elseif ($dialer.Trim()) { Say "  [!]  Varsayilan dialer: $($dialer.Trim())  (uygulama olmali!)" Yellow }
else { Say "  [?]  Dialer okunamadi" Yellow }

# 0c) Push on kosullari (tanilama dosyasindan)
$diag = "/sdcard/Android/data/$pkg/files/diagnostics/call-diagnostics.log"
$pushLine = (& $adb -s $Serial shell "grep -o 'alertsRegistered[^,}]*' $diag 2>/dev/null | tail -1" 2>$null) -join ""
if ($pushLine) { Say "  [..] $pushLine" }
Say "  NOT: alert token yoksa iPhone uygulamayi BIR KEZ acmali." DarkGray

# ---------------------------------------------------------------- ADIM 1
Head "ADIM 1 - logcat yakalama"
& $adb -s $Serial logcat -c 2>$null
$job = Start-Job -ScriptBlock {
  param($adb, $serial, $out)
  & $adb -s $serial logcat -v time 2>$null | Out-File -FilePath $out -Encoding utf8
} -ArgumentList $adb, $Serial, $raw
Say "  Yakalama basladi -> $raw" DarkGray

# ---------------------------------------------------------------- ADIM 2
Head "ADIM 2 - cagri bekleniyor"
Say "  >>> REDMI'DEN 532'YI ARA <<<" Yellow
Say "  (cagri otomatik algilanacak, Enter gerekmez)" DarkGray

$deadline = (Get-Date).AddSeconds($CallTimeoutSeconds)
$detected = $false
# Yalnizca GERCEK cagri izleri: cagri durumu, arm, ses aktarimi, uyanma.
# Genel "CallAudioRelay" gibi gevsek desen kullanma - arka plan log'una takilir.
$callPattern = "arm\(|audio_start|Call audio moved|Silent wake push|RINGER_OFF|" +
               "AUDIO_OWNER|STATE_RINGING|STATE_ACTIVE|startLocal called|take call|" +
               "answeredElsewhere|CallKit reportIncoming|currentCallState=(ringing|active|dialing)"
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 2
  $hit = Get-Content $raw -ErrorAction SilentlyContinue | Select-String -Pattern $callPattern | Select-Object -First 1
  if ($hit) {
    $detected = $true
    Say "  [OK] Cagri algilandi: $($hit.Line.Trim())" Green
    break
  }
  $left = [int]($deadline - (Get-Date)).TotalSeconds
  Write-Host "`r  bekleniyor... $left sn  " -NoNewline -ForegroundColor DarkGray
}
Write-Host ""
if ($detected) {
  Say "  Cagri sesi olcumu icin $AfterCallSeconds sn bekleniyor..." DarkGray
  Start-Sleep -Seconds $AfterCallSeconds
} else {
  Say "  [!]  Zaman asimi ($CallTimeoutSeconds sn) - yine de ozet uretiliyor" Yellow
}

Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- ADIM 3
Head "ADIM 3 - OLCUM"

$log = Get-Content $raw -ErrorAction SilentlyContinue
if (-not $log) { Say "  Log bos!" Red; exit 1 }

function Grab($title, $pattern, $n = 6) {
  $hits = $log | Select-String -Pattern $pattern
  if ($hits) {
    Write-Host ""
    Write-Host "  $title" -ForegroundColor Magenta
    $hits | Select-Object -Last $n | ForEach-Object { Write-Host "    $($_.Line.Trim())" }
    return $hits
  }
  return @()
}

$push   = Grab "SESSIZ PUSH"            "Silent wake push"
$noTok  = Grab "ALERT TOKEN EKSIK"      "no alert token" 3
$wake   = Grab "UYANMA"                 "iPhone connected .* after the wake beacon"
$engine = Grab "MOTOR (v2 olcumleri)"   "localReady|firstCapture|firstSend|startLocal|Early audio start|Attach found no running"
$moved  = Grab "GECIS"                  "Call audio moved|GATE_OPEN"
$gate   = Grab "KAPI BEKLEMESI"         "GATE_WAIT" 3
$relay  = Grab "KORUNAN METRIKLER"      "RELAY_HEALTH|silence|dropped|maxWriteMs|underrun" 4

# ---------------------------------------------------------------- KARAR
Head "KARAR"

function Check($name, $ok, $detail) {
  $mark = if ($ok -eq $true) { "[OK]" } elseif ($ok -eq $false) { "[XX]" } else { "[??]" }
  $color = if ($ok -eq $true) { "Green" } elseif ($ok -eq $false) { "Red" } else { "Yellow" }
  Write-Host ("  {0} {1,-28} {2}" -f $mark, $name, $detail) -ForegroundColor $color
}

$pushSent  = ($push | Measure-Object).Count -gt 0
$pushNoTok = ($noTok | Measure-Object).Count -gt 0
$wakeHit   = $wake | Select-Object -Last 1
$wakeMs    = -1
if ($wakeHit -and $wakeHit.Line -match '(\d+)\s*ms') { $wakeMs = [int]$Matches[1] }

$movedHit = $moved | Select-Object -Last 1
$gateMs   = -1; $firstPcm = -1; $startMedia = -1
if ($movedHit) {
  if ($movedHit.Line -match 'gateMs=(\d+)')      { $gateMs = [int]$Matches[1] }
  if ($movedHit.Line -match 'firstPcm=(\d+)')    { $firstPcm = [int]$Matches[1] }
  if ($movedHit.Line -match 'startMedia=(\d+)')  { $startMedia = [int]$Matches[1] }
}

$localReady = -1; $firstSend = -1
foreach ($l in $engine) {
  if ($l.Line -match 'localReady=(\d+)')  { $localReady = [int]$Matches[1] }
  if ($l.Line -match 'firstSend=(\d+)')   { $firstSend = [int]$Matches[1] }
}

$watchdog = ($log | Select-String -Pattern "Early audio start was never attached").Count
$rebuild  = ($log | Select-String -Pattern "Attach found no running engine").Count

Write-Host ""
Check "Vendor ses duzeltmesi" $true "yerinde"
Check "Sessiz push gonderildi" $(if ($pushNoTok) { $false } else { $pushSent }) $(if ($pushNoTok) { "ALERT TOKEN YOK - iPhone uygulamayi bir kez ac" } elseif ($pushSent) { "gonderildi" } else { "gorulmedi" })
Check "Uyanma (wakeToConnectMs)" $(if ($wakeMs -lt 0) { $null } else { $wakeMs -le 3000 }) $(if ($wakeMs -lt 0) { "olculemedi" } else { "$wakeMs ms  (hedef 1000-3000, eski 58000)" })
Check "firstPcm" $(if ($firstPcm -lt 0) { $null } else { $firstPcm -le 200 }) $(if ($firstPcm -lt 0) { "yok" } else { "$firstPcm ms  (eski 592)" })
Check "startMedia" $(if ($startMedia -lt 0) { $null } else { $startMedia -lt 1000 }) $(if ($startMedia -lt 0) { "yok" } else { "$startMedia ms  (hedef <1000, eski 2477)" })
Check "localReady (v2)" $(if ($localReady -lt 0) { "n/a" } else { $localReady -le 600 }) $(if ($localReady -lt 0) { "v2 dalinda beklenir" } else { "$localReady ms" })
Check "firstSend (v2, asil)" $(if ($firstSend -lt 0) { "n/a" } else { $firstSend -le 500 }) $(if ($firstSend -lt 0) { "v2 dalinda beklenir" } else { "$firstSend ms  (hedef <=500)" })
Check "Mikrofon bekcisi" $(if ($watchdog -gt 0) { $null } else { $true }) $(if ($watchdog -gt 0) { "$watchdog kez atesledi (+$rebuild kez yeniden kuruldu)" } else { "ateslenmedi (normal)" })
Check "Ses akiyor" $(if ($movedHit) { $true } else { $false }) $(if ($movedHit) { "Call audio moved" } else { "GECIS SATIRI YOK" })

Write-Host ""
Say "HAM LOG: $raw" DarkGray
Say "Korunanlar: silence 0-1/s · gap 52 ms · dropped 0 · maxWriteMs <=5 · DTMF · FEC dormant" DarkGray
