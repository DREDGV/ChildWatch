# Connects over WiFi and installs in ONE process.
#
# The DSH sandbox ends child processes with each tool call, which kills the adb
# daemon and every WiFi session with it; connecting in one call and installing in
# the next therefore always failed with "device not found".
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [string]$ParentEndpoint = "192.168.3.28:42589",
    [string]$ChildEndpoint = "192.168.3.71:40383"
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

function Connect-Device {
    param([string]$Endpoint)
    for ($i = 1; $i -le 4; $i++) {
        Q @('connect', $Endpoint) | Out-Null
        Start-Sleep -Seconds 2
        if ((Q @('-s', $Endpoint, 'shell', 'echo', 'ok')) -match 'ok') { return $true }
    }
    return $false
}

function Install-Apk {
    param([string]$Endpoint, [string]$Apk, [string]$Label)
    if (-not (Test-Path -LiteralPath $Apk)) { Write-Host "  ${Label}: APK missing"; return $false }
    Write-Host ("  {0}: {1}" -f $Label, (Split-Path $Apk -Leaf))
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $out = Q @('-s', $Endpoint, 'install', '-r', $Apk)
    Write-Host ("    -> " + (($out -split "`n") | Select-Object -Last 1).Trim() + "  ($([int]$sw.Elapsed.TotalSeconds) s)")
    return ($out -match 'Success')
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 2

$parentApk = (Get-ChildItem (Join-Path $repo "app\build\outputs\apk\debug\*.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$childApk  = (Get-ChildItem (Join-Path $repo "parentwatch\build\outputs\apk\debug\*.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName

Write-Host "=== parent phone ==="
if (Connect-Device -Endpoint $ParentEndpoint) {
    Install-Apk -Endpoint $ParentEndpoint -Apk $parentApk -Label "Nokia ParentMonitor" | Out-Null
} else {
    Write-Host "  not reachable at $ParentEndpoint"
}

Write-Host "=== child phone ==="
if (Connect-Device -Endpoint $ChildEndpoint) {
    Install-Apk -Endpoint $ChildEndpoint -Apk $childApk -Label "Infinix ChildDevice" | Out-Null
} else {
    Write-Host "  not reachable at $ChildEndpoint"
}

Write-Host "=== devices after the run ==="
(Q @('devices')) -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host ("  " + $_.Trim()) } }
