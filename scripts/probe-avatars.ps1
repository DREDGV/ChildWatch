# Taps avatar presets on the parent invite screen and captures a screenshot
# before/after, so the selection highlight can be compared from the image.
# ASCII-only.
[CmdletBinding()]
param([string]$Serial = "PT19655KA1280800674")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$outDir = Join-Path $repo "qa"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Dump { Q @('-s', $Serial, 'shell', 'uiautomator', 'dump', '/sdcard/pui.xml') | Out-Null; Q @('-s', $Serial, 'shell', 'cat', '/sdcard/pui.xml') }
function Shot { param([string]$name)
    Q @('-s', $Serial, 'shell', 'screencap', '-p', "/sdcard/$name.png") | Out-Null
    Q @('-s', $Serial, 'pull', "/sdcard/$name.png", (Join-Path $outDir "$name.png")) | Out-Null
    Q @('-s', $Serial, 'shell', 'rm', "/sdcard/$name.png") | Out-Null
    Write-Host ("      shot: qa\$name.png")
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1
Q @('-s', $Serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 500

# Make sure the "new member" mode is active so avatars are visible.
$xml = Dump
$newRadio = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*inviteNewPersonRadio"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if ($newRadio.Success -and $xml -notmatch 'inviteAvatarPreset1') {
    $g = $newRadio.Groups
    Q @('-s', $Serial, 'shell', 'input', 'tap', ([int](([int]$g[1].Value + [int]$g[3].Value) / 2)), ([int](([int]$g[2].Value + [int]$g[4].Value) / 2))) | Out-Null
    Start-Sleep -Seconds 2
    $xml = Dump
}

Write-Host "=== BEFORE: screenshot of avatar row ==="
Shot "avatars-before"

function TapAvatar { param([string]$id)
    $m = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*' + $id + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { Write-Host "      $id absent"; return $false }
    $g = $m.Groups
    $x = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
    $y = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
    Q @('-s', $Serial, 'shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Milliseconds 1200
    Write-Host ("      tapped $id at ($x,$y)")
    return $true
}

Write-Host "=== tap avatar 3 ==="
TapAvatar 'inviteAvatarPreset3' | Out-Null
$xml2 = Dump
Write-Host ("      after tap, avatar row still present: " + ($xml2 -match 'inviteAvatarPreset3'))
Shot "avatars-after3"

Write-Host "=== tap avatar 5 ==="
TapAvatar 'inviteAvatarPreset5' | Out-Null
Shot "avatars-after5"

Write-Host ""
Write-Host "=== does the screen still show the invite form? ==="
$xml3 = Dump
[regex]::Matches($xml3, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_.Trim() } | Select-Object -Unique | Select-Object -First 15 |
    ForEach-Object { Write-Host ("      - " + $_) }
