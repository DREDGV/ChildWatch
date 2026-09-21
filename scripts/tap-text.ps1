# Taps a UI element by its visible text on the device found over USB or WiFi.
# ASCII-only: Windows PowerShell 5.1 reads scripts as ANSI.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Text,
    [string]$WifiEndpoint = "192.168.3.71:40383",
    [int]$Index = 0
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

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

Q @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml') | Out-Null
$xml = Q @('-s', $serial, 'shell', 'cat', '/sdcard/ui.xml')

# Every node that carries the requested text, with its bounds.
$pattern = '<node[^>]*text="' + [regex]::Escape($Text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
$matches = [regex]::Matches($xml, $pattern)
if ($matches.Count -eq 0) {
    Write-Host "NOT FOUND: '$Text'"
    Write-Host "texts on screen:"
    [regex]::Matches($xml, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_.Trim() } | Select-Object -Unique | ForEach-Object { Write-Host ("  - " + $_) }
    exit 2
}

$m = $matches[[Math]::Min($Index, $matches.Count - 1)]
$x1 = [int]$m.Groups[1].Value; $y1 = [int]$m.Groups[2].Value
$x2 = [int]$m.Groups[3].Value; $y2 = [int]$m.Groups[4].Value
$cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)

Write-Host "tapping '$Text' at ($cx,$cy) [matches: $($matches.Count)]"
Q @('-s', $serial, 'shell', 'input', 'tap', "$cx", "$cy") | Out-Null
Start-Sleep -Seconds 2
Write-Host "tapped"
