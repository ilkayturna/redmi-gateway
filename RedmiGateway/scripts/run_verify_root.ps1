# Push verify_root.sh to the connected Redmi, run it, and pull the report back.
#
# Yollar bu betigin kendi konumundan cozulur. Onceden sabit olarak
# c:\Users\ilkay.turna\Documents\antigravity\elegant-borg\ icinde yaziyordu - baska bir
# checkout. O dizin hala duruyor, yani betik ORADAKI (eski) verify_root.sh'i push ediyordu.
# Ayni sinif hata scripts/watch_and_configure.py'de de bulundu ve orada ciddi sonucu vardi.
#
# Kullanim:  powershell -ExecutionPolicy Bypass -File run_verify_root.ps1 [-Serial 17df6357]

param(
    [string]$Serial = ""
)

$ErrorActionPreference = "Continue"

$adb = "C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "$env:LOCALAPPDATA\NucumBuildTools\android-sdk\platform-tools\adb.exe"
}
if (-not (Test-Path $adb)) {
    Write-Host "HATA: adb bulunamadi." -ForegroundColor Red
    exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$verifySh = Join-Path $scriptDir "verify_root.sh"
$localOut = Join-Path (Split-Path -Parent $scriptDir) "docs\root_first_boot"

if (-not (Test-Path $verifySh)) {
    Write-Host "HATA: verify_root.sh bulunamadi: $verifySh" -ForegroundColor Red
    exit 1
}

$adbArgs = @()
if ($Serial) { $adbArgs = @("-s", $Serial) }

Write-Host "verify_root.sh : $verifySh"
Write-Host "cikti klasoru  : $localOut"
New-Item -ItemType Directory -Force -Path $localOut | Out-Null

Write-Host "Pushing verify_root.sh to device..."
& $adb @adbArgs push $verifySh /data/local/tmp/verify_root.sh
& $adb @adbArgs shell "chmod 755 /data/local/tmp/verify_root.sh"

Write-Host "Executing root verification on device..."
& $adb @adbArgs shell "/data/local/tmp/verify_root.sh"

Write-Host "Pulling verification logs to $localOut..."
& $adb @adbArgs pull /sdcard/Download/root_first_boot/. "$localOut/"
Write-Host "Root verification logs stored in $localOut"
