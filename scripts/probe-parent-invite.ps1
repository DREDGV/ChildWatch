# Probes the parent invite screen: taps each mode radio and each preset avatar,
# reporting what becomes visible. Read-mostly: it only taps, creates nothing.
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param([string]$Serial = "PT19655KA1280800674")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$outDir = Join-Path $repo "qa"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }
function Dump { Q @('-s', $Serial, 'shell', 'uiautomator', 'dump', '/sdcard/pui.xml') | Out-Null; Q @('-s', $Serial, 'shell', 'cat', '/sdcard/pui.xml') }

function State { param([string]$xml, [string]$label)
    Write-Host ("  --- " + $label)
    foreach ($id in @('inviteNewPersonRadio', 'inviteExistingPersonRadio', 'inviteLegacyProfileRadio',
                      'inviteNameLayout', 'inviteRoleLayout', 'inviteAvatarSection',
                      'inviteExistingLayout', 'inviteLegacyCandidateLayout', 'invitationResultCard')) {
        $m = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*' + $id + '"[^>]*>')
        if ($m.Success) {
            $checked = if ($m.Value -match 'checked="true"') { 'CHECKED' } else { '' }
            Write-Host ("      {0,-30} present {1}" -f $id, $checked)
        } else {
            Write-Host ("      {0,-30} absent" -f $id)
        }
    }
}

function TapId { param([string]$xml, [string]$id)
    $p = '<node[^>]*resource-id="[^"]*' + [regex]::Escape($id) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $m = [regex]::Match($xml, $p)
    if (-not $m.Success) { return $false }
    $g = $m.Groups
    $x = [int](([int]$g[1].Value + [int]$g[3].Value) / 2)
    $y = [int](([int]$g[2].Value + [int]$g[4].Value) / 2)
    Q @('-s', $Serial, 'shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Milliseconds 1500
    Write-Host ("      tapped $id at ($x,$y)")
    return $true
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1
Q @('-s', $Serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Start-Sleep -Milliseconds 500

Write-Host "=== initial state (as opened) ==="
$xml = Dump
State -xml $xml -label "on open"

Write-Host ""
Write-Host "=== tap: new family member ==="
if (TapId -xml $xml -id 'inviteNewPersonRadio') {
    Start-Sleep -Seconds 1
    $xml = Dump
    State -xml $xml -label "after tapping new member"
    [System.IO.File]::WriteAllText((Join-Path $outDir "parent-invite-new.xml"), $xml)
    Write-Host "      texts now:"
    [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_.Trim() } | Select-Object -Unique | Select-Object -First 15 |
        ForEach-Object { Write-Host ("        - " + $_) }
}

Write-Host ""
Write-Host "=== tap: existing person (back) ==="
if (TapId -xml $xml -id 'inviteExistingPersonRadio') {
    $xml = Dump
    State -xml $xml -label "after tapping existing person"
}

Write-Host ""
Write-Host "=== avatar probe: back to new member, then tap avatar 3 ==="
TapId -xml $xml -id 'inviteNewPersonRadio' | Out-Null
Start-Sleep -Seconds 1
$xml = Dump
$avatarIds = @('inviteAvatarPreset1', 'inviteAvatarPreset2', 'inviteAvatarPreset3', 'inviteAvatarPreset4', 'inviteAvatarPreset5', 'inviteAvatarPreset6')
foreach ($a in $avatarIds) {
    $m = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*' + $a + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($m.Success) {
        $g = $m.Groups
        Write-Host ("      {0} bounds=[{1},{2}][{3},{4}]" -f $a, $g[1].Value, $g[2].Value, $g[3].Value, $g[4].Value)
    } else {
        Write-Host ("      {0} absent - avatars are not on screen" -f $a)
    }
}
Write-Host ""
Write-Host "=== role dropdown contents ==="
$roleNode = [regex]::Match($xml, '<node[^>]*resource-id="[^"]*inviteRoleInput"[^>]*>')
if ($roleNode.Success) {
    Write-Host ("      inviteRoleInput present; text=" + ([regex]::Match($roleNode.Value, 'text="([^"]*)"')).Groups[1].Value)
} else {
    Write-Host "      inviteRoleInput absent - cannot choose a role on this screen"
}
