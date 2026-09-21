# Configures the test child phone: opens Settings, applies the VPS server URL,
# saves, and reports what was stored. ASCII-only for Windows PowerShell 5.1.
[CmdletBinding()]
param([string]$WifiEndpoint = "192.168.3.71:40383")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"
$outDir = Join-Path $repo "qa"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

function Get-Ui {
    param([string]$serial)
    Q @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    return (Q @('-s', $serial, 'shell', 'cat', '/sdcard/ui.xml'))
}

function Tap-Text {
    param([string]$serial, [string]$xml, [string]$text, [int]$index = 0)
    $pattern = '<node[^>]*text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Matches($xml, $pattern)
    if ($m.Count -eq 0) { return $false }
    $g = $m[[Math]::Min($index, $m.Count - 1)].Groups
    $cx = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
    $cy = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
    Q @('-s', $serial, 'shell', 'input', 'tap', "$cx", "$cy") | Out-Null
    Start-Sleep -Milliseconds 1200
    Write-Host "    tapped '$text' at ($cx,$cy)"
    return $true
}

# --- find device ---
& $adb start-server 2>&1 | Out-Null
$candidates = @()
foreach ($line in ((Q @('devices')) -split "`n")) {
    $t = $line.Trim()
    if ($t -match '^(\S+)\s+device$') { $candidates += $Matches[1] }
}
$candidates += $WifiEndpoint
$serial = $null
foreach ($c in $candidates) {
    if ([string]::IsNullOrWhiteSpace($c)) { continue }
    if ($c -match ':\d+$') { Q @('connect', $c) | Out-Null; Start-Sleep -Seconds 2 }
    if ((Q @('-s', $c, 'shell', 'echo', 'ok')) -match 'ok') { $serial = $c; break }
}
if (-not $serial) { Write-Host "DEVICE NOT FOUND"; exit 1 }
Write-Host "device: $serial"

Write-Host ""
Write-Host "=== 1. wake screen ==="
Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Seconds 1

Write-Host "=== 2. open Settings ==="
Q @('-s', $serial, 'shell', 'am', 'start', '-n', "$pkg/ru.example.parentwatch.SettingsActivity") | Out-Null
Start-Sleep -Seconds 5
$xml = Get-Ui $serial
[System.IO.File]::WriteAllText((Join-Path $outDir "child-settings.xml"), $xml)
$texts = [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Trim() } | Select-Object -Unique
Write-Host "  screen text:"
$texts | ForEach-Object { Write-Host ("    - " + $_) }

Write-Host ""
Write-Host "=== 3. current stored server url ==="
$prefsRaw = Q @('-s', $serial, 'shell', "run-as $pkg cat shared_prefs/parentwatch_prefs.xml")
$before = ([regex]::Match($prefsRaw, 'name="server_url">([^<]*)<')).Groups[1].Value
Write-Host "  before: '$before'"

Write-Host ""
Write-Host "=== 4. apply VPS preset ==="
# The button label carries the URL, so match on the known preset text.
$vpsTapped = $false
foreach ($candidate in @('http://31.28.27.96:3000', 'VPS', 'VPS URL')) {
    if (Tap-Text -serial $serial -xml $xml -text $candidate) { $vpsTapped = $true; break }
}
if (-not $vpsTapped) {
    Write-Host "  VPS button not found by text; looking for the input field to type into"
    $edit = [regex]::Match($xml, '<node[^>]*class="android\.widget\.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($edit.Success) {
        $g = $edit.Groups
        $cx = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
        $cy = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
        Q @('-s', $serial, 'shell', 'input', 'tap', "$cx", "$cy") | Out-Null
        Start-Sleep -Milliseconds 800
        # clear then type
        Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_MOVE_END') | Out-Null
        for ($i = 0; $i -lt 40; $i++) { Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_DEL') | Out-Null }
        Q @('-s', $serial, 'shell', 'input', 'text', 'http://31.28.27.96:3000') | Out-Null
        Start-Sleep -Milliseconds 800
        Write-Host "    typed the URL into the field"
    } else {
        Write-Host "    no EditText found either"
    }
}

Write-Host ""
Write-Host "=== 5. save ==="
# The save button label is Cyrillic; build it from code points so this script
# stays pure ASCII and Windows PowerShell 5.1 cannot mis-decode it.
$saveLabel = ([char]0x0421) + ([char]0x043E) + ([char]0x0445) + ([char]0x0440) + ([char]0x0430) +
             ([char]0x043D) + ([char]0x0438) + ([char]0x0442) + ([char]0x044C)
$xml2 = Get-Ui $serial
$tapped = $false
foreach ($label in @($saveLabel, 'Save')) {
    if (Tap-Text -serial $serial -xml $xml2 -text $label) { $tapped = $true; break }
}
if (-not $tapped) {
    Write-Host "  save button not found; trying the button id"
    $btn = [regex]::Match($xml2, '<node[^>]*resource-id="[^"]*saveButton"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($btn.Success) {
        $g = $btn.Groups
        $cx = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
        $cy = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
        Q @('-s', $serial, 'shell', 'input', 'tap', "$cx", "$cy") | Out-Null
        Write-Host "    tapped saveButton id at ($cx,$cy)"
    }
}
Start-Sleep -Seconds 3

Write-Host ""
Write-Host "=== 6. stored server url after ==="
$prefsRaw2 = Q @('-s', $serial, 'shell', "run-as $pkg cat shared_prefs/parentwatch_prefs.xml")
$after = ([regex]::Match($prefsRaw2, 'name="server_url">([^<]*)<')).Groups[1].Value
Write-Host "  after:  '$after'"
if ($after -match '^https?://[^/]+') { Write-Host "  RESULT: a real host is stored" } else { Write-Host "  RESULT: still incomplete" }
