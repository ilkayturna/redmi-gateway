# Çağrı teşhis kaydı — gerçek çağrı sırasında ölçüm toplar.
# Kullanım: powershell -ExecutionPolicy Bypass -File capture-call-diagnostics.ps1 -Seconds 180
param(
    [int]$Seconds = 180,
    [string]$Serial = "17df6357",
    [string]$Label = "call"
)

$adb = "$env:USERPROFILE\platform-tools\adb.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dir = "$env:USERPROFILE\Documents\GitHub\RedmiGateway-Archive\logs\$Label-$stamp"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Write-Host "Kayit klasoru: $dir"
Write-Host "Sure: $Seconds sn — SIMDI TEST CAGRISI YAPIN"

# Wi-Fi / ag baseline
& $adb -s $Serial shell "cmd wifi status" 2>&1 | Out-File "$dir\wifi-before.txt" -Encoding UTF8
& $adb -s $Serial shell "dumpsys battery" 2>&1 | Out-File "$dir\battery-before.txt" -Encoding UTF8
& $adb -s $Serial shell "dumpsys thermalservice | head -n 20" 2>&1 | Out-File "$dir\thermal-before.txt" -Encoding UTF8

# Cagriya ozel logcat (tek surecte, filtreli)
$filter = "RedmiRinger|CallAudioRelay|CallManager|GatewayInCallService|IncomingCallPresenter|BleServerManager|SmartHotspot|GatewayService|ContactResolver|RELAY_HEALTH|AUDIO_OWNER|Executing|answered|Ringer|Telecom"
$job = Start-Job -ScriptBlock {
    param($adbPath, $serial, $file, $secs, $filt)
    & $adbPath -s $serial logcat -c 2>&1 | Out-Null
    & $adbPath -s $serial logcat -v threadtime 2>&1 | Out-File $file -Encoding UTF8
} -ArgumentList $adb, $Serial, "$dir\logcat-full.txt", $Seconds, $filter

Start-Sleep -Seconds $Seconds
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue

# Cagri sonrasi olcumler
& $adb -s $Serial shell "cmd wifi status" 2>&1 | Out-File "$dir\wifi-after.txt" -Encoding UTF8
& $adb -s $Serial shell "dumpsys battery" 2>&1 | Out-File "$dir\battery-after.txt" -Encoding UTF8
& $adb -s $Serial shell "dumpsys thermalservice | head -n 20" 2>&1 | Out-File "$dir\thermal-after.txt" -Encoding UTF8
& $adb -s $Serial shell "dumpsys meminfo com.ilkay.redmigateway | head -n 12" 2>&1 | Out-File "$dir\meminfo-after.txt" -Encoding UTF8
& $adb -s $Serial shell "dumpsys activity services com.ilkay.redmigateway | grep -E 'isForeground|types='" 2>&1 | Out-File "$dir\service-after.txt" -Encoding UTF8

# Ozet filtreler
Select-String -Path "$dir\logcat-full.txt" -Pattern "RELAY_HEALTH|AUDIO_OWNER|RedmiRinger|Executing ANSWER|Executing REJECT|Executing HANGUP|Incoming call is showing|Call audio moved|ringing_ack|resolve_contact|contact_info" 2>$null |
    ForEach-Object { $_.Line } | Set-Content "$dir\ONEMLI-SATIRLAR.txt" -Encoding UTF8

Write-Host ""
Write-Host "=== OZET ==="
Write-Host ("Onemli satir sayisi: " + (Get-Content "$dir\ONEMLI-SATIRLAR.txt" -ErrorAction SilentlyContinue | Measure-Object).Count)
Get-ChildItem $dir | Select-Object @{n='KB';e={[math]::Round($_.Length/1KB,1)}},Name | Format-Table -AutoSize
