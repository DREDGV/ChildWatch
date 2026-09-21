# Stages a release of both applications for over-the-air update.
#
# Produces, inside the release directory:
#   - a copy of each built APK under a versioned name
#   - manifest.json describing what is published, with a checksum for each file
#
# A phone compares its own version code with the manifest, downloads the file the
# manifest names, and checks the file against the checksum in that same manifest
# before offering to install it. Without the checksum a download that was cut
# short or altered would be indistinguishable from a good one.
#
# This script does NOT upload anything and does NOT install anything. It stages
# what will be published, so the upload can be a single deliberate step.
#
# ASCII only: Windows PowerShell 5.1 reads this file as ANSI and Cyrillic here
# would break parsing.

param(
    [string]$OutputDirectory = "",
    [switch]$UseDebugBuilds
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Step($message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

function Get-ApkSigner {
    $sdkLine = Get-Content (Join-Path $repo "local.properties") |
        Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
    if (-not $sdkLine) { return $null }
    $sdk = ($sdkLine -replace "^sdk.dir=", "").Replace("\\", "\")
    $candidates = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
        Where-Object { Test-Path $_ }
    return $candidates | Select-Object -First 1
}

function Get-BuildStamp($apkPath) {
    # The build writes its own version into the application; the file name carries
    # the same version, which avoids depending on aapt for a single string.
    $name = [System.IO.Path]::GetFileNameWithoutExtension($apkPath)
    if ($name -match "v(\d+\.\d+\.\d+\.\d+)$") { return $Matches[1] }
    return $null
}

function Get-VersionCode($apkPath) {
    # Prefer the build outputs metadata Gradle writes: it needs no extra tool.
    $meta = Join-Path (Split-Path -Parent $apkPath) "output-metadata.json"
    if (Test-Path $meta) {
        try {
            $parsed = Get-Content $meta -Raw | ConvertFrom-Json
            $element = $parsed.elements | Select-Object -First 1
            if ($element -and $element.versionCode) { return [int64]$element.versionCode }
        } catch { }
    }
    return $null
}

function Get-SignerFingerprint($apkPath, $apksigner) {
    if (-not $apksigner) { return $null }
    try {
        $output = & $apksigner verify --print-certs $apkPath 2>&1
        $line = $output | Where-Object { $_ -match "SHA-256 digest" } | Select-Object -First 1
        if ($line -and $line -match "([0-9a-fA-F:]{95})") {
            return ($Matches[1] -replace ":", "").ToLowerInvariant()
        }
    } catch { }
    return $null
}

$releaseDirectory = if ($OutputDirectory) {
    $OutputDirectory
} else {
    Join-Path $repo "updates"
}
New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null

$apksigner = Get-ApkSigner
if (-not $apksigner) {
    Step "WARNING: apksigner not found; the signing certificate will be omitted"
}

# The parent application is the one that can ask for an update on the owner's
# behalf, but the child application needs it just as much: it is the one that is
# hard to reach with a cable.
$targets = @(
    @{ Key = "parent"; Package = "ru.example.childwatch"; Project = "app"; Label = "ParentMonitor" },
    @{ Key = "child";  Package = "ru.example.parentwatch"; Project = "parentwatch"; Label = "ChildDevice" }
)

$apps = @{}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

foreach ($target in $targets) {
    $buildType = if ($UseDebugBuilds) { "debug" } else { "release" }
    $apkDirectory = Join-Path $repo "$($target.Project)\build\outputs\apk\$buildType"
    if (-not (Test-Path $apkDirectory)) {
        Step "SKIP $($target.Label): no $buildType build in $apkDirectory"
        Step "     run the build first, for example: scripts\build-and-install.ps1 -BuildOnly"
        continue
    }

    $apk = Get-ChildItem $apkDirectory -Filter "*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) {
        Step "SKIP $($target.Label): no APK found in $apkDirectory"
        continue
    }

    $versionName = Get-BuildStamp $apk.FullName
    $versionCode = Get-VersionCode $apk.FullName
    if (-not $versionCode) {
        Step "SKIP $($target.Label): the build did not report a version code"
        continue
    }

    Step ("{0}: version {1} (code {2}), {3:N1} MB" -f $target.Label, $versionName, $versionCode, ($apk.Length / 1MB))

    $publishedName = "$($target.Label)-$versionName.apk"
    $publishedPath = Join-Path $releaseDirectory $publishedName
    Copy-Item $apk.FullName $publishedPath -Force

    $hash = (Get-FileHash -Path $publishedPath -Algorithm SHA256).Hash.ToLowerInvariant()

    # Never overwrite a published file that has a different content under the same
    # name: a phone may already have downloaded it, and the manifest must describe
    # exactly one file for one version.
    $apps[$target.Key] = [ordered]@{
        packageName    = $target.Package
        versionCode    = $versionCode
        versionName    = $versionName
        buildType      = $buildType
        sizeBytes      = [int64]$publishedPath.Length
        sha256         = $hash
        file           = $publishedName
        signingCertSha256 = Get-SignerFingerprint $publishedPath $apksigner
        notes          = ""
    }
}

if ($apps.Count -eq 0) {
    Step "FAILED: nothing to publish"
    exit 1
}

$manifest = [ordered]@{
    schema      = 1
    generatedAt = [int64]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    generatedBy = "scripts\stage-release.ps1"
    apps        = $apps
}

$manifestPath = Join-Path $releaseDirectory "manifest.json"
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $manifestPath -Encoding UTF8

Step "manifest written: $manifestPath"
Write-Host ""
Write-Host "Published in this manifest:"
foreach ($key in $apps.Keys) {
    $entry = $apps[$key]
    Write-Host ("  {0,-8} {1}  code {2}" -f $key, $entry.versionName, $entry.versionCode)
    Write-Host ("           {0}" -f $entry.file)
    Write-Host ("           sha256 {0}" -f $entry.sha256)
    if ($entry.signingCertSha256) {
        Write-Host ("           signed by {0}" -f $entry.signingCertSha256)
    } else {
        Write-Host  "           signing certificate NOT recorded"
    }
}
Write-Host ""
Write-Host "Next step: upload the directory to the server's release folder and make sure"
Write-Host "CW_UPDATE_DIR points at it. Nothing was uploaded or installed by this script."
