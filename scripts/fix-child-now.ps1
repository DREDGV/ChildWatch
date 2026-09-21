# Closes the notification shade, unlocks the (PIN-less) keyguard, opens the app
# settings and applies the VPS server URL, all in one adb session.
[CmdletBinding()]
param([string]$UsbSerial = "102742534J001408", [string]$WifiEndpoint = "192.168.3.71:40383")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function UiXml { param([string]$s)
    Q @('-s', $s, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    return (Q @('-s', $s, 'shell', 'cat', '/sdcard/ui.xml'))
}
function Texts { param([string]$s)
    $xml = UiXml $s
    return ([regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Trim() } | Select-Object -Unique)
}
function TapText { param([string]$s, [string]$xml, [string]$text)
    $p = '<node[^>]*text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Matches($xml, $p)
    if ($m.Count -eq 0) { return $false }
    $g = $m[0].Groups
    $cx = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
    $cy = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
    Q @('-s', $s, 'shell', 'input', 'tap', "$cx", "$cy") | Out-Null
    Start-Sleep -Milliseconds 1200
    Write-Host ("    tapped '" + $text + "' at (" + $cx + "," + $cy + ")")
    return $true
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1
$serial = $null
foreach ($c in @($UsbSerial, $WifiEndpoint)) {
    if ($c -match ':\d+$') { Q @('connect', $c) | Out-Null; Start-Sleep -Seconds 2 }
    if ((Q @('-s', $c, 'shell', 'echo', 'ok')) -match 'ok') { $serial = $c; break }
}
if (-not $serial) { Write-Host "DEVICE NOT FOUND"; exit 1 }
Write-Host "device: $serial"

Write-Host "=== 1. close notification shade ==="
for ($i = 0; $i -lt 3; $i++) {
    Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Milliseconds 700
}
Q @('-s', $serial, 'shell', 'cmd', 'statusbar', 'collapse') | Out-Null
Start-Sleep -Seconds 1

Write-Host "=== 2. unlock keyguard ==="
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 700
Q @('-s', $serial, 'shell', 'wm', 'dismiss-keyguard') | Out-Null
Start-Sleep -Seconds 1
# Non-secure keyguard also dismisses on a long upward swipe from the very bottom.
Q @('-s', $serial, 'shell', 'input', 'swipe', '540', '2400', '540', '200', '120') | Out-Null
Start-Sleep -Seconds 2
for ($i = 0; $i -lt 2; $i++) {
    Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_MENU') | Out-Null
    Start-Sleep -Milliseconds 700
}
Write-Host ("  focus now: " + ((Q @('-s', $serial, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1))

Write-Host "=== 3. open app settings ==="
Q @('-s', $serial, 'shell', 'am', 'start', '-n', "$pkg/ru.example.parentwatch.SettingsActivity") | Out-Null
Start-Sleep -Seconds 5
$xml = UiXml $serial
$t = [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Trim() } | Select-Object -Unique
Write-Host "  texts:"
$t | Select-Object -First 30 | ForEach-Object { Write-Host ("    - " + $_) }

# Detect the lockscreen by code point: the word "SIM" plus a Cyrillic letter,
# so this script stays pure ASCII.
$lockscreenMarker = ([char]0x041D) + ([char]0x0435) + ([char]0x0442) + ' SIM'
if (($t -join ' ') -match [regex]::Escape($lockscreenMarker)) {
    Write-Host ""
    Write-Host "STILL LOCKED - the app settings are not reachable without a manual unlock."
    exit 3
}

Write-Host "=== 4. apply VPS preset and save ==="
$tapped = $false
foreach ($candidate in @('http://31.28.27.96:3000', 'VPS URL', 'VPS')) {
    if (TapText -s $serial -xml $xml -text $candidate) { $tapped = $true; break }
}
if (-not $tapped) {
    $edit = [regex]::Match($xml, '<node[^>]*class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($edit.Success) {
        $g = $edit.Groups
        $cx = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
        $cy = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
        Q @('-s', $serial, 'shell', 'input', 'tap', "$cx", "$cy") | Out-Null
        Start-Sleep -Milliseconds 800
        for ($i = 0; $i -lt 40; $i++) { Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_DEL') | Out-Null }
        Q @('-s', $serial, 'shell', 'input', 'text', 'http://31.28.27.96:3000') | Out-Null
        Write-Host "    typed the URL directly"
    }
}
$xml2 = UiXml $serial
$saveLabel = ([char]0x0421)+([char]0x043E)+([char]0x0445)+([char]0x0440)+([char]0x0430)+([char]0x043D)+([char]0x0438)+([char]0x0442)+([char]0x044C)
if (-not (TapText -s $serial -xml $xml2 -text $saveLabel)) { TapText -s $serial -xml $xml2 -text 'Save' | Out-Null }
Start-Sleep -Seconds 3

Write-Host "=== 5. stored value ==="
$after = ([regex]::Match((Q @('-s', $serial, 'shell', "run-as $pkg cat shared_prefs/parentwatch_prefs.xml")), 'name="server_url">([^<]*)<')).Groups[1].Value
Write-Host ("  server_url = '" + $after + "'")
if ($after -match '^https?://[^/]+$|^https?://[^/]+/') { Write-Host "  RESULT: OK" } else { Write-Host "  RESULT: still incomplete" }
