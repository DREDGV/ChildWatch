# Prepares a runtime-only ChildWatch server update archive.
#
# The archive contains ONLY the server files that changed since the given
# baseline commit. It never contains the database, uploads, auth sessions,
# logs or node_modules, so installing it cannot delete user data.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-server-update.ps1
[CmdletBinding()]
param(
    [string]$BaseCommit = "f283c6f",
    [string]$OutDir = "artifacts\server"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

$version = (Get-Content (Join-Path $repoRoot "server\package.json") -Raw |
    ConvertFrom-Json).version
$head = (git rev-parse --short HEAD).Trim()
$stamp = Get-Date -Format "yyyyMMdd"
$name = "childwatch-server-$version-$stamp-$head"
$stage = Join-Path $repoRoot (Join-Path $OutDir $name)

if (Test-Path -LiteralPath $stage) {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# Only tracked server files that differ from the baseline. The deployment
# baseline is the last server release that went to the VPS.
$changed = git diff --name-only "$BaseCommit..HEAD" -- server |
    Where-Object { $_ -match '^server/' } |
    ForEach-Object { $_.Substring("server/".Length) } |
    Where-Object {
        $_ -notmatch '^node_modules/' -and
        $_ -notmatch '^\.emulator-lab/' -and
        $_ -notmatch '^uploads/' -and
        $_ -notmatch '^deploy/' -and
        $_ -notmatch '^data/' -and
        $_ -ne 'childwatch.db' -and
        $_ -notmatch '\.zip$' -and
        $_ -notmatch '\.log$'
    }

if (-not $changed) {
    throw "No server changes found between $BaseCommit and HEAD."
}

$installed = @()
foreach ($relative in $changed) {
    $source = Join-Path $repoRoot (Join-Path "server" $relative)
    if (-not (Test-Path -LiteralPath $source)) {
        Write-Warning "Skipping removed file: $relative"
        continue
    }
    $target = Join-Path $stage $relative
    $targetDir = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $targetDir)) {
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    }
    Copy-Item -LiteralPath $source -Destination $target -Force
    $installed += $relative
}

$manifestPath = Join-Path $stage "MANIFEST.txt"
$manifest = @(
    "ChildWatch server update",
    "version: $version",
    "baseline commit: $BaseCommit",
    "head commit: $head",
    "built: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "",
    "Files ($($installed.Count)):"
) + ($installed | Sort-Object | ForEach-Object { "  $_" })
[System.IO.File]::WriteAllLines($manifestPath, $manifest)

$readmeSource = Join-Path $repoRoot "scripts\server-update-README.txt"
if (Test-Path -LiteralPath $readmeSource) {
    Copy-Item -LiteralPath $readmeSource -Destination (Join-Path $stage "README.txt") -Force
}

$zipPath = Join-Path $repoRoot (Join-Path $OutDir "$name.zip")
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

# Compress-Archive writes Windows backslash separators into entry names, which
# would turn "routes/media.js" into a single file called "routes\media.js" on
# Linux. Entries are therefore written explicitly with forward slashes.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open(
    $zipPath,
    [System.IO.Compression.ZipArchiveMode]::Create
)
try {
    $stagePrefix = $stage.TrimEnd('\') + '\'
    Get-ChildItem -LiteralPath $stage -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($stagePrefix.Length).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive,
            $_.FullName,
            $relative,
            [System.IO.Compression.CompressionLevel]::Optimal
        )
    }
} finally {
    $archive.Dispose()
}

$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
$size = (Get-Item -LiteralPath $zipPath).Length

# Confirm the entry names are Linux-safe before reporting success.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$verify = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $badNames = $verify.Entries |
        Where-Object { $_.FullName -match '\\' } |
        ForEach-Object { $_.FullName }
} finally {
    $verify.Dispose()
}
if ($badNames) {
    throw "Archive contains Windows-style entry names: $($badNames -join ', ')"
}

Write-Host ""
Write-Host "Archive:  $zipPath"
Write-Host "Size:     $size bytes"
Write-Host "SHA-256:  $hash"
Write-Host "Files:    $($installed.Count)"
Write-Host ""
Write-Host "Install instructions: scripts\server-update-README.txt"
