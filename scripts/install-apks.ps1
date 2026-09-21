# Installs the freshly built APKs with a hard timeout per device.
# The Nokia has twice stalled the adb server mid-command, so each install runs
# in its own job and is abandoned rather than blocking the session.
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 240,
    [string]$OnlyDevice = ""
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

function Install-Apk {
    param([string]$Serial, [string]$ApkPath, [string]$Label)
    if (-not (Test-Path -LiteralPath $ApkPath)) {
        Write-Host "  $Label : APK not found"
        return $false
    }
    Write-Host ("  {0}: installing {1} ({2:N1} MB)" -f $Label, (Split-Path $ApkPath -Leaf), ((Get-Item $ApkPath).Length / 1MB))
    $job = Start-Job -ScriptBlock {
        param($exe, $serial, $apk)
        & $exe -s $serial install -r -d $apk 2>&1 | Out-String
    } -ArgumentList $adb, $Serial, $ApkPath

    if (Wait-Job -Job $job -Timeout $TimeoutSeconds) {
        $out = Receive-Job -Job $job
        Remove-Job -Job $job -Force
        $text = ($out | Out-String).Trim()
        Write-Host ("    -> " + (($text -split "`n") | Select-Object -Last 1).Trim())
        return ($text -match 'Success')
    }
    Stop-Job -Job $job -ErrorAction SilentlyContinue
    Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    Write-Host "    -> TIMEOUT after $TimeoutSeconds s (device likely stalled adb)"
    return $false
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 2
Write-Host "=== devices ==="
(Q @('devices')) -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host ("  " + $_.Trim()) } }

$parentSerial = "PT19655KA1280800674"
$childSerial  = "102742534J001408"
$appApk   = (Get-ChildItem (Join-Path $repo "app\build\outputs\apk\debug\*.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
$childApk = (Get-ChildItem (Join-Path $repo "parentwatch\build\outputs\apk\debug\*.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName

Write-Host "=== installs ==="
$results = @{}
if ($OnlyDevice -ne "child")  { $results["parent"] = Install-Apk -Serial $parentSerial -ApkPath $appApk   -Label "Nokia (ParentMonitor)" }
if ($OnlyDevice -ne "parent") { $results["child"]  = Install-Apk -Serial $childSerial  -ApkPath $childApk -Label "Infinix (ChildDevice)" }

Write-Host ""
Write-Host "=== result ==="
foreach ($k in $results.Keys) { Write-Host ("  {0}: {1}" -f $k, $(if ($results[$k]) { "installed" } else { "NOT installed" })) }
