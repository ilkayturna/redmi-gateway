# Çağrı teşhis logunu telefondan çeker ve ölçümleri çıkarır.
# Kullanım: powershell -ExecutionPolicy Bypass -File analyze-call-diagnostics.ps1
#          powershell ... -Serial 192.168.1.143:5555   (kablosuz ADB)
#          powershell ... -Serial auto                 (bağlı ilk cihazı seç)
param(
    [string]$Serial = "auto",
    [switch]$NoPull   # yalnızca cihazdaki dosyayı oku, kopyalama
)

$adb = "$env:USERPROFILE\platform-tools\adb.exe"
$devicePath = "/sdcard/Android/data/com.ilkay.redmigateway/files/diagnostics/call-diagnostics.log"
$outDir = "$env:USERPROFILE\Documents\GitHub\RedmiGateway-Archive\logs\diagnostics"

# Cihaz seçimi: sabit seri, "auto" ya da kablosuz adres. Bağlı değilse net hata ver;
# yoksa ADB'nin hata metni sayı sanılıp yanlış alarm üretiyor.
$deviceList = (& $adb devices 2>&1) -join "`n"
$connected = @()
foreach ($line in ($deviceList -split "`n")) {
    if ($line -match '^(\S+)\s+device\b') { $connected += $Matches[1] }
}
if ($Serial -eq "auto") {
    if ($connected.Count -eq 0) { Write-Host "HATA: Bagli cihaz yok. Kabloyu takin ya da 'adb connect <ip>:5555' calistirin."; exit 2 }
    $Serial = $connected[0]
} elseif ($connected -notcontains $Serial) {
    Write-Host "HATA: '$Serial' bagli degil. Bagli cihazlar: $($connected -join ', ')"
    exit 2
}
$Serial = $Serial.Trim()
Write-Host "Cihaz: $Serial"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$local = Join-Path $outDir "call-diagnostics-$stamp.log"

if ($NoPull) {
    $lines = & $adb -s $Serial shell "cat $devicePath" 2>&1
    $lines | Set-Content $local -Encoding UTF8
} else {
    & $adb -s $Serial pull $devicePath $local 2>&1 | Select-Object -Last 1
}
if (-not (Test-Path $local) -or (Get-Item $local).Length -eq 0) { Write-Host "Log okunamadi: $devicePath"; exit 1 }
Write-Host "Log: $local ($([math]::Round((Get-Item $local).Length/1KB,1)) KB)"
Write-Host ""

$lines = Get-Content $local

function Stamp([string]$line) {
    # "10-15 23:36:35.131 Call: ..." -> DateTime
    if ($line -match '^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)') {
        return [datetime]::new((Get-Date).Year, [int]$Matches[1], [int]$Matches[2],
            [int]$Matches[3], [int]$Matches[4], [int]$Matches[5], [int]$Matches[6])
    }
    return $null
}

Write-Host "=== 1) ZİL SENKRONİZASYONU (hedef: <=300 ms) ==="
$answer = $lines | Where-Object { $_ -match 'Executing ANSWER on call' } | Select-Object -First 1
$ringerOff = $lines | Where-Object { $_ -match 'RINGER_OFF reason=peer_answered' } | Select-Object -First 1
if ($answer -and $ringerOff) {
    $a = Stamp $answer; $b = Stamp $ringerOff
    if ($a -and $b) { "  iPhone'da yanit -> Redmi sustu: {0:N0} ms" -f ($b - $a).TotalMilliseconds }
} else {
    "  (kayit yok - bu senaryo yasanmadi)"
}

$answering = $lines | Where-Object { $_ -match 'Executing ANSWER on call' } | Select-Object -First 1
$stateActive = $lines | Where-Object { $_ -match 'RINGER_OFF reason=call_state_active' } | Select-Object -First 1
if ($answering -and $stateActive) {
    $a = Stamp $answering; $b = Stamp $stateActive
    if ($a -and $b) { "  Redmi'de yanit -> Redmi zili: {0:N0} ms" -f ($b - $a).TotalMilliseconds }
}
$rings = $lines | Where-Object { $_ -match 'Redmi rings:' }
"  Redmi zil sayisi (cift calma gostergesi): $($rings.Count)"

Write-Host ""
Write-Host "=== 2) SES GEÇİŞİ (hedef: <1000 ms) ==="
$prep = $lines | Where-Object { $_ -match 'AUDIO_OWNER=PREPARING_IPHONE' } | Select-Object -First 1
$iphone = $lines | Where-Object { $_ -match 'AUDIO_OWNER=IPHONE reason=iphone_confirmed' } | Select-Object -First 1
if ($prep -and $iphone) {
    $a = Stamp $prep; $b = Stamp $iphone
    if ($a -and $b) { "  PREPARING -> IPHONE: {0:N0} ms" -f ($b - $a).TotalMilliseconds }
} else { "  (kayit yok)" }

# Yeni bütçe satırı: "Call audio moved to iPhone (uplink=X/s downlink=Y/s settle=Ams firstWrite=Bms
# firstRead=Cms firstPcm=Pms startMedia=Sms)" - 2.5 sn'nin nerede geçtiğini üçe ayırır:
# firstPcm = iPhone'un ilk ses karesi, startMedia-firstPcm = bizim kapı, settle = doğrulama.
$budget = $lines | Where-Object { $_ -match 'Call audio moved to iPhone \(' } | Select-Object -Last 1
if ($budget) {
    Write-Host "  --- BÜTÇE (son geçiş) ---"
    $vals = @{}
    foreach ($k in @('settle','firstWrite','firstRead','firstPcm','startMedia')) {
        if ($budget -match "$k=(\d+)ms") { $vals[$k] = [int]$Matches[1] }
    }
    if ($vals.ContainsKey('firstPcm'))  { "    iPhone'un ilk ses karesi (firstPcm) : {0} ms" -f $vals['firstPcm'] }
    if ($vals.ContainsKey('startMedia')) { "    Bizim kapı (startMedia)            : {0} ms" -f $vals['startMedia'] }
    if ($vals.ContainsKey('firstPcm') -and $vals.ContainsKey('startMedia')) {
        "    -> KAPI GECIKMESI (bizde)          : {0} ms" -f ($vals['startMedia'] - $vals['firstPcm'])
    }
    if ($vals.ContainsKey('settle')) { "    Doğrulama (settle)                 : {0} ms" -f $vals['settle'] }
    if ($vals.ContainsKey('firstWrite')) { "    Ilk yazma (firstWrite)             : {0} ms" -f $vals['firstWrite'] }
    if ($vals.ContainsKey('firstRead'))  { "    Ilk okuma (firstRead)              : {0} ms" -f $vals['firstRead'] }
    $budget.Trim()
} else { "  (yeni butce satiri yok - eski surum kurulu olabilir)" }

Write-Host ""
Write-Host "=== 2b) GİDEN ARAMADA iPhone UYANDIRMA ==="
$dial = $lines | Where-Object { $_ -match 'Initiating outgoing GSM call' } | Select-Object -Last 1
$beacon = $lines | Where-Object { $_ -match 'Call wake beacon on' } | Select-Object -Last 1
$connected = $lines | Where-Object { $_ -match 'iPhone connected \(protocol' } | Select-Object -Last 1
if ($dial) {
    "  Giden arama basladi: " + $dial.Trim()
    if ($beacon) {
        $a = Stamp $dial; $b = Stamp $beacon
        if ($a -and $b) { "  -> Beacon yandi: {0:N0} ms sonra  (hedef: ~100 ms)" -f ($b - $a).TotalMilliseconds }
        if ($connected) {
            $c = Stamp $connected
            if ($a -and $c) { "  -> iPhone baglandi: {0:N0} ms sonra" -f ($c - $a).TotalMilliseconds }
        } else { "  -> DIKKAT: iPhone baglanmadi (uyanma zinciri kirik olabilir)" }
    } else { "  -> DIKKAT: beacon yanmadi (giden cagri uyandirmasi calismiyor)" }
} else { "  (giden arama kaydi yok)" }

$fallback = $lines | Where-Object { $_ -match 'Returning call audio to Redmi|Cutover verification failed|No iPhone on the control link' }
if ($fallback) { "  DIKKAT - ses geri dondu/basarisiz/kanal yok: $($fallback.Count) satir"; $fallback | Select-Object -First 3 | ForEach-Object { "    " + $_.Trim() } }

Write-Host ""
Write-Host "=== 3) SES KALİTESİ (RELAY_HEALTH) ==="
$health = $lines | Where-Object { $_ -match 'RELAY_HEALTH' }
if ($health) {
    $last = $health | Select-Object -Last 1
    $last.Trim()
    $u = @(); $d = @(); $w = @()
    foreach ($h in $health) {
        if ($h -match 'underruns=(\d+)') { $u += [int]$Matches[1] }
        if ($h -match 'dropped=(\d+)')    { $d += [int]$Matches[1] }
        if ($h -match 'maxWriteMs=(\d+)') { $w += [int]$Matches[1] }
    }
    if ($u.Count) { "  underrun: ilk={0} son={1} (hedef: <5)" -f $u[0], $u[-1] }
    if ($d.Count) { "  dropped : ilk={0} son={1} (hedef: 0)" -f $d[0], $d[-1] }
    if ($w.Count) { "  maxWriteMs: en kotu={0} ms (hedef: <=5, eski: 71)" -f ($w | Measure-Object -Maximum).Maximum }
    $silence = $health | Where-Object { $_ -match 'silence=([1-9]\d*)/s' }
    "  sessizlik enjekte edilen olcum: $($silence.Count)"
} else { "  (çağrıda RELAY_HEALTH yok)" }

Write-Host ""
Write-Host "=== 4) VERİ KORUMASI (hotspot) ==="
$lines | Where-Object { $_ -match 'Known Wi-Fi .* in range|hotspot off to save|Wi-Fi is off; hotspot stays|keeping hotspot for the call|Wi-Fi with internet joined' } |
    Select-Object -Last 10 | ForEach-Object { "  " + $_.Trim() }

Write-Host ""
Write-Host "=== 5) iOS TARAFI (varsa) ==="
$lines | Where-Object { $_ -match 'showing the call as incoming again|No ack for|peer_answered_stale' } |
    Select-Object -Last 5 | ForEach-Object { "  " + $_.Trim() }

Write-Host ""
Write-Host "=== ÖZET ==="
"  Toplam satir: $($lines.Count)"
"  Kayit araligi: " + (($lines | Select-Object -First 1) -replace '^(\S+ \S+).*','$1') + "  ->  " + (($lines | Select-Object -Last 1) -replace '^(\S+ \S+).*','$1')
