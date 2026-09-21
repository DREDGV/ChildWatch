# Installs an APK by copying it to the device first.
#
# Streamed install (adb install) of the 42 MB parent APK failed on the Nokia
# after ~5 minutes; pushing the file and installing from the device copy avoids
# the streamed path that stalls on that phone.
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Apk
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

if (-not (Test-Path -LiteralPath $Apk)) { Write-Host "APK missing: $Apk"; exit 1 }
Write-Host ("device: {0}" -f $Serial)
Write-Host ("apk:    {0}" -f (Split-Path $Apk -Leaf))

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1

if (-not ((Q @('-s', $Serial, 'shell', 'echo', 'ok')) -match 'ok')) {
    Write-Host "device not reachable"
    exit 1
}

$remote = "/data/local/tmp/cw-install.apk"
Write-Host "1) pushing to $remote"
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Q @('-s', $Serial, 'push', $Apk, $remote) | ForEach-Object { Write-Host ("   " + ($_ -split "`n")[-1]) }
Write-Host ("   push took {0} s" -f [int]$sw.Elapsed.TotalSeconds)

Write-Host "2) installing from the device copy"
$sw.Restart()
$out = Q @('-s', $Serial, 'shell', 'pm', 'install', '-r', $remote)
Write-Host ("   {0}" -f (($out -split "`n") | Select-Object -Last 1).Trim())
Write-Host ("   install took {0} s" -f [int]$sw.Elapsed.TotalSeconds)

Write-Host "3) cleanup"
Q @('-s', $Serial, 'shell', 'rm', '-f', $remote) | Out-Null

Write-Host "4) verify"
$pkg = if ((Split-Path $Apk -Leaf) -match 'ParentMonitor') { "ru.example.childwatch" } else { "ru.example.parentwatch.debug" }
$list = Q @('-s', $Serial, 'shell', 'pm', 'list', 'packages', $pkg)
Write-Host ("   {0}" -f $list)
