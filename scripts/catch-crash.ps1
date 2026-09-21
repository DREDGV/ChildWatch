# Reproduces the family-selection crash and captures the full stack trace.
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [string]$Serial = "PT19655KA1280800674",
    [int]$Rounds = 8
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String) }
function Dump {
    Q @('-s', $Serial, 'shell', 'uiautomator', 'dump', '/sdcard/z.xml') | Out-Null
    return (Q @('-s', $Serial, 'shell', 'cat', '/sdcard/z.xml'))
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 2

$caught = $false
for ($round = 1; $round -le $Rounds -and -not $caught; $round++) {
    Q @('-s', $Serial, 'shell', 'am', 'force-stop', 'ru.example.childwatch') | Out-Null
    Start-Sleep -Seconds 2
    Q @('-s', $Serial, 'shell', 'monkey', '-p', 'ru.example.childwatch', '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
    Start-Sleep -Seconds 11
    Q @('-s', $Serial, 'shell', 'input', 'tap', '568', '322') | Out-Null
    Start-Sleep -Seconds 5
    Q @('-s', $Serial, 'shell', 'logcat', '-c') | Out-Null
    Q @('-s', $Serial, 'shell', 'input', 'tap', '360', '387') | Out-Null
    Start-Sleep -Seconds 6

    $pkg = [regex]::Match((Dump), 'package="([^"]+)"').Groups[1].Value
    if ($pkg -ne 'ru.example.childwatch') {
        $caught = $true
        Write-Host "CRASH CAPTURED on round $round (package=$pkg)"
        $log = (Q @('-s', $Serial, 'shell', 'logcat', '-d', '-t', '600')) -split "`n"
        for ($i = 0; $i -lt $log.Count; $i++) {
            if ($log[$i] -match 'FATAL EXCEPTION') {
                $end = [Math]::Min($i + 30, $log.Count - 1)
                $log[$i..$end] | ForEach-Object { Write-Host ("  " + ($_ -replace '^\S+\s+\S+\s+\S+\s+', '')) }
                break
            }
        }
    } else {
        Write-Host "round $round : no crash"
    }
}

if (-not $caught) { Write-Host "no crash reproduced in $Rounds rounds" }
