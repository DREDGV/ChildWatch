# Taps "Create invitation" on the parent invite screen and reports exactly what
# happens: server response, on-screen error, or nothing at all. ASCII-only.
[CmdletBinding()]
param([string]$Serial = "PT19655KA1280800674")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.childwatch"
$outDir = Join-Path $repo "qa"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Dump { Q @('-s', $Serial, 'shell', 'uiautomator', 'dump', '/sdcard/pui.xml') | Out-Null; Q @('-s', $Serial, 'shell', 'cat', '/sdcard/pui.xml') }
function Texts { param([string]$xml)
    [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_.Trim() } | Select-Object -Unique
}
function TapId { param([string]$xml, [string]$id)
    $p = '<node[^>]*resource-id="[^"]*' + [regex]::Escape($id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $p)
    if (-not $m.Success) { Write-Host "      $id ABSENT"; return $false }
    $g = $m.Groups
    $x = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
    $y = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
    Q @('-s', $Serial, 'shell', 'input', 'tap', "$x", "$y") | Out-Null
    Write-Host "      tapped $id at ($x,$y)"
    return $true
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1
Q @('-s', $Serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 500

Q @('-s', $Serial, 'shell', 'logcat', '-c') | Out-Null

$xml = Dump
Write-Host "=== current screen ==="
Texts $xml | Select-Object -First 15 | ForEach-Object { Write-Host ("  - " + $_) }

# Ensure the new-member mode is active.
if ($xml -notmatch 'inviteAvatarPreset1') {
    Write-Host "=== switching to new-member mode ==="
    TapId -xml $xml -id 'inviteNewPersonRadio' | Out-Null
    Start-Sleep -Seconds 2
    $xml = Dump
}

Write-Host ""
Write-Host "=== name field content ==="
$name = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*inviteNameInput"[^>]*>')
if ($name.Success) {
    Write-Host ("  name = '" + ([regex]::Match($name.Value, 'text="([^"]*)"')).Groups[1].Value + "'")
} else {
    Write-Host "  inviteNameInput absent"
}

Write-Host ""
Write-Host "=== tapping Create invitation ==="
TapId -xml $xml -id 'createInvitationButton' | Out-Null
Start-Sleep -Seconds 8

$xml2 = Dump
Write-Host "=== screen after tapping create ==="
Texts $xml2 | Select-Object -First 25 | ForEach-Object { Write-Host ("  - " + $_) }

Write-Host ""
Write-Host "=== did the QR card appear? ==="
Write-Host ("  invitationResultCard present: " + ($xml2 -match 'invitationResultCard'))

Write-Host ""
Write-Host "=== app logs around the attempt ==="
$log = Q @('-s', $Serial, 'logcat', '-d', '-t', '300')
($log -split "`n") | Where-Object { $_ -match 'Invite|invitation|FamilyInvite|NetworkClient|createFamily|HTTP|401|403|400|500|error|Error|Toast' } |
    Select-Object -Last 30 | ForEach-Object { Write-Host ("  " + $_.Trim()) }
