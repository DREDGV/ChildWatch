# Drives the child app to its settings screen: launches it, leaves the join
# wizard, taps the settings card, enters the in-app PIN, reports each step.
# ASCII-ONLY on purpose: Windows PowerShell 5.1 reads script files as ANSI and
# any non-ASCII literal breaks parsing. Match on activity names and ids only.
[CmdletBinding()]
param(
    [string]$UsbSerial = "102742534J001408",
    [string]$WifiEndpoint = "192.168.3.71:40383",
    [string]$Pin = "2502"
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"
$outDir = Join-Path $repo "qa"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Dump { param([string]$s)
    Q @('-s', $s, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
    return (Q @('-s', $s, 'shell', 'cat', '/sdcard/ui.xml'))
}
function Focus { param([string]$s) ((Q @('-s', $s, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1) }
function ShowAll { param([string]$xml, [string]$label)
    Write-Host ("  " + $label)
    [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_.Trim() } | Select-Object -Unique | Select-Object -First 20 |
        ForEach-Object { Write-Host ("    - " + $_) }
}
function TapAt { param([string]$s, [int]$x, [int]$y)
    Q @('-s', $s, 'shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Seconds 1
    Write-Host ("    tap ($x,$y)")
}
function TapBounds { param([string]$s, [System.Text.RegularExpressions.Match]$m)
    $g = $m.Groups
    TapAt -s $s -x ([int](([int]$g[1].Value + [int]$g[3].Value) / 2)) -y ([int](([int]$g[2].Value + [int]$g[4].Value) / 2))
}
function TapById { param([string]$s, [string]$xml, [string]$id)
    $p = '<node[^>]*resource-id="' + [regex]::Escape($id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $p)
    if (-not $m.Success) { return $false }
    TapBounds -s $s -m $m
    return $true
}
function TapByText { param([string]$s, [string]$xml, [string]$text)
    $p = '<node[^>]*text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $p)
    if (-not $m.Success) { return $false }
    TapBounds -s $s -m $m
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

Write-Host ""
Write-Host "=== 1. launch app ==="
Q @('-s', $serial, 'shell', 'monkey', '-p', $pkg, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 6
Write-Host ("  focus: " + (Focus $serial))

Write-Host "=== 2. leave the join wizard if shown ==="
for ($i = 0; $i -lt 3; $i++) {
    $f = Focus $serial
    if ($f -notmatch 'FamilyJoinActivity') { break }
    Q @('-s', $serial, 'shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Seconds 2
    Q @('-s', $serial, 'shell', 'monkey', '-p', $pkg, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Start-Sleep -Seconds 4
}
Start-Sleep -Seconds 2
$xml = Dump $serial
Write-Host ("  focus: " + (Focus $serial))
ShowAll -xml $xml -label "screen text:"

Write-Host "=== 3. open settings ==="
$opened = TapById -s $serial -xml $xml -id "$pkg`:id/settingsCard"
if (-not $opened) { Write-Host "  settingsCard not found; dumping ids" }
Start-Sleep -Seconds 3
$xml = Dump $serial
Write-Host ("  focus: " + (Focus $serial))
ShowAll -xml $xml -label "after tapping settings:"

Write-Host "=== 4. PIN dialog handling ==="
$hasKeypad = $xml -match 'text="1"' -and $xml -match 'text="2"'
if ($hasKeypad) {
    Write-Host "  keypad detected; tapping the PIN digits"
    foreach ($d in $Pin.ToCharArray()) {
        if (TapByText -s $serial -xml $xml -text $d) { Start-Sleep -Milliseconds 400 }
    }
    Start-Sleep -Seconds 1
    $xml = Dump $serial
    foreach ($label in @('OK', 'Next', 'Done')) {
        if (TapByText -s $serial -xml $xml -text $label) { break }
    }
    Start-Sleep -Seconds 3
    $xml = Dump $serial
    Write-Host ("  focus: " + (Focus $serial))
    ShowAll -xml $xml -label "after PIN:"
} else {
    Write-Host "  no keypad on this screen"
}

[System.IO.File]::WriteAllText((Join-Path $outDir "child-ui-last.xml"), $xml)
Write-Host ""
Write-Host "saved: qa\child-ui-last.xml"
