# Builds and installs the ChildWatch applications in one step.
#
# Why this exists: assembling both APKs, pushing them and installing used to be
# retyped command by command, which was slow and silent. This script does the
# whole flow and prints progress at every stage, so a stalled step is visible
# instead of looking like the tool is idle.
#
# Measured on this workstation (2026-09-17):
#   assembleDebug, no source changes ....... about 25 s
#   assembleDebug, after Kotlin changes .... about 2-3 min
#   push + pm install per device ........... 5-20 s
#   adb install (streamed) on Nokia ........ fails after 5 min - never use it
#
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [ValidateSet('both', 'parent', 'child')]
    [string]$Target = 'both',

    # Skip Gradle and install the newest APKs that already exist.
    [switch]$InstallOnly,

    # Skip installation and only assemble.
    [switch]$BuildOnly,

    # Seconds to allow one assemble invocation before it is called stuck.
    [int]$BuildTimeoutSeconds = 420
)

$ErrorActionPreference = 'Continue'
$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

$parentSerial = "PT19655KA1280800674"
$childSerial = "102742534J001408"
$parentPackage = "ru.example.childwatch"
$childPackage = "ru.example.parentwatch.debug"

function Write-Stage {
    param([string]$Text)
    Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Text)
}

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

function Get-Apk {
    param([string]$Module)
    $dir = Join-Path $repo "$Module\build\outputs\apk\debug"
    if (-not (Test-Path $dir)) { return $null }
    return (Get-ChildItem (Join-Path $dir "*.apk") |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

# Gradle runs in its own process so a hang cannot block this script forever.
function Invoke-Build {
    Write-Stage "gradle: assembling ($BuildTimeoutSeconds s limit)"
    $tasks = @()
    if ($Target -ne 'child') { $tasks += ':app:assembleDebug' }
    if ($Target -ne 'parent') { $tasks += ':parentwatch:assembleDebug' }

    $job = Start-Job -ScriptBlock {
        param($root, $taskList)
        & powershell -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $root 'scripts\run-gradle-safe.ps1') @taskList 2>&1 | Out-String
    } -ArgumentList $repo, $tasks

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    if (Wait-Job -Job $job -Timeout $BuildTimeoutSeconds) {
        $out = Receive-Job -Job $job
        Remove-Job -Job $job -Force
        $text = ($out | Out-String)
        $verdict = if ($text -match 'BUILD SUCCESSFUL') { 'BUILD SUCCESSFUL' }
                   elseif ($text -match 'BUILD FAILED') { 'BUILD FAILED' }
                   else { 'no verdict in output' }
        Write-Stage ("gradle: {0} in {1} s" -f $verdict, [int]$sw.Elapsed.TotalSeconds)
        if ($verdict -ne 'BUILD SUCCESSFUL') {
            ($text -split "`n") | Where-Object { $_ -match '^e: |error:|FAILURE' } |
                Select-Object -First 20 | ForEach-Object { Write-Host ("    " + $_.Trim()) }
            return $false
        }
        return $true
    }

    Stop-Job -Job $job -ErrorAction SilentlyContinue
    Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    Write-Stage ("gradle: NO RESULT after {0} s - treating as stuck" -f $BuildTimeoutSeconds)
    return $false
}

function Install-One {
    param([string]$Label, [string]$Serial, [string]$Apk, [string]$Package)
    if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) {
        Write-Stage "$Label : no APK found, skipped"
        return
    }
    if ((Q @('-s', $Serial, 'shell', 'echo', 'ok')) -notmatch 'ok') {
        Write-Stage "$Label : not connected, skipped"
        return
    }

    $name = Split-Path $Apk -Leaf
    Write-Stage "$Label : pushing $name"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # Push first, then install from the device copy: the streamed install of the
    # parent APK failed on the Nokia after five minutes.
    Q @('-s', $Serial, 'push', $Apk, '/data/local/tmp/cw-install.apk') | Out-Null
    $pushSeconds = [int]$sw.Elapsed.TotalSeconds
    $sw.Restart()
    $result = Q @('-s', $Serial, 'shell', 'pm', 'install', '-r', '/data/local/tmp/cw-install.apk')
    $installSeconds = [int]$sw.Elapsed.TotalSeconds
    Q @('-s', $Serial, 'shell', 'rm', '-f', '/data/local/tmp/cw-install.apk') | Out-Null

    $verdict = (($result -split "`n") | Select-Object -Last 1).Trim()
    Write-Stage ("{0} : {1} (push {2} s, install {3} s)" -f $Label, $verdict, $pushSeconds, $installSeconds)

    $installed = Q @('-s', $Serial, 'shell', 'dumpsys', 'package', $Package)
    $version = ([regex]::Match($installed, 'versionName=(\S+)')).Groups[1].Value
    if ($version) { Write-Stage ("{0} : installed version {1}" -f $Label, $version) }
}

Write-Stage "target=$Target installOnly=$InstallOnly buildOnly=$BuildOnly"

if (-not $InstallOnly) {
    if (-not (Invoke-Build)) {
        Write-Stage "stopping: the build did not succeed"
        exit 1
    }
}

if ($BuildOnly) { Write-Stage "done (build only)"; exit 0 }

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1

$parentApk = Get-Apk -Module 'app'
$childApk = Get-Apk -Module 'parentwatch'

if ($Target -ne 'child') { Install-One -Label 'Nokia (ParentMonitor)' -Serial $parentSerial -Apk $parentApk -Package $parentPackage }
if ($Target -ne 'parent') { Install-One -Label 'Infinix (ChildDevice)' -Serial $childSerial -Apk $childApk -Package $childPackage }

Write-Stage "done"
