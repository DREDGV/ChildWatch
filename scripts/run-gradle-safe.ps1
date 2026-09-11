[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$gradleHome = Join-Path $repoRoot ".gradle-agent-home"
$androidHome = Join-Path $repoRoot ".android-signing"

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

New-Item -ItemType Directory -Force -Path $gradleHome, $androidHome | Out-Null
$env:GRADLE_USER_HOME = $gradleHome
$env:ANDROID_USER_HOME = $androidHome

# Android Studio updates can change its installation directory (for example,
# "Android Studio" to "Android Studio1").  A stale JAVA_HOME then makes the
# Gradle wrapper fail before it can start.  Select a valid bundled JDK only
# for this safe Gradle process; the user's global environment is untouched.
$androidStudioRoot = Join-Path $env:ProgramFiles "Android"
if (Test-Path -LiteralPath $androidStudioRoot) {
    $bundledJdk = Get-ChildItem -LiteralPath $androidStudioRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object {
            $candidate = Join-Path $_.FullName "jbr"
            if (Test-Path -LiteralPath (Join-Path $candidate "bin\java.exe")) {
                $candidate
            }
        } |
        Select-Object -First 1
    if ($bundledJdk) {
        $env:JAVA_HOME = $bundledJdk
    }
}
$userHomeOption = "-Duser.home=$androidHome"
if ($env:GRADLE_OPTS -notlike "*$userHomeOption*") {
    $env:GRADLE_OPTS = "$($env:GRADLE_OPTS) $userHomeOption".Trim()
}

$normalizedRoot = [IO.Path]::GetFullPath($repoRoot).ToLowerInvariant()
$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $hashBytes = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalizedRoot))
} finally {
    $sha256.Dispose()
}
$hash = -join ($hashBytes[0..7] | ForEach-Object { $_.ToString("x2") })
$mutex = [Threading.Mutex]::new($false, "Local\ChildWatchGradle-$hash")
$lockTaken = $false

try {
    try {
        $lockTaken = $mutex.WaitOne([TimeSpan]::FromSeconds(30))
    } catch [Threading.AbandonedMutexException] {
        $lockTaken = $true
    }
    if (-not $lockTaken) {
        throw "Another ChildWatch Gradle command is still running. Wait for it to finish instead of starting a parallel build."
    }

    if (-not $GradleArguments -or $GradleArguments.Count -eq 0) {
        $GradleArguments = @(
            ":shared-core:test",
            ":app:compileDebugKotlin",
            ":parentwatch:compileDebugKotlin"
        )
    }

    $effectiveArguments = @($GradleArguments)
    if ($effectiveArguments -notcontains "--console=plain") {
        $effectiveArguments += "--console=plain"
    }
    if ($effectiveArguments -notcontains "--no-daemon") {
        $effectiveArguments += "--no-daemon"
    }

    Push-Location $repoRoot
    try {
        & $gradleWrapper @effectiveArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($lockTaken) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}
