[CmdletBinding()]
param(
    [ValidatePattern('^emulator-[0-9]+$')][string]$Serial = 'emulator-5582',
    [ValidateSet('baseline', 'home')][string]$Mode = 'baseline'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$probeAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$probePackage = 'ru.example.parentwatch.debug'
$probeHome = "$probePackage/ru.example.parentwatch.debug.HomeRecoveryProbeActivity"
$probeEvidence = Join-Path $repoRoot ".runtime/home-boot-$Mode"
New-Item -ItemType Directory -Force $probeEvidence | Out-Null
function Adb([string[]]$Arguments) {
    # Windows PowerShell treats even empty native stderr as a terminating error under Stop.
    $ErrorActionPreference = 'Continue'
    $output = & $probeAdb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output | Out-String) }
    ($output | Out-String).Trim()
}
function Stage([string]$Message) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Message) }
$hardware = Adb @('shell', 'getprop', 'ro.hardware')
if ($hardware -notin @('ranchu', 'goldfish')) { throw 'Only an isolated Android emulator is allowed.' }
$avdName = Adb @('emu', 'avd', 'name')
if ($avdName -notmatch '(?m)^ChildWatchHomeTest\s*$') { throw 'Only the dedicated ChildWatchHomeTest AVD is allowed.' }
$policy = Adb @('shell', 'dumpsys', 'device_policy')
if ($policy -match '(?m)^\s*(Device Owner:|Profile Owner \()') { throw 'Owner privileges would invalidate this experiment.' }
$assistant = Adb @('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.ASSISTANT')
if ($assistant.Contains($probePackage)) { throw 'ChildWatch must not be the selected assistant.' }
$policy | Set-Content -Encoding utf8 (Join-Path $probeEvidence 'device-policy-before.txt')
$assistant | Set-Content -Encoding utf8 (Join-Path $probeEvidence 'assistant-before.txt')
Stage 'Isolated emulator verified; preparing fake localhost session only'
foreach ($permission in @('ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'ACCESS_BACKGROUND_LOCATION', 'CAMERA', 'RECORD_AUDIO', 'POST_NOTIFICATIONS')) {
    Adb @('shell', 'pm', 'grant', $probePackage, "android.permission.$permission") | Out-Null
}
Adb @('shell', 'run-as', $probePackage, 'pm', 'enable', "$probePackage/ru.example.parentwatch.debug.HomeProbeSetupReceiver") | Out-Null
$bootProbeState = if ($Mode -eq 'baseline') { 'enable' } else { 'disable' }
# Home triggers its own capture onStop. Do not run a second baseline probe concurrently.
Adb @('shell', 'run-as', $probePackage, 'pm', $bootProbeState, "$probePackage/ru.example.parentwatch.debug.HomeProbeBootReceiver") | Out-Null
Adb @('shell', 'am', 'broadcast', '-a', 'ru.example.parentwatch.HOME_PROBE_SETUP', '-n', "$probePackage/ru.example.parentwatch.debug.HomeProbeSetupReceiver") | Out-Null
# A newly installed/stopped package does not receive boot broadcasts. Model initial setup before reboot.
Adb @('shell', 'am', 'start', '-n', "$probePackage/ru.example.parentwatch.MainActivity") | Out-Null
if ($Mode -eq 'home') {
    Adb @('shell', 'run-as', $probePackage, 'pm', 'enable', $probeHome) | Out-Null
    Adb @('shell', 'cmd', 'package', 'set-home-activity', $probeHome) | Out-Null
} else {
    $currentHome = Adb @('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.HOME')
    if ($currentHome.Contains($probePackage)) { throw 'Restore the original emulator Home before a baseline run.' }
    $currentHome | Set-Content -Encoding utf8 (Join-Path $probeEvidence 'home-before.txt')
    Adb @('shell', 'run-as', $probePackage, 'pm', 'disable', $probeHome) | Out-Null
}
Stage 'Waiting for Android to persist component/role settings before reboot'
Start-Sleep -Seconds 15
Adb @('shell', 'run-as', $probePackage, 'rm', '-f', 'files/home-probe-result.json', 'files/home-probe-camera.jpg') | Out-Null
Stage 'Real reboot; no ChildWatch Activity or instrumentation launch'
Adb @('reboot') | Out-Null
$deadline = (Get-Date).AddSeconds(180)
do {
    Start-Sleep -Seconds 3
    try { $boot = Adb @('shell', 'getprop', 'sys.boot_completed') } catch { $boot = '' }
    if ((Get-Date) -gt $deadline) { throw 'Emulator boot timed out.' }
} until ($boot -eq '1')
Stage 'Android boot completed; waiting for normal system startup'
Start-Sleep -Seconds 15
Adb @('shell', 'dumpsys', 'activity', 'services', $probePackage) |
    Set-Content -Encoding utf8 (Join-Path $probeEvidence 'services-before-capture.txt')
Adb @('shell', 'dumpsys', 'activity', 'activities') |
    Set-Content -Encoding utf8 (Join-Path $probeEvidence 'activities-after-boot.txt')
if ($Mode -eq 'home') {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        $activities = Adb @('shell', 'dumpsys', 'activity', 'activities')
        $homeRecord = [regex]::Match($activities, '(?s)\* Hist[^\r\n]*HomeRecoveryProbeActivity.*?(?=\* Hist|$)').Value
        $location = Adb @('shell', 'dumpsys', 'activity', 'services', 'ru.example.parentwatch.service.LocationService')
        $homeVisible = ($activities -match '(mResumedActivity|topResumedActivity)[=:].*HomeRecoveryProbeActivity') -and
            ($homeRecord -match 'reportedDrawn=true reportedVisible=true') -and
            ($homeRecord -match 'nowVisible=true') -and
            ($location -match 'mAllowWhileInUsePermissionInFgsReason=PROC_STATE_TOP')
        if (!$homeVisible) { Start-Sleep -Seconds 3 }
    } until ($homeVisible -or (Get-Date) -gt $deadline)
    if (!$homeVisible) { throw 'Android did not show our Home after boot; hypothesis not demonstrated.' }
    $activities | Set-Content -Encoding utf8 (Join-Path $probeEvidence 'activities-home-visible.txt')
    $location | Set-Content -Encoding utf8 (Join-Path $probeEvidence 'location-home-visible-before-capture.txt')
    Adb @('shell', 'screencap', '-p', '/data/local/tmp/home-probe-ui.png') | Out-Null
    Adb @('pull', '/data/local/tmp/home-probe-ui.png', (Join-Path $probeEvidence 'home-ui.png')) | Out-Null
    Stage 'System-selected Home appeared; moving to Settings before capture'
    Adb @('shell', 'am', 'start', '-a', 'android.settings.SETTINGS') | Out-Null
    Adb @('shell', 'input', 'keyevent', '223') | Out-Null
    Stage 'Display put to sleep; capture must complete in the background'
}
$deadline = (Get-Date).AddSeconds(100)
do {
    Start-Sleep -Seconds 5
    try { $result = Adb @('shell', 'run-as', $probePackage, 'cat', 'files/home-probe-result.json') } catch { $result = '' }
    if ((Get-Date) -gt $deadline) { throw 'The ordinary app process produced no capture result.' }
} until ($result.StartsWith('{'))
$result | Set-Content -Encoding utf8 (Join-Path $probeEvidence 'result.json')
Adb @('shell', 'dumpsys', 'activity', 'services', $probePackage) |
    Set-Content -Encoding utf8 (Join-Path $probeEvidence 'services-after-capture.txt')
Adb @('logcat', '-d', '-v', 'threadtime') |
    Set-Content -Encoding utf8 (Join-Path $probeEvidence 'logcat.txt')
Adb @('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.HOME') |
    Set-Content -Encoding utf8 (Join-Path $probeEvidence 'home-after.txt')
Stage 'Ordinary-process capture result saved (no instrumentation)'
$result
$verdict = $result | ConvertFrom-Json
if ($Mode -eq 'home' -and !$verdict.pass) { throw 'Home background capture failed; see the saved result.' }
