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

    # Override the usual parent handset when another parent phone is connected.
    [string]$ConnectedParentSerial = '',

    [string]$ConnectedChildSerial = '',

    [ValidateRange(0, 2100000000)]
    [int]$VersionCode = 0,

    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'debug',

    # Skip installation and only assemble.
    [switch]$BuildOnly,

    # Seconds to allow one assemble invocation before it is called stuck.
    [int]$BuildTimeoutSeconds = 900
)

$ErrorActionPreference = 'Continue'
if ($BuildType -eq 'release' -and -not $InstallOnly) {
    throw 'Build a signed release with scripts/build-release.ps1, then use -InstallOnly -BuildType release.'
}
$repo = Split-Path -Parent $PSScriptRoot
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'

$parentSerial = "PT19655KA1280800674"
if ($ConnectedParentSerial.Trim()) { $parentSerial = $ConnectedParentSerial.Trim() }
$childSerial = "102742534J001408"
if ($ConnectedChildSerial.Trim()) { $childSerial = $ConnectedChildSerial.Trim() }
$parentPackage = "ru.example.childwatch"
$childPackage = "ru.example.parentwatch.debug"

function Write-Stage {
    param([string]$Text)
    Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Text)
}

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

<#
    The project's signing password, read from the encrypted file the key helper
    created. Returns $null when no key is configured.

    Why the ordinary build needs it: build.gradle signs the debug build with the
    project's permanent key so it installs over a build that already carries that
    signature. That only happens when the password reaches Gradle - without it
    Gradle silently falls back to this machine's debug key, and the install then
    fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match.
#>
function Get-SigningPassword {
    $store = Join-Path $env:LOCALAPPDATA "ChildWatch"
    $secretPath = Join-Path $store "signing.dpapi"
    $settingsPath = Join-Path $store "signing.properties"
    if (-not (Test-Path -LiteralPath $settingsPath) -or -not (Test-Path -LiteralPath $secretPath)) {
        return $null
    }
    try {
        $secure = Get-Content -LiteralPath $secretPath | ConvertTo-SecureString
        return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    } catch {
        return $null
    }
}

function Get-Apk {
    param([string]$Module)
    $dir = Join-Path $repo "$Module\build\outputs\apk\$BuildType"
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

    # Signing has to happen inside the job, because the job is the process that
    # starts Gradle. The password lives in this process's environment for the
    # length of one build and is removed as soon as the job is finished.
    $signingPassword = Get-SigningPassword
    if ($signingPassword) {
        $env:CW_SIGNING_PASSWORD = $signingPassword
        Write-Stage "gradle: signing with the project key"
    } else {
        Write-Stage "gradle: WARNING no project signing key, so the debug key is used and the result may refuse to install over an existing build"
    }

    try {
        $job = Start-Job -ScriptBlock {
            param($root, $taskList, $requestedVersionCode)
            if ($requestedVersionCode -gt 0) {
                $taskList += "-PcwVersionCode=$requestedVersionCode"
            }
            & powershell -NoProfile -ExecutionPolicy Bypass `
                -File (Join-Path $root 'scripts\run-gradle-safe.ps1') @taskList 2>&1 | Out-String
        } -ArgumentList $repo, $tasks, $VersionCode

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
    } finally {
        Remove-Item Env:\CW_SIGNING_PASSWORD -ErrorAction SilentlyContinue
        $signingPassword = $null
    }
}

function Install-One {
    param([string]$Label, [string]$Serial, [string]$Apk, [string]$Package)
    if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) {
        Write-Stage "$Label : no APK found, skipped"
        return $false
    }
    if ((Q @('-s', $Serial, 'shell', 'echo', 'ok')) -notmatch 'ok') {
        Write-Stage "$Label : not connected, skipped"
        return $false
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
    return ($verdict -eq 'Success')
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

$installResults = @()
if ($Target -ne 'child') { $installResults += Install-One -Label 'ParentMonitor' -Serial $parentSerial -Apk $parentApk -Package $parentPackage }
if ($Target -ne 'parent') { $installResults += Install-One -Label 'ChildDevice' -Serial $childSerial -Apk $childApk -Package $childPackage }

Write-Stage "done"
if ($installResults -contains $false) { exit 1 }
