# Creates the release signing key for ChildWatch, once and for all.
#
# RUN THIS YOURSELF. It asks for a password, and that password never reaches the
# agent, never reaches the repository, and is never written in readable form:
# it is stored encrypted with the Windows Data Protection API, tied to this
# Windows account, in %LOCALAPPDATA%\ChildWatch\signing.dpapi
#
# Why the key matters more than it looks: Android installs an update only over a
# build signed by the SAME key. If the key is lost, every phone has to have the
# application removed and installed again, losing its data. If the key leaks,
# anybody can publish a build that installs over the real one. So:
#
#   - create it once, now, while the application has not spread to many phones;
#   - keep a copy of the key file AND the password on separate media;
#   - never commit either of them.
#
# ASCII only: Windows PowerShell 5.1 reads this file as ANSI and Cyrillic here
# would break parsing.

param(
    # Where the key file lives. Outside the repository by default; move it later
    # by re-running with a different -KeyDirectory, or by editing the small
    # settings file this script writes.
    [string]$KeyDirectory = "$env:USERPROFILE\ChildWatch-keys",
    [string]$KeyFileName = "childwatch-release.jks",
    [string]$Alias = "childwatch",
    [int]$ValidityYears = 30
)

$ErrorActionPreference = "Stop"

function Write-Step($message) {
    Write-Host ""
    Write-Host ("== {0}" -f $message) -ForegroundColor Cyan
}

function Write-Ok($message) { Write-Host ("   {0}" -f $message) -ForegroundColor Green }
function Write-Warn($message) { Write-Host ("   {0}" -f $message) -ForegroundColor Yellow }
function Write-Bad($message) { Write-Host ("   {0}" -f $message) -ForegroundColor Red }

<#
    Runs an external program and returns everything it printed.

    keytool and apksigner write their progress to the error stream - "Generating
    4096 bit RSA key pair" is a progress message, not a failure. With
    $ErrorActionPreference = 'Stop' PowerShell treats any error-stream output from a
    native program as a terminating error and aborts the whole script, which is
    what happened the first time this ran: the key generation was killed halfway
    and no file was produced.

    So the error preference is lowered for the duration of the call only, and the
    program's own exit code decides success.
#>
function Invoke-NativeTool {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>&1
        return @{ ExitCode = $LASTEXITCODE; Output = @($output) }
    } finally {
        $ErrorActionPreference = $previous
    }
}

Write-Host ""
Write-Host "ChildWatch release key" -ForegroundColor White
Write-Host "=====================" -ForegroundColor White
Write-Host "This runs once. Read the notes at the top of the script if you have not."

# ---------------------------------------------------------------------------
Write-Step "Finding a Java runtime that has keytool"
# ---------------------------------------------------------------------------

# Android Studio ships a runtime, not a full JDK: its bin holds java.exe and
# almost nothing else, so keytool is simply absent there. It is worth searching
# rather than reporting a missing tool the owner would have to install.
function Find-Keytool {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $candidates.Add("C:\Program Files\Android\Android Studio\jbr")

    # Every JDK the usual installers place on this machine.
    foreach ($root in @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        (Join-Path $env:USERPROFILE ".jdks"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium")
    )) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($javaHomeCandidate in $candidates) {
        if (-not $javaHomeCandidate) { continue }
        $tool = Join-Path $javaHomeCandidate "bin\keytool.exe"
        if (Test-Path $tool) { return $tool }
    }
    return $null
}

$keytool = Find-Keytool
if (-not $keytool) {
    Write-Bad "No Java runtime with keytool was found."
    Write-Bad "Install a JDK (for example Eclipse Temurin) and run this again."
    exit 1
}
Write-Ok "keytool: $keytool"

$apksigner = $null
$repo = Split-Path -Parent $PSScriptRoot
$sdkLine = Get-Content (Join-Path $repo "local.properties") -ErrorAction SilentlyContinue |
    Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
if ($sdkLine) {
    $sdk = ($sdkLine -replace "^sdk.dir=", "").Replace("\\", "\")
    $apksigner = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
if ($apksigner) { Write-Ok "apksigner: $apksigner" } else { Write-Warn "apksigner not found; the fingerprint will be read with keytool" }

# ---------------------------------------------------------------------------
Write-Step "Preparing the key directory"
# ---------------------------------------------------------------------------

if (-not (Test-Path $KeyDirectory)) {
    New-Item -ItemType Directory -Force -Path $KeyDirectory | Out-Null
    Write-Ok "created $KeyDirectory"
} else {
    Write-Ok "using $KeyDirectory"
}

$keyPath = Join-Path $KeyDirectory $KeyFileName

if (Test-Path $keyPath) {
    Write-Warn "A key already exists at:"
    Write-Warn "  $keyPath"
    Write-Warn ""
    Write-Warn "Do NOT replace it unless you are certain: replacing the key means"
    Write-Warn "every phone must have the application removed and installed again."
    $answer = Read-Host "Replace it? Type REPLACE to confirm"
    if ($answer -ne "REPLACE") {
        Write-Ok "Nothing changed."
        exit 0
    }
    $backup = "$keyPath.replaced-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Move-Item $keyPath $backup
    Write-Ok "old key moved aside: $backup"
}

# ---------------------------------------------------------------------------
Write-Step "Choosing the password"
# ---------------------------------------------------------------------------

Write-Host "   The password protects the key file. It is stored encrypted for THIS"
Write-Host "   Windows account only. If you reinstall Windows or move to another"
Write-Host "   account, the stored copy stops working - so write the password down"
Write-Host "   as well and keep it with the backup copy of the key."
Write-Host ""

$password = $null
while ($true) {
    $first = Read-Host "Password (at least 12 characters)" -AsSecureString
    $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($first))
    if ($plain.Length -lt 12) {
        Write-Bad "Too short. Use at least 12 characters."
        continue
    }
    $second = Read-Host "Repeat the password" -AsSecureString
    $again = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($second))
    if ($plain -ne $again) {
        Write-Bad "The two entries differ. Try again."
        continue
    }
    $password = $plain
    $plain = $null
    $again = $null
    break
}
Write-Ok "password accepted"

# ---------------------------------------------------------------------------
Write-Step "Creating the key"
# ---------------------------------------------------------------------------

$dname = "CN=ChildWatch Release, OU=ChildWatch, O=ChildWatch, C=RU"

# -storetype PKCS12 is what modern Android tooling expects; JKS still works but
# PKCS12 is the format that will keep working.
$result = Invoke-NativeTool -FilePath $keytool -Arguments @(
    "-genkeypair",
    "-keystore", $keyPath,
    "-storetype", "PKCS12",
    "-alias", $Alias,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-validity", (365 * $ValidityYears),
    "-dname", $dname,
    "-storepass", $password,
    "-keypass", $password
)
$result.Output | ForEach-Object { Write-Host ("   " + $_) }

if (-not (Test-Path $keyPath)) {
    Write-Bad "The key was not created (exit code $($result.ExitCode)). Read the output above."
    exit 1
}
Write-Ok "key created: $keyPath"

# ---------------------------------------------------------------------------
Write-Step "Storing the password encrypted for this Windows account"
# ---------------------------------------------------------------------------

$storeDirectory = Join-Path $env:LOCALAPPDATA "ChildWatch"
New-Item -ItemType Directory -Force -Path $storeDirectory | Out-Null
$secretPath = Join-Path $storeDirectory "signing.dpapi"

# ConvertFrom-SecureString with no key uses DPAPI: the file can only be read by
# this account on this machine. The agent runs the build and never sees this.
$password | ConvertTo-SecureString -AsPlainText -Force |
    ConvertFrom-SecureString | Set-Content -Path $secretPath -Encoding ASCII

$settingsPath = Join-Path $storeDirectory "signing.properties"
@(
    "# Written by scripts\setup-signing-key.ps1. Safe to read; holds no password.",
    "keyPath=$keyPath",
    "alias=$Alias"
) | Set-Content -Path $settingsPath -Encoding ASCII

Write-Ok "password stored encrypted: $secretPath"
Write-Ok "settings stored: $settingsPath"

# ---------------------------------------------------------------------------
Write-Step "The certificate fingerprint"
# ---------------------------------------------------------------------------

$fingerprint = $null
$listing = Invoke-NativeTool -FilePath $keytool -Arguments @(
    "-list", "-v",
    "-keystore", $keyPath,
    "-storetype", "PKCS12",
    "-alias", $Alias,
    "-storepass", $password
)
$listing.Output | ForEach-Object {
    if ($_ -match "SHA256:\s*([0-9A-Fa-f:]{95})") { $fingerprint = ($Matches[1] -replace ":", "") }
}

if ($fingerprint) {
    Write-Host ""
    Write-Host "   SHA-256 fingerprint of the signing certificate:" -ForegroundColor White
    Write-Host "   $($fingerprint.ToLowerInvariant())" -ForegroundColor White
    Write-Host ""
    Write-Host "   The release step records this in the update manifest, and the"
    Write-Host "   application checks a downloaded build against the key it is"
    Write-Host "   already signed with. Keep a note of it: it is how you prove later"
    Write-Host "   that a published build really came from this key."
} else {
    Write-Warn "Could not read the fingerprint; run keytool -list -v yourself."
}

# ---------------------------------------------------------------------------
Write-Step "What you must do now, by hand"
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "   1. COPY THE KEY FILE somewhere else - a USB stick, an encrypted"
Write-Host "      archive, a password manager attachment:" -ForegroundColor Yellow
Write-Host "        $keyPath"
Write-Host ""
Write-Host "   2. WRITE THE PASSWORD DOWN and keep it with that copy." -ForegroundColor Yellow
Write-Host "      The stored copy above only works for this Windows account on this"
Write-Host "      machine. Losing both means every phone has to be reinstalled."
Write-Host ""
Write-Host "   3. Do not move the key into the repository. It is ignored there, and"
Write-Host "      the ignore rules are what keep it from being published."
Write-Host ""
Write-Host "Done. A release build will now be signed automatically." -ForegroundColor Green
Write-Host ""
