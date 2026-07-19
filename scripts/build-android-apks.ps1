param(
    [ValidateSet("All", "Parent", "Child")]
    [string]$Target = "All",
    [ValidateSet("Debug", "Release")]
    [string]$BuildType = "Debug",
    [string]$VersionName,
    [long]$VersionCode = 0,
    [switch]$AllowDependencyDownload
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildMoment = Get-Date
$runId = $buildMoment.ToString("yyyyMMdd-HHmmss")
$shortYear = $buildMoment.ToString("yy")
$dayOfYear = $buildMoment.DayOfYear.ToString("000")
$resolvedVersionName = if ($VersionName) {
    $VersionName.Trim()
} else {
    "7.3.$shortYear$dayOfYear.$($buildMoment.ToString('HHmmss'))"
}
$resolvedVersionCode = if ($VersionCode -gt 0) {
    $VersionCode
} else {
    [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
}
$isolatedBuildRoot = ".codex-build/$runId"
$automationTemp = Join-Path $projectRoot ".gradle-agent-home/tmp-$runId"
$artifactDirectory = Join-Path $projectRoot "artifacts/android"

New-Item -ItemType Directory -Force -Path $automationTemp | Out-Null
New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null

$lockPath = Join-Path $projectRoot ".gradle-agent-home/childwatch-build.lock"
try {
    $buildLock = [System.IO.File]::Open(
        $lockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
} catch {
    throw "Another ChildWatch automated build is already running. Wait for it to finish and try again."
}

try {
    $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$automationTemp"
    $env:GRADLE_USER_HOME = Join-Path $projectRoot ".gradle-agent-home"
    # Keep the Android debug signing identity stable across automated and
    # Android Studio builds. A separate agent home creates a new debug key and
    # makes otherwise valid APK updates incompatible with installed devices.
    $env:ANDROID_USER_HOME = Join-Path $projectRoot ".android-signing"

    $wrapperProperties = Get-Content (Join-Path $projectRoot "gradle/wrapper/gradle-wrapper.properties") -Raw
    $versionMatch = [regex]::Match($wrapperProperties, "gradle-([0-9.]+)-bin\.zip")
    if (-not $versionMatch.Success) {
        throw "Unable to determine the Gradle version from gradle-wrapper.properties."
    }

    $gradleVersion = $versionMatch.Groups[1].Value
    $gradlePatterns = @(
        (Join-Path $env:GRADLE_USER_HOME "wrapper/dists/gradle-$gradleVersion-bin/*/gradle-$gradleVersion/bin/gradle.bat"),
        (Join-Path $env:USERPROFILE ".gradle/wrapper/dists/gradle-$gradleVersion-bin/*/gradle-$gradleVersion/bin/gradle.bat")
    )
    $gradleExecutable = $gradlePatterns |
        ForEach-Object { Get-ChildItem -Path $_ -File -ErrorAction SilentlyContinue } |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $gradleExecutable) {
        throw "Gradle $gradleVersion is not installed in the local wrapper cache."
    }

    $assembleTask = if ($BuildType -eq "Release") { "assembleRelease" } else { "assembleDebug" }
    $tasks = switch ($Target) {
        "Parent" { @((":app:{0}" -f $assembleTask)) }
        "Child" { @((":parentwatch:{0}" -f $assembleTask)) }
        default { @((":app:{0}" -f $assembleTask), (":parentwatch:{0}" -f $assembleTask)) }
    }

    $arguments = @($tasks)
    if (-not $AllowDependencyDownload) {
        $arguments += '--offline'
    }
    $arguments += @(
        '--no-daemon'
        '--no-parallel'
        '--max-workers=1'
        '--no-build-cache'
        '--no-configuration-cache'
        '--console=plain'
        '-Dorg.gradle.vfs.watch=false'
        '-Pkotlin.compiler.execution.strategy=in-process'
        "-PcwIsolatedBuildRoot=$isolatedBuildRoot"
        "-PcwVersionName=$resolvedVersionName"
        "-PcwVersionCode=$resolvedVersionCode"
    )

    Push-Location $projectRoot
    try {
        & $gradleExecutable @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    $moduleNames = switch ($Target) {
        "Parent" { @('app') }
        "Child" { @('parentwatch') }
        default { @('app', 'parentwatch') }
    }

    $artifacts = foreach ($moduleName in $moduleNames) {
        $apkDirectory = Join-Path $projectRoot "$isolatedBuildRoot/$moduleName/outputs/apk/$($BuildType.ToLowerInvariant())"
        $apk = Get-ChildItem -LiteralPath $apkDirectory -Filter '*.apk' -File |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $apk) {
            throw "No APK was produced for module $moduleName."
        }
        $destination = Join-Path $artifactDirectory $apk.Name
        Copy-Item -LiteralPath $apk.FullName -Destination $destination
        Get-Item -LiteralPath $destination
    }

    Write-Host "Build completed successfully."
    $artifacts | Select-Object FullName, Length, LastWriteTime | Format-Table -AutoSize
} finally {
    if ($buildLock) {
        $buildLock.Dispose()
    }
}
