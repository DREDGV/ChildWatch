param(
    [ValidateSet("All", "Parent", "Child")]
    [string]$Target = "All"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildMoment = Get-Date
$runId = $buildMoment.ToString("yyyyMMdd-HHmmss")
$shortYear = $buildMoment.ToString("yy")
$dayOfYear = $buildMoment.DayOfYear.ToString("000")
$versionName = "7.2.$shortYear$dayOfYear.$($buildMoment.ToString('HHmmss'))"
$versionCode = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
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

    $wrapperProperties = Get-Content (Join-Path $projectRoot "gradle/wrapper/gradle-wrapper.properties") -Raw
    $versionMatch = [regex]::Match($wrapperProperties, "gradle-([0-9.]+)-bin\.zip")
    if (-not $versionMatch.Success) {
        throw "Unable to determine the Gradle version from gradle-wrapper.properties."
    }

    $gradleVersion = $versionMatch.Groups[1].Value
    $gradlePattern = Join-Path $env:USERPROFILE ".gradle/wrapper/dists/gradle-$gradleVersion-bin/*/gradle-$gradleVersion/bin/gradle.bat"
    $gradleExecutable = Get-ChildItem -Path $gradlePattern -File -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $gradleExecutable) {
        throw "Gradle $gradleVersion is not installed in the local wrapper cache."
    }

    $tasks = switch ($Target) {
        "Parent" { @(':app:assembleDebug') }
        "Child" { @(':parentwatch:assembleDebug') }
        default { @(':app:assembleDebug', ':parentwatch:assembleDebug') }
    }

    $arguments = @(
        $tasks
        '--offline'
        '--no-daemon'
        '--no-parallel'
        '--max-workers=1'
        '--no-build-cache'
        '--no-configuration-cache'
        '--console=plain'
        '-Dorg.gradle.vfs.watch=false'
        '-Pkotlin.compiler.execution.strategy=in-process'
        "-PcwIsolatedBuildRoot=$isolatedBuildRoot"
        "-PcwVersionName=$versionName"
        "-PcwVersionCode=$versionCode"
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
        $apkDirectory = Join-Path $projectRoot "$isolatedBuildRoot/$moduleName/outputs/apk/debug"
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
