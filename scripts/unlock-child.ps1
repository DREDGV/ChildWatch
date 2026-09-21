# Unlocks the test child phone (it has no PIN) and opens the app settings.
[CmdletBinding()]
param([string]$WifiEndpoint = "192.168.3.71:40383")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Screen-Text {
    param([string]$serial)
    Q @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    $xml = (Q @('-s', $serial, 'shell', 'cat', '/sdcard/ui.xml'))
    return ([regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
            Where-Object { $_.Trim() } | Select-Object -Unique)
}

& $adb start-server 2>&1 | Out-Null
$serial = $null
foreach ($c in @(((Q @('devices')) -split "`n" | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_.Trim() -split '\s+')[0] }) + @($WifiEndpoint))) {
    if ([string]::IsNullOrWhiteSpace($c)) { continue }
    if ($c -match ':\d+$') { Q @('connect', $c) | Out-Null; Start-Sleep -Seconds 2 }
    if ((Q @('-s', $c, 'shell', 'echo', 'ok')) -match 'ok') { $serial = $c; break }
}
if (-not $serial) { Write-Host "DEVICE NOT FOUND"; exit 1 }
Write-Host "device: $serial"

Write-Host "=== unlock attempts ==="
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 500
Q @('-s', $serial, 'shell', 'wm', 'dismiss-keyguard') | Out-Null
Start-Sleep -Seconds 1
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_MENU') | Out-Null
Start-Sleep -Seconds 1
Q @('-s', $serial, 'shell', 'input', 'swipe', '540', '2200', '540', '300', '150') | Out-Null
Start-Sleep -Seconds 2

Write-Host "=== screen now ==="
(Screen-Text $serial) | Select-Object -First 10 | ForEach-Object { Write-Host ("  - " + $_) }

Write-Host "=== open app settings ==="
Q @('-s', $serial, 'shell', 'am', 'start', '-n', "$pkg/ru.example.parentwatch.SettingsActivity") | Out-Null
Start-Sleep -Seconds 5
$texts = Screen-Text $serial
Write-Host "  settings screen text:"
$texts | Select-Object -First 40 | ForEach-Object { Write-Host ("    - " + $_) }

Write-Host "=== focus ==="
(Q @('-s', $serial, 'shell', 'dumpsys', 'window')) -split "`n" |
    Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 2 | ForEach-Object { Write-Host ("  " + $_.Trim()) }
