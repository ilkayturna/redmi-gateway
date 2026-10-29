# Installs the gateway APK on the Redmi only when no call exists (AGENTS 0.4 and 0.6).
#
# 29.10.2026 13:51: an install was chained after a call check in one command line, the check
# printed an ACTIVE call and the install ran anyway. Telecom handed the live call to the ROM's
# dialer, which crash-looped 84 times, and the call stayed without audio on the iPhone. This
# script refuses instead: any call, or a check that returns nothing, stops it before adb install.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File RedmiGateway\scripts\device\guvenli-kur.ps1 [-Apk <path>] [-Seri <serial>]
# Exit codes: 0 installed and verified, 2 refused (call or unknown state), 1 failed.

param(
    [string]$Adb = "C:\Users\ilkay.turna\platform-tools\adb.exe",
    [string]$Seri = "",
    [string]$Apk = ""
)

# Native adb writes harmless warnings to stderr (AGENTS 1.2); they must not stop the script.
$ErrorActionPreference = "Continue"
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if (-not $Apk) { $Apk = Join-Path $repo "RedmiGateway\android\app\build\outputs\apk\debug\app-debug.apk" }

function Invoke-Adb([string[]]$Arguments) {
    $output = & $Adb @Arguments 2>&1 | Out-String
    return $output
}

if (-not (Test-Path $Apk)) { Write-Output "APK not found: $Apk"; exit 1 }
$apkItem = Get-Item $Apk
# AGENTS 7.1: say what is installed and how old it is; refuse a build from another checkout.
if (-not $apkItem.FullName.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Output "Refused: the APK is outside this repository ($($apkItem.FullName))"
    exit 2
}
$ageHours = [math]::Round(((Get-Date) - $apkItem.LastWriteTime).TotalHours, 1)
Write-Output "APK: $($apkItem.FullName) ($([math]::Round($apkItem.Length / 1MB, 2)) MB, built $ageHours h ago)"
if ($ageHours -gt 168) { Write-Output "WARNING: the APK is more than 7 days old" }

if (-not $Seri) {
    $devices = @((Invoke-Adb @("devices")) -split "`r?`n" | Where-Object { $_ -match "^\S+\s+device$" })
    if ($devices.Count -ne 1) { Write-Output "Refused: expected exactly one device, found $($devices.Count). Pass -Seri."; exit 2 }
    $Seri = ($devices[0] -split "\s+")[0]
}
Write-Output "Device: $Seri"

# AGENTS 0.6: both SIM slots, every call; an empty answer is "unknown", never "no call".
$telecom = Invoke-Adb @("-s", $Seri, "shell", "dumpsys telecom")
if ($telecom -notmatch "mCalls:") { Write-Output "Refused: the call state could not be read"; exit 2 }
$callsBlock = ($telecom -split "mCallAudioManager")[0]
$callsBlock = ($callsBlock -split "mCalls:")[-1]
$liveStates = @([regex]::Matches($callsBlock, "state=([A-Z_]+)") | ForEach-Object { $_.Groups[1].Value })
if ($liveStates.Count -gt 0) {
    Write-Output "Refused: the phone has a call now ($($liveStates -join ', ')). Try again when it is idle."
    exit 2
}
Write-Output "No call on the phone; installing"

$install = Invoke-Adb @("-s", $Seri, "install", "-r", $apkItem.FullName)
Write-Output $install.Trim()
if ($install -notmatch "Success") { exit 1 }
Start-Sleep -Seconds 5

$package = Invoke-Adb @("-s", $Seri, "shell", "dumpsys package com.ilkay.redmigateway")
$version = [regex]::Matches($package, "versionName=(\S+)") | Select-Object -First 1
$granted = @(@("CAPTURE_AUDIO_OUTPUT", "CALL_AUDIO_INTERCEPTION", "MODIFY_PHONE_STATE", "TETHER_PRIVILEGED") |
    Where-Object { $package -match "android\.permission\.$($_): granted=true" })
$processId = (Invoke-Adb @("-s", $Seri, "shell", "pidof com.ilkay.redmigateway")).Trim()
$dialer = (Invoke-Adb @("-s", $Seri, "shell", "cmd role get-role-holders android.app.role.DIALER")).Trim()
Write-Output "Installed: $($version.Groups[1].Value); privileged permissions granted: $($granted.Count)/4; pid: $processId; dialer role: $dialer"
if ($granted.Count -ne 4 -or -not $processId -or $dialer -ne "com.ilkay.redmigateway") {
    Write-Output "VERIFY FAILED: roll back with: adb -s $Seri shell pm uninstall-system-updates com.ilkay.redmigateway"
    exit 1
}
exit 0
