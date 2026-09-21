# Finds the test child phone and reports what is on its screen.
# ASCII-only on purpose: Windows PowerShell 5.1 reads script files as ANSI,
# and non-ASCII string literals break parsing.
[CmdletBinding()]
param([string]$WifiEndpoint = "192.168.3.71:40383")

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"
$outDir = Join-Path $repo "qa"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1

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

if (-not $serial) {
    Write-Host "DEVICE NOT FOUND"
    Write-Host "connected now:"
    (Q @('devices')) -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host ("  " + $_.Trim()) } }
    Write-Host "mdns services:"
    (Q @('mdns', 'services')) -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host ("  " + $_.Trim()) } }
    exit 1
}
Write-Host "device: $serial"

Write-Host ""
Write-Host "=== foreground activity ==="
(Q @('-s', $serial, 'shell', 'dumpsys', 'activity', 'activities')) -split "`n" |
    Where-Object { $_ -match 'mResumedActivity|topResumedActivity' } |
    Select-Object -First 3 | ForEach-Object { Write-Host ("  " + $_.Trim()) }

Write-Host ""
Write-Host "=== screen text ==="
Q @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
$xml = Q @('-s', $serial, 'shell', 'cat', '/sdcard/ui.xml')
[System.IO.File]::WriteAllText((Join-Path $outDir "child-ui.xml"), $xml)
[regex]::Matches($xml, 'text="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_.Trim() } | Select-Object -Unique |
    ForEach-Object { Write-Host ("  - " + $_) }

Write-Host ""
Write-Host "=== ids looking like inputs or buttons ==="
[regex]::Matches($xml, 'resource-id="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -match 'edit|server|url|child|parent|name|button|btn|switch|save' } |
    Select-Object -Unique | ForEach-Object { Write-Host ("  " + $_) }

Write-Host ""
Write-Host "=== screenshot ==="
Q @('-s', $serial, 'shell', 'screencap', '-p', '/sdcard/child-screen.png') | Out-Null
Q @('-s', $serial, 'pull', '/sdcard/child-screen.png', (Join-Path $outDir "child-screen.png")) | Out-Null
Q @('-s', $serial, 'shell', 'rm', '/sdcard/child-screen.png', '/sdcard/ui.xml') | Out-Null
if (Test-Path (Join-Path $outDir "child-screen.png")) { Write-Host "  saved: qa\child-screen.png" } else { Write-Host "  screenshot failed" }
