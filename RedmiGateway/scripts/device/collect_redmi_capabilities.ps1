[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial = '17df6357',
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
# Read-only snapshot. Raw ADB output stays in memory and is never printed or saved.
$candidates = @('C:\Users\ilkay.turna\platform-tools\adb.exe', 'C:\Users\ilkay.turna\AppData\Local\NucumBuildTools\android-sdk\platform-tools\adb.exe')
foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
    if ($sdk) { $candidates += Join-Path $sdk 'platform-tools\adb.exe' }
}
$adb = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $adb) {
    $installed = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($installed) { $adb = $installed.Source }
}
if (-not $adb) { throw 'ADB is unavailable. No report was created.' }

function Invoke-Adb([string[]]$Arguments) {
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $adb
    # All arguments originate from constants or the validated serial; none contain quotes.
    $process.StartInfo.Arguments = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(12000)) {
            try { $process.Kill() } catch { }
            return @{ success = $false; reason = 'timeout'; text = '' }
        }
        if (-not $stdout.Wait(1000) -or -not $stderr.Wait(1000)) {
            return @{ success = $false; reason = 'output_timeout'; text = '' }
        }
        if ($process.ExitCode -ne 0) { return @{ success = $false; reason = 'command_failed'; text = '' } }
        return @{ success = $true; reason = $null; text = $stdout.Result }
    } catch { return @{ success = $false; reason = 'command_failed'; text = '' } }
    finally { $process.Dispose() }
}
function Read-Shell([string[]]$Arguments) { Invoke-Adb (@('-s', $Serial, 'shell') + $Arguments) }
function Unavailable([string]$Unit, [string]$Reason = 'not_reported_or_not_readable') {
    @{ available = $false; value = $null; unit = $Unit; reason = $Reason }
}
function Extract($Result, [string]$Pattern, [string]$Unit, [switch]$Numeric) {
    if (-not $Result.success) { return Unavailable $Unit $Result.reason }
    $match = [regex]::Match($Result.text, $Pattern, 'Multiline')
    if (-not $match.Success) { return Unavailable $Unit }
    $value = $match.Groups[1].Value.Trim()
    if ($Numeric) {
        $number = 0L
        if (-not [long]::TryParse($value, [ref]$number)) { return Unavailable $Unit 'invalid_number' }
        $value = $number
    }
    @{ available = $true; value = $value; unit = $Unit }
}
function Assert-SelectedDevice {
    $devices = Invoke-Adb -Arguments @('devices')
    if (-not $devices.success) { throw 'ADB device enumeration failed. No report was created.' }
    $selected = [regex]::Match($devices.text, '(?m)^' + [regex]::Escape($Serial) + '\s+(\S+)\s*$')
    if (-not $selected.Success -or $selected.Groups[1].Value -ne 'device') {
        throw "Selected device $Serial is absent, offline, or unauthorized. No report was created."
    }
}
# Do not create the destination until the explicitly selected device is authorized.
Assert-SelectedDevice
$started = [DateTimeOffset]::UtcNow.ToString('o')
$os = [ordered]@{}
foreach ($pair in @(
    @('model', 'ro.product.model'), @('device', 'ro.product.device'),
    @('release', 'ro.build.version.release'), @('sdk', 'ro.build.version.sdk'),
    @('security_patch', 'ro.build.version.security_patch')
)) { $os[$pair[0]] = Extract (Read-Shell -Arguments @('getprop', $pair[1])) '^([^\r\n]+)\s*$' 'text' }
$usb = [ordered]@{}
foreach ($field in @('config', 'state')) {
    $usb[$field] = Extract (Read-Shell -Arguments @('getprop', "sys.usb.$field")) '^([a-zA-Z0-9_,.-]+)\s*$' 'text'
}
$packages = [ordered]@{}
foreach ($package in @('com.ilkay.redmigateway', 'com.google.android.projection.gearhead')) {
    $raw = Read-Shell -Arguments @('dumpsys', 'package', $package)
    $presence = 'unavailable'
    if ($raw.success -and $raw.text -match ('Package \[' + [regex]::Escape($package) + '\]')) { $presence = 'present' }
    elseif ($raw.success -and $raw.text -match 'Unable to find package|Unknown package') { $presence = 'absent' }
    $packages[$package] = @{
        presence = $presence
        versionCode = Extract $raw '^\s*versionCode=(\d+)\b' 'integer' -Numeric
        versionName = Extract $raw '^\s*versionName=([^\r\n]+)' 'text'
        targetSdk = Extract $raw '^\s*versionCode=\d+[^\r\n]*\btargetSdk=(\d+)\b' 'integer' -Numeric
        enabled = Extract $raw '^\s*User 0:[^\r\n]*\benabled=(\d+)\b' 'Android enabled state enum' -Numeric
        enabled_note = 'User 0: 0=default, 1=enabled, 2=disabled, 3=disabled by user, 4=disabled until used; default does not alone establish usability.'
    }
    $raw = $null
}
$raw = Read-Shell -Arguments @('dumpsys', 'battery')
$battery = [ordered]@{}
foreach ($pair in @(
    @('level', 'raw level'), @('scale', 'raw scale'), @('status', 'Android battery status enum'),
    @('health', 'Android battery health enum'), @('voltage', 'mV'), @('temperature', '0.1 deg C'),
    @('Charge counter', 'uAh'), @('Max charging current', 'uA'), @('Max charging voltage', 'uV')
)) { $battery[$pair[0]] = Extract $raw ('^\s*' + [regex]::Escape($pair[0]) + ':\s*(-?\d+)\s*$') $pair[1] -Numeric }
foreach ($field in @('AC powered', 'USB powered', 'Wireless powered', 'present')) {
    $battery[$field] = Extract $raw ('^\s*' + [regex]::Escape($field) + ':\s*(true|false)\s*$') 'boolean text'
}
$raw = $null
$sysfs = [ordered]@{}
foreach ($pair in @(
    @('current_now', 'uA'), @('voltage_now', 'uV'), @('charge_counter', 'uAh'),
    @('capacity', 'percent'), @('temp', '0.1 deg C'), @('cycle_count', 'cycles'),
    @('charge_full', 'uAh'), @('charge_full_design', 'uAh')
)) {
    $sysfs[$pair[0]] = Extract (Read-Shell -Arguments @('cat', "/sys/class/power_supply/battery/$($pair[0])")) '^\s*(-?\d+)\s*$' $pair[1] -Numeric
}
$raw = Read-Shell -Arguments @('dumpsys', 'power')
$power = [ordered]@{
    wakefulness = Extract $raw '^\s*mWakefulness=(Awake|Asleep|Dreaming|Dozing)\s*$' 'enum'
    powered = Extract $raw '^\s*mIsPowered=(true|false)\s*$' 'boolean text'
    wakelock_summary = Extract $raw '^\s*mWakeLockSummary=(0x[0-9a-fA-F]+|\d+)\s*$' 'bitmask'
    gateway_locks = @()
}
foreach ($tag in @('RedmiGateway::WakeLock', 'RedmiGateway:CallProximity')) {
    $count = Unavailable 'matching active power wakelocks' $(if ($raw.success) { 'wakelock_header_not_reported' } else { $raw.reason })
    if ($raw.success -and $raw.text -match '(?m)^\s*Wake Locks:') {
        $matches = [regex]::Matches($raw.text, "(?m)^\s*\S+\s+'" + [regex]::Escape($tag) + "'[^\r\n]*$")
        $count = @{ available = $true; value = $matches.Count; unit = 'matching active power wakelocks' }
    }
    $power.gateway_locks += @{ tag = $tag; count = $count }
}
$raw = $null
# A disconnect during collection must not create a misleading snapshot.
Assert-SelectedDevice
$report = [ordered]@{
    schema_version = 1; serial = $Serial; collection_started_utc = $started
    collection_finished_utc = [DateTimeOffset]::UtcNow.ToString('o')
    limitations = @(
        'Read-only snapshot; this is not a discharge-rate, battery-wear, audio-quality, or car-compatibility diagnosis.',
        'USB connection may charge the phone; see battery powered fields and USB state.',
        'Charge counter is remaining charge, not full capacity. Current polarity is device-specific.',
        'Unavailable is distinct from zero. Sysfs units are standard power_supply units; vendor implementation is unverified.',
        'No root capability test, permissions/settings change, raw logs, contacts, calls, network identifiers, or global battery history.'
    )
    os = $os; usb = $usb; packages = $packages; battery = $battery; battery_sysfs = $sysfs; power = $power
}
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PSScriptRoot 'reports' }
$directory = [IO.Path]::GetFullPath($OutputDirectory)
$json = $report | ConvertTo-Json -Depth 10
[void][IO.Directory]::CreateDirectory($directory)
$file = Join-Path $directory ("redmi-capabilities-{0}-{1}.json" -f $Serial.Replace(':', '_'), [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
[IO.File]::WriteAllText($file, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "Read-only snapshot saved: $file"
