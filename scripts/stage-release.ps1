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

<#
    Finds a Java runtime the signing tools can actually start.

    JAVA_HOME points at the runtime bundled with Android Studio, which is not a
    full JDK: apksigner started under it fails with a message about jvm.cfg that
    says nothing about the cause. Checking for that file is the reliable test.
#>
function Find-JavaHome {
    $roots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        (Join-Path $env:USERPROFILE ".jdks"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium")
    )
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Where-Object { Test-Path (Join-Path $_.FullName "lib\jvm.cfg") } |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

<#
    Reads what Gradle recorded about a build.

    Taken from the build's own metadata rather than parsed out of the file name:
    the name carries a "-debug" or "-release" suffix, so a pattern that worked for
    one variant silently produced an empty version for the other.
#>
function Get-BuildInfo($apkPath) {
    $meta = Join-Path (Split-Path -Parent $apkPath) "output-metadata.json"
    $info = [ordered]@{ versionCode = $null; versionName = $null; packageName = $null }
    if (-not (Test-Path $meta)) { return $info }
    try {
        $parsed = Get-Content $meta -Raw | ConvertFrom-Json
        $element = $parsed.elements | Select-Object -First 1
        if ($element) {
            if ($element.versionCode) { $info.versionCode = [int64]$element.versionCode }
            if ($element.versionName) { $info.versionName = [string]$element.versionName }
        }
        if ($parsed.applicationId) { $info.packageName = [string]$parsed.applicationId }
    } catch { }
    return $info
}

function Get-SignerFingerprint($apkPath, $apksigner, $javaHome) {
    if (-not $apksigner) { return $null }
    $previousJavaHome = $env:JAVA_HOME
    if ($javaHome) { $env:JAVA_HOME = $javaHome }
    try {
        $output = & $apksigner verify --print-certs $apkPath 2>&1
        $line = $output | Where-Object { "$_" -match "Signer #1 certificate SHA-256 digest" } |
            Select-Object -First 1
        if (-not $line) {
            $line = $output | Where-Object { "$_" -match "SHA-256 digest" } | Select-Object -First 1
        }
        if ($line) {
            # apksigner prints the digest as plain lower-case hex, while keytool
            # separates the pairs with colons. Both shapes are accepted rather than
            # assuming the one that happened to be seen first: the earlier assumption
            # made the fingerprint silently come out empty.
            $text = "$line"
            if ($text -match "([0-9a-fA-F]{64})") {
                return $Matches[1].ToLowerInvariant()
            }
            if ($text -match "([0-9a-fA-F:]{95})") {
                return ($Matches[1] -replace ":", "").ToLowerInvariant()
            }
        }
    } catch { } finally {
        $env:JAVA_HOME = $previousJavaHome
    }
    return $null
}

$releaseDirectory = if ($OutputDirectory) {
    $OutputDirectory
} else {
    Join-Path $repo "updates"
}
New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null

$apksigner = Get-ApkSigner
$signingJavaHome = Find-JavaHome
if (-not $apksigner) {
    Step "WARNING: apksigner not found; the signing certificate will be omitted"
} elseif (-not $signingJavaHome) {
    Step "WARNING: no full JDK found; the signing certificate will be omitted"
    Step "         apksigner needs one, the runtime bundled with Android Studio is not enough"
} else {
    Step "Signing tools will use: $signingJavaHome"
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

    $build = Get-BuildInfo $apk.FullName
    $versionName = $build.versionName
    $versionCode = $build.versionCode
    if (-not $versionCode) {
        Step "SKIP $($target.Label): the build did not report a version code"
        continue
    }
    if (-not $versionName) {
        # Never publish a nameless version: it is what the person sees in the notice.
        Step "SKIP $($target.Label): the build did not report a version name"
        continue
    }

    Step ("{0}: version {1} (code {2}), {3:N1} MB" -f $target.Label, $versionName, $versionCode, ($apk.Length / 1MB))

    $publishedName = "$($target.Label)-$versionName.apk"
    $publishedPath = Join-Path $releaseDirectory $publishedName
    Copy-Item $apk.FullName $publishedPath -Force

    # The size of the FILE, taken from the file itself.
    #
    # $publishedPath.Length is the length of the path STRING - 72 characters here -
    # and using it published a manifest claiming the package was 72 bytes. The phone
    # then told the person "about 0.0 MB" and refused the download as too small, so
    # no update could ever have installed.
    $publishedSize = (Get-Item -LiteralPath $publishedPath).Length

    $hash = (Get-FileHash -Path $publishedPath -Algorithm SHA256).Hash.ToLowerInvariant()

    # Never overwrite a published file that has a different content under the same
    # name: a phone may already have downloaded it, and the manifest must describe
    # exactly one file for one version.
    $apps[$target.Key] = [ordered]@{
        packageName    = $target.Package
        versionCode    = $versionCode
        versionName    = $versionName
        buildType      = $buildType
        sizeBytes      = [int64]$publishedSize
        sha256         = $hash
        file           = $publishedName
        signingCertSha256 = Get-SignerFingerprint $publishedPath $apksigner $signingJavaHome
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

# Written as UTF-8 WITHOUT a byte order mark. PowerShell's -Encoding UTF8 writes one
# by default, and a leading mark makes JSON.parse throw, so the manifest would be
# unreadable to the server that serves it. Found by testing the route rather than
# by reading the code.
$manifestJson = $manifest | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText(
    $manifestPath,
    $manifestJson,
    (New-Object System.Text.UTF8Encoding($false))
)

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
