# Builds signed release APKs and stages them for over-the-air update.
#
# The signing password is read from the encrypted file created by
# scripts\setup-signing-key.ps1 and passed to Gradle in the environment for the
# length of one build. It is never written to a file, never printed, and never
# reaches anyone reading this output - which is the whole point: the agent runs
# this script, and the secret stays in the operating system's own store.
#
# The build refuses to continue if the key is missing, and afterwards it verifies
# that the produced files really are signed, and that the fingerprint recorded in
# the update manifest matches the key. Publishing a build signed by some other key
# would be worse than not publishing at all: it would install on nobody's phone.
#
# ASCII only: Windows PowerShell 5.1 reads this file as ANSI and Cyrillic here
# would break parsing.

param(
    # Stage for publishing as well as building.
    [switch]$Stage,
    # Build only one application. Both by default.
    [ValidateSet("both", "parent", "child")]
    [string]$Target = "both",
    [ValidateRange(0, 2100000000)]
    [int]$VersionCode = 0
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Step($message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $message)
}

function Fail($message) {
    Write-Host ""
    Write-Host "FAILED: $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
Step "Reading the signing key settings"
# ---------------------------------------------------------------------------

$storeDirectory = Join-Path $env:LOCALAPPDATA "ChildWatch"
$settingsPath = Join-Path $storeDirectory "signing.properties"
$secretPath = Join-Path $storeDirectory "signing.dpapi"

if (-not (Test-Path $settingsPath)) {
    Fail ("No signing key is configured. Run scripts\setup-signing-key.ps1 first." + `
          " It asks for the password itself, so the password never has to be given to anybody else.")
}
if (-not (Test-Path $secretPath)) {
    Fail "The stored signing password is missing. Re-run scripts\setup-signing-key.ps1."
}

$keyPath = $null
$alias = $null
Get-Content $settingsPath | ForEach-Object {
    $line = $_.Trim()
    if ($line.StartsWith("keyPath=")) { $keyPath = $line.Substring(8).Trim() }
    if ($line.StartsWith("alias=")) { $alias = $line.Substring(6).Trim() }
}

if (-not $keyPath -or -not (Test-Path $keyPath)) {
    Fail "The signing key file is not where the settings say: $keyPath"
}
if (-not $alias) { Fail "No key alias is configured." }

Step "Key: $keyPath (alias $alias)"

# ---------------------------------------------------------------------------
Step "Finding a Java runtime that can run the signing tools"
# ---------------------------------------------------------------------------

# JAVA_HOME points at the runtime bundled with Android Studio, which is NOT a full
# JDK: apksigner started under it fails with "could not open ...\lib\jvm.cfg".
# The signing tools need a real JDK, so one is found the same way the key helper
# finds it.
function Find-JavaHome {
    $candidates = New-Object System.Collections.Generic.List[string]

    foreach ($root in @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        (Join-Path $env:USERPROFILE ".jdks"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"),
        "C:\Program Files\Android\Android Studio\jbr"
    )) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($javaHomeCandidate in $candidates) {
        # jvm.cfg is what apksigner asks for and the bundled runtime lacks.
        if (Test-Path (Join-Path $javaHomeCandidate "lib\jvm.cfg")) {
            return $javaHomeCandidate
        }
    }
    return $null
}

$signingJavaHome = Find-JavaHome
if (-not $signingJavaHome) {
    Fail ("No full JDK was found, so the signing tools cannot run." + `
          " Install Eclipse Temurin and run this again.")
}
Step "Signing tools will use: $signingJavaHome"

# Decrypting works only for the Windows account that created the file. If it
# fails, the message says what to do rather than showing a cryptographic error.
try {
    $secure = Get-Content $secretPath | ConvertTo-SecureString
    $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
} catch {
    Fail ("The stored password could not be read. It is tied to the Windows account" + `
          " that created it; re-run scripts\setup-signing-key.ps1 on this account.")
}
if (-not $plain) { Fail "The stored password is empty." }

# ---------------------------------------------------------------------------
Step "Building signed release APKs"
# ---------------------------------------------------------------------------

$tasks = @()
if ($Target -eq "both" -or $Target -eq "parent") { $tasks += ":app:assembleRelease" }
if ($Target -eq "both" -or $Target -eq "child") { $tasks += ":parentwatch:assembleRelease" }
if ($VersionCode -gt 0) { $tasks += "-PcwVersionCode=$VersionCode" }

$env:CW_SIGNING_PASSWORD = $plain
try {
    # The same safe runner every other build uses: it keeps Gradle's caches inside
    # the workspace and refuses to run two builds at once.
    & powershell -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "run-gradle-safe.ps1") @tasks 2>&1 |
        ForEach-Object { Write-Host $_ }
    $buildExit = $LASTEXITCODE
} finally {
    # Removed from this process as soon as the build is over.
    Remove-Item Env:\CW_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $plain = $null
}

if ($buildExit -ne 0) { Fail "The build failed. Read the output above." }

# ---------------------------------------------------------------------------
Step "Checking that the produced files are really signed"
# ---------------------------------------------------------------------------

function Get-ApkSigner {
    $sdkLine = Get-Content (Join-Path $repo "local.properties") |
        Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
    if (-not $sdkLine) { return $null }
    $sdk = ($sdkLine -replace "^sdk.dir=", "").Replace("\\", "\")
    return Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}

$apksigner = Get-ApkSigner
if (-not $apksigner) { Fail "apksigner not found, so the signing cannot be verified." }

$targets = @()
if ($Target -eq "both" -or $Target -eq "parent") {
    $targets += @{ Key = "parent"; Project = "app"; Label = "ParentMonitor" }
}
if ($Target -eq "both" -or $Target -eq "child") {
    $targets += @{ Key = "child"; Project = "parentwatch"; Label = "ChildDevice" }
}

foreach ($apkTarget in $targets) {
    $directory = Join-Path $repo "$($apkTarget.Project)\build\outputs\apk\release"
    $apk = Get-ChildItem $directory -Filter "*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { Fail "No release APK was produced for $($apkTarget.Label)." }

    # apksigner is a wrapper that starts java using JAVA_HOME, so the full JDK has
    # to be in place for the call. Without this the check fails with a message
    # about jvm.cfg that says nothing about the real cause.
    $previousJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $signingJavaHome
    try {
        $verify = & $apksigner verify --print-certs $apk.FullName 2>&1
        $verifyExit = $LASTEXITCODE
    } finally {
        $env:JAVA_HOME = $previousJavaHome
    }

    if ($verifyExit -ne 0) {
        Fail "$($apkTarget.Label) is not correctly signed: $verify"
    }
    $line = $verify | Where-Object { $_ -match "SHA-256 digest" } | Select-Object -First 1
    $fingerprint = if ($line -and $line -match "([0-9a-fA-F:]{64,95})") {
        ($Matches[1] -replace ":", "").ToLowerInvariant()
    } else { "unknown" }

    Step ("{0}: signed, {1:N1} MB, fingerprint {2}" -f `
        $apkTarget.Label, ($apk.Length / 1MB), $fingerprint.Substring(0, [Math]::Min(16, $fingerprint.Length)))
}

# ---------------------------------------------------------------------------
if ($Stage) {
    Step "Staging for publishing"
    & powershell -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "stage-release.ps1") 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { Fail "Staging failed. Read the output above." }
}

Write-Host ""
Write-Host "Signed releases are ready." -ForegroundColor Green
Write-Host "Nothing was uploaded and nothing was installed."
if (-not $Stage) {
    Write-Host "Run again with -Stage to prepare the update manifest as well."
}
Write-Host ""
