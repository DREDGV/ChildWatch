# Reads the child app's last UI dump and dumps what MainActivity shows.
# ASCII-only.
[CmdletBinding()]
param([string]$UsbSerial = "102742534J001408", [string]$WifiEndpoint = "192.168.3.71:40383")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Dump { param([string]$s)
    Q @('-s', $s, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    return (Q @('-s', $s, 'shell', 'cat', '/sdcard/ui.xml'))
}
function Focus { param([string]$s) ((Q @('-s', $s, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1) }
function Top { param([string]$s) ((Q @('-s', $s, 'shell', 'dumpsys', 'activity', 'activities')) -split "`n" | Where-Object { $_ -match 'topResumedActivity' } | Select-Object -First 1) }

& $adb start-server 2>&1 | Out-Null
$serial = $null
foreach ($c in @($UsbSerial, $WifiEndpoint)) {
    if ($c -match ':\d+$') { Q @('connect', $c) | Out-Null; Start-Sleep -Seconds 2 }
    if ((Q @('-s', $c, 'shell', 'echo', 'ok')) -match 'ok') { $serial = $c; break }
}
if (-not $serial) { Write-Host "DEVICE NOT FOUND"; exit 1 }
Write-Host "device: $serial"

Write-Host "focus before: " (Focus $serial)
Write-Host "top before:   " (Top $serial)
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 500

Write-Host ""
Write-Host "=== try MainActivity ==="
Write-Host (Q @('-s', $serial, 'shell', 'am', 'start', '-n', "$pkg/ru.example.parentwatch.MainActivity"))
Start-Sleep -Seconds 6

$f = Focus $serial
$t = Top $serial
Write-Host ("focus after: " + $f)
Write-Host ("top after:   " + $t)

if ($t -match 'FamilyJoinActivity') {
    Write-Host ""
    Write-Host "=== FamilyJoinActivity is still on top; tapping its inner Back button if present ==="
    $xml = Dump $serial
    $back = [regex]::Match($xml, '<node[^>]*content-desc="[^"]*[Nn]avigat[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $back.Success) {
        $back = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*navigation[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    }
    if ($back.Success) {
        $g = $back.Groups
        $x = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
        $y = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
        Q @('-s', $serial, 'shell', 'input', 'tap', "$x", "$y") | Out-Null
        Write-Host "  tapped inner back at ($x,$y)"
        Start-Sleep -Seconds 3
        Write-Host ("  top now: " + (Top $serial))
    } else {
        Write-Host "  no inner back button found"
    }
}

$xml = Dump $serial
[System.IO.File]::WriteAllText((Join-Path $repo "qa\child-now.xml"), $xml)
Write-Host ""
Write-Host "=== screen text ==="
[regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_.Trim() } | Select-Object -Unique | Select-Object -First 30 |
    ForEach-Object { Write-Host ("  - " + $_) }
Write-Host ""
Write-Host "=== ids ==="
[regex]::Matches($xml, 'resource-id="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -match 'parentwatch|childwatch' } | Select-Object -Unique | Select-Object -First 25 |
    ForEach-Object { Write-Host ("  - " + $_) }
