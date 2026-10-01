# One-button release: build both applications, publish them for over-the-air
# update, and verify what the server actually serves.
#
# Written to be pressed by a person in Explorer, so every step either works or
# stops with a sentence saying what to do. Nothing is published half-way: the
# upload only starts after the build succeeded and the signing certificate was
# checked, and the public result is re-downloaded and compared afterwards.
#
# ASCII only: Windows PowerShell 5.1 reads a script as ANSI unless it carries a
# byte order mark, and this project was bitten by that before.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release-updates.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release-updates.ps1 -PreflightOnly

param(
    [switch]$PreflightOnly,
    [int]$VersionCode = 0
)

$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
$updatesDir = Join-Path $repo 'updates'
$server = 'root@31.28.27.96'
$key = Join-Path $env:USERPROFILE '.ssh\atlas_vds_ed25519'
$knownHosts = Join-Path $repo '.ssh-local\known_hosts'
$ssh = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe'
$scp = Join-Path $env:SystemRoot 'System32\OpenSSH\scp.exe'
$publicBase = 'http://31.28.27.96:3000'
. (Join-Path $PSScriptRoot 'update-version-guard.ps1')
$expectedFingerprint = '4ca0ad1687dfff330ef81aedb2f226989f7936820ee4749300358172fef7982d'

function Say($text) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $text) }
function Head($text) { Write-Host ''; Write-Host ("=== {0} ===" -f $text) }
function Stop-With($text) {
    Write-Host ''
    Write-Host ("STOPPED: {0}" -f $text) -ForegroundColor Yellow
    Write-Host 'Nothing was published. Fix the reason above and press the button again.'
    exit 1
}

Head 'preflight'
foreach ($tool in @($ssh, $scp)) {
    if (-not (Test-Path -LiteralPath $tool)) { Stop-With "missing $tool (OpenSSH client is part of Windows 10+)" }
}
if (-not (Test-Path -LiteralPath $key)) { Stop-With "missing the server key at $key" }
if (-not (Test-Path -LiteralPath $knownHosts)) { Stop-With "missing $knownHosts" }
Say "repository: $repo"
Say "this will build both applications and publish them for over-the-air update"

if ($PreflightOnly) {
    Head 'checking the server connection'
    $probe = (& $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=10 -o BatchMode=yes $server 'echo OK' 2>&1 | Out-String).Trim()
    Say ("ssh probe: {0}" -f $probe)
    if ($probe -notmatch 'OK') {
        Write-Host ''
        Write-Host 'The server cannot be reached from this computer. The usual cause is the VPN.'
        Write-Host 'Turn the VPN off, then press the button again.'
        exit 2
    }
    Say 'the server answers; a real release would work now'
    exit 0
}

function Read-PublishedManifest {
    $json = (& $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=12 -o BatchMode=yes $server 'curl -fsS --max-time 10 http://localhost:3000/updates/manifest' 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { Stop-With 'cannot read published manifest; refusing to guess its version' }
    try { return ($json | ConvertFrom-Json -ErrorAction Stop) }
    catch { Stop-With 'published manifest is invalid JSON' }
}
$published = Read-PublishedManifest
$highestCode = 0L
foreach ($appName in @('parent', 'child')) {
    $code = 0L
    if (-not [long]::TryParse([string]$published.apps.$appName.versionCode, [ref]$code) -or $code -le 0) { Stop-With 'published version code is missing or invalid' }
    $highestCode = [Math]::Max($highestCode, $code)
}
Get-ChildItem (Join-Path $repo 'releases') -Filter manifest.json -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
    try { $local = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json -ErrorAction Stop
        foreach ($appName in @('parent', 'child')) { $code=0L; if ([long]::TryParse([string]$local.apps.$appName.versionCode,[ref]$code)) { $highestCode=[Math]::Max($highestCode,$code) } }
    } catch { Stop-With 'a local release manifest is unreadable; choose a verified new number' }
}
if ($VersionCode -eq 0) {
    if ($highestCode -ge 2147483647) { Stop-With 'version code limit reached' }
    $VersionCode = [int]($highestCode + 1)
} elseif ($VersionCode -le $highestCode) { Stop-With "requested code $VersionCode is not newer than staged/published $highestCode" }
Say "new explicit version code: $VersionCode"

Head 'step 1 of 5: building both applications, signed with the project key'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-and-install.ps1') -Target both -BuildOnly -VersionCode $VersionCode -BuildTimeoutSeconds 900 2>&1 |
    Select-String -Pattern 'gradle: BUILD|gradle: signing|^e: |error:|done' | ForEach-Object { $_.Line }
if ($LASTEXITCODE -ne 0) { Stop-With 'the build did not succeed' }
Say 'build finished'

Head 'step 2 of 5: staging the release and checking the signature'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'stage-release.ps1') -UseDebugBuilds 2>&1 |
    ForEach-Object { $_.ToString() }
if ($LASTEXITCODE -ne 0) { Stop-With 'staging failed' }
$manifestPath = Join-Path $updatesDir 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { Stop-With 'no manifest was produced' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
# `$appName`, not `$key`: the same name was used for the SSH key path, and the loop
# overwrote it, so every later ssh used "-i child" and the server refused the key.
foreach ($appName in @('parent', 'child')) {
    $entry = $manifest.apps.$appName
    if (-not $entry) { Stop-With "the manifest has no $appName entry" }
    if ($entry.signingCertSha256 -ne $expectedFingerprint) {
        Stop-With "the $appName build is NOT signed with the project key (found '$($entry.signingCertSha256)'). A build signed with another key cannot be installed over what the family already runs."
    }
    Say ("{0}: version {1} (code {2}), {3:N1} MB, signed with the project key" -f $appName, $entry.versionName, $entry.versionCode, ($entry.sizeBytes / 1MB))
}

Head 'step 3 of 5: reaching the server'
$probe = (& $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=12 -o BatchMode=yes $server 'echo OK' 2>&1 | Out-String).Trim()
Say ("ssh probe: {0}" -f $probe)
if ($probe -notmatch 'OK') {
    Write-Host ''
    Write-Host 'The server cannot be reached from this computer, so nothing was uploaded.'
    Write-Host 'The usual cause is the VPN: turn it off and press the button again.'
    Write-Host 'The build is already done and staged, so the second press will be quick.'
    exit 2
}
Say 'the server answers'

$published = Read-PublishedManifest
try { Assert-NewerUpdateManifest $manifest $published } catch { Stop-With $_.Exception.Message }
Head 'step 4 of 5: uploading and publishing'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupCommand = "cd /var/www/childwatch-updates && cp manifest.json manifest.json.bak-$stamp && rm -rf /tmp/cw-updates-in && mkdir -p /tmp/cw-updates-in && echo READY"
$ready = (& $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=20 $server $backupCommand 2>&1 | Out-String).Trim()
Say ("the previous manifest was kept as manifest.json.bak-{0}: {1}" -f $stamp, $ready)
if ($ready -notmatch 'READY') { Stop-With 'the server did not accept the preparation step' }

$files = @($manifestPath)
foreach ($appName in @('parent', 'child')) { $files += (Join-Path $updatesDir $manifest.apps.$appName.file) }
foreach ($file in $files) {
    if (-not (Test-Path -LiteralPath $file)) { Stop-With "missing staged file $file" }
}
Say ("uploading {0} file(s)" -f $files.Count)
& $scp -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=20 $files "$server`:/tmp/cw-updates-in/" 2>&1 |
    ForEach-Object { $_.ToString().Trim() }
if ($LASTEXITCODE -ne 0) { Stop-With 'the upload failed' }
Say 'upload finished'

$publishScript = Join-Path $repo '.ssh-local\publish-updates.sh'
if (-not (Test-Path -LiteralPath $publishScript)) { Stop-With "missing $publishScript" }
Get-Content -LiteralPath $publishScript -Raw | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=25 $server 'bash -s' 2>&1 |
    Select-String -Pattern 'update directory|\.apk|manifest\.json|error' | ForEach-Object { $_.Line.Trim() }
if ($LASTEXITCODE -ne 0) { Stop-With 'remote publication refused or failed' }
Say 'publish finished'

Head 'step 5 of 5: checking what the server actually serves'
$served = $null
try {
    $served = (Invoke-WebRequest -Uri "$publicBase/updates/manifest" -TimeoutSec 30 -UseBasicParsing).Content | ConvertFrom-Json
} catch {
    Stop-With "the published manifest could not be read back: $($_.Exception.Message)"
}
foreach ($appName in @('parent', 'child')) {
    $entry = $served.apps.$appName
    if ($entry.versionCode -ne $manifest.apps.$appName.versionCode -or $entry.sha256 -ne $manifest.apps.$appName.sha256 -or $entry.packageName -ne $manifest.apps.$appName.packageName) { Stop-With "served $appName does not match this release" }
    Say ("served {0}: {1} (code {2})" -f $appName, $entry.versionName, $entry.versionCode)
    $temp = Join-Path $env:TEMP ("cw-verify-" + $entry.file)
    Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
    Invoke-WebRequest -Uri "$publicBase/updates/files/$($entry.file)" -OutFile $temp -TimeoutSec 300 -UseBasicParsing
    $hash = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant()
    $sizeOk = (Get-Item -LiteralPath $temp).Length -eq $entry.sizeBytes
    $hashOk = $hash -eq $entry.sha256
    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
    if (-not ($sizeOk -and $hashOk)) { Stop-With "the file served for $appName does not match the manifest (size $sizeOk, checksum $hashOk)" }
    Say ("{0}: size and checksum match the manifest - a phone will accept this file" -f $appName)
}

Head 'done'
Write-Host ("Published: parent {0}, child {1}" -f $served.apps.parent.versionName, $served.apps.child.versionName)
Write-Host ("Phones update themselves from {0}/updates/manifest" -f $publicBase)
Write-Host ''
Write-Host 'Reminder: a phone must already run a build signed with the same project key.'
Write-Host 'A phone whose application was signed with another key needs one cable install.'
exit 0
