# Tries every non-destructive way to bring the app settings to the foreground
# on the test child phone. No security settings are changed.
[CmdletBinding()]
param([string]$UsbSerial = "102742534J001408", [string]$WifiEndpoint = "192.168.3.71:40383")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Texts { param([string]$s)
    Q @('-s', $s, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    $xml = Q @('-s', $s, 'shell', 'cat', '/sdcard/ui.xml')
    return ([regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Trim() } | Select-Object -Unique)
}
function Focus { param([string]$s) (Q @('-s', $s, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1 }

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1

$serial = $null
foreach ($c in @($UsbSerial, $WifiEndpoint)) {
    if ($c -match ':\d+$') { Q @('connect', $c) | Out-Null; Start-Sleep -Seconds 2 }
    if ((Q @('-s', $c, 'shell', 'echo', 'ok')) -match 'ok') { $serial = $c; break }
}
if (-not $serial) {
    Write-Host "DEVICE NOT FOUND. Devices now:"
    (Q @('devices')) -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host ("  " + $_.Trim()) } }
    exit 1
}
Write-Host "device: $serial"

Write-Host ""
Write-Host "=== lock state ==="
Write-Host ("  mDreamingLockscreen=" + ((Q @('-s', $serial, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'mDreamingLockscreen=' } | Select-Object -First 1))
Write-Host ("  keyguard showing: " + ((Q @('-s', $serial, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'KeyguardController|keyguardShowing' } | Select-Object -First 2))

Write-Host ""
Write-Host "=== attempt 1: wake + dismiss ==="
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 800
Q @('-s', $serial, 'shell', 'wm', 'dismiss-keyguard') | Out-Null
Start-Sleep -Seconds 2
Write-Host ("  focus: " + (Focus $serial))

Write-Host "=== attempt 2: launch settings + dismiss after ==="
Q @('-s', $serial, 'shell', 'am', 'start', '-n', "$pkg/ru.example.parentwatch.SettingsActivity") | Out-Null
Start-Sleep -Seconds 3
Q @('-s', $serial, 'shell', 'wm', 'dismiss-keyguard') | Out-Null
Start-Sleep -Seconds 2
Write-Host ("  focus: " + (Focus $serial))
$t = Texts $serial
Write-Host "  texts:"; $t | Select-Object -First 8 | ForEach-Object { Write-Host ("    - " + $_) }

Write-Host "=== attempt 3: go home, then start again ==="
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
Start-Sleep -Seconds 2
Q @('-s', $serial, 'shell', 'am', 'start', '-n', "$pkg/ru.example.parentwatch.SettingsActivity") | Out-Null
Start-Sleep -Seconds 5
Write-Host ("  focus: " + (Focus $serial))
$t2 = Texts $serial
Write-Host "  texts:"; $t2 | Select-Object -First 25 | ForEach-Object { Write-Host ("    - " + $_) }
