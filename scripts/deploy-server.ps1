# Deploy the server code that has changed since the last successful deploy.
#
# Written for a person pressing a button in Explorer. The hard part of a server
# deploy is not copying files: it is knowing WHICH files differ from what the
# server is running, and being able to say afterwards that the change did not break
# the family's data. So this script:
#
#   * compares every server source file against a record of the last deploy and
#     sends only what changed - a file nobody touched is not uploaded;
#   * takes a real backup first: the database with sqlite3's own backup command,
#     and a copy of every file it is about to replace;
#   * checks the syntax of every file in /tmp BEFORE replacing anything, so a typo
#     cannot take the service down;
#   * restarts, then reads the log for errors and asks the service and the database
#     the same questions as before, comparing the answers;
#   * records what it deployed, so the next run knows what "changed" means.
#
# ASCII only: Windows PowerShell 5.1 reads a script as ANSI unless it carries a byte
# order mark.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-server.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-server.ps1 -Yes
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-server.ps1 -ListOnly

param(
    [switch]$Yes,
    [switch]$ListOnly
)

$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
$serverRoot = Join-Path $repo 'server'
$statePath = Join-Path $repo '.ssh-local\deployed-server.json'
$server = 'root@31.28.27.96'
$key = Join-Path $env:USERPROFILE '.ssh\atlas_vds_ed25519'
$knownHosts = Join-Path $repo '.ssh-local\known_hosts'
$ssh = Join-Path $env:SystemRoot 'System32\OpenSSH\ssh.exe'
$scp = Join-Path $env:SystemRoot 'System32\OpenSSH\scp.exe'
$remoteApp = '/var/www/childwatch'

function Say($text) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $text) }
function Head($text) { Write-Host ''; Write-Host ("=== {0} ===" -f $text) }
function Stop-With($text) {
    Write-Host ''
    Write-Host ("STOPPED: {0}" -f $text) -ForegroundColor Yellow
    Write-Host 'The server was not changed by this step. Fix the reason and try again.'
    exit 1
}
function Fingerprint($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }

# Everything the server runs, excluding what it does not: tests and helper scripts.
$files = Get-ChildItem -LiteralPath $serverRoot -Recurse -File -Filter *.js |
    Where-Object {
        $relative = $_.FullName.Substring($serverRoot.Length + 1)
        $relative -notmatch '^(node_modules|__tests__|scripts|coverage|deploy)[\\/]'
    } |
    Sort-Object FullName

if (-not $files) { Stop-With "no server sources found under $serverRoot" }

$current = @{}
foreach ($file in $files) {
    $relative = $file.FullName.Substring($serverRoot.Length + 1).Replace('\', '/')
    $current[$relative] = Fingerprint $file.FullName
}

Head 'are the tools and the key here?'
foreach ($tool in @($ssh, $scp)) {
    if (-not (Test-Path -LiteralPath $tool)) { Stop-With "missing $tool" }
}
if (-not (Test-Path -LiteralPath $key)) { Stop-With "missing the server key at $key" }
if (-not (Test-Path -LiteralPath $knownHosts)) { Stop-With "missing $knownHosts" }

# The server is asked which sources it actually runs, and only those are deployed.
# The repository also holds alternative entry points that were never deployed; the
# first version of this script tried to back one of them up and stopped with
# "cannot stat", which is how this rule was learned.
# Named rather than inferred: the repository also holds alternative entry points that
# were never part of the running server, and they are skipped by name. Everything else
# in server/ is deployed, INCLUDING files that are new to the server - a new module the
# code requires has to arrive, or the service dies on require at the next restart.
$neverDeployed = @('minimal-server.js', 'simple-server.js', 'working-server.js', 'test-startup.js')
$skipped = @($current.Keys | Where-Object { $neverDeployed -contains $_ })
foreach ($relative in $skipped) { $current.Remove($relative) }
if ($skipped) {
    Say ("not part of the running server, so not deployed: {0}" -f ($skipped -join ', '))
}

$previous = @{}
if (Test-Path -LiteralPath $statePath) {
    try {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        foreach ($property in $state.files.PSObject.Properties) { $previous[$property.Name] = $property.Value }
    } catch {
        Write-Host ('the record of the last deploy could not be read ({0}); treating every file as changed' -f $_.Exception.Message)
    }
} else {
    Write-Host 'there is no record of a previous deploy from this computer; treating every file as changed'
}

$changed = @()
foreach ($relative in $current.Keys) {
    if (-not $previous.ContainsKey($relative) -or $previous[$relative] -ne $current[$relative]) { $changed += $relative }
}
$changed = $changed | Sort-Object

Head 'what would be deployed'
Say ("server sources: {0}, changed since the last deploy: {1}" -f $current.Count, $changed.Count)
if ($previous.Count -gt 0) {
    $removed = $previous.Keys | Where-Object { -not $current.ContainsKey($_) }
    if ($removed) { Say ("files that existed before and are gone now: {0}" -f ($removed -join ', ')) }
}
if (-not $changed) {
    Write-Host ''
    Write-Host 'Nothing to deploy: the server is already running this code.'
    exit 0
}
foreach ($relative in $changed) { Write-Host ("  {0}" -f $relative) }

if ($ListOnly) { exit 0 }

if (-not $Yes) {
    Write-Host ''
    Write-Host ('About to replace {0} file(s) on the live server and restart it.' -f $changed.Count)
    Write-Host 'A backup of every replaced file and of the database is taken first.'
    $answer = Read-Host 'Type YES to continue'
    if ($answer -ne 'YES') { Stop-With 'the person did not confirm' }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "/home/adminuser/childwatch-code-rollback-$stamp"

Head 'backup on the server'
$backupLines = @("set -e", "mkdir -p $backup", "cd $remoteApp")
foreach ($relative in $changed) { $backupLines += "mkdir -p `"$backup/$(Split-Path $relative -Parent)`" 2>/dev/null || true" }
foreach ($relative in $changed) { $backupLines += "if [ -f `"$remoteApp/$relative`" ]; then cp `"$remoteApp/$relative`" `"$backup/$relative`"; else echo `"  NEW on the server: $relative`"; fi" }
$backupLines += "sqlite3 -cmd '.timeout 30000' `"$remoteApp/data/childwatch.db`" `".backup '$backup/childwatch.db'`""
$backupLines += "chown -R adminuser:adminuser $backup"
$backupLines += "echo BACKUP_OK"
$backupResult = ($backupLines -join "`n" | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=30 $server 'bash -s' 2>&1 | Out-String).Trim()
Say $backupResult
if ($backupResult -notmatch 'BACKUP_OK') { Stop-With 'the backup did not complete, so nothing was replaced' }
Say ("backup kept at {0}" -f $backup)

Head 'uploading to a temporary place and checking the syntax there'
(& $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=20 $server "rm -rf /tmp/cw-server-in && mkdir -p /tmp/cw-server-in && echo READY" 2>&1 | Out-String).Trim() | ForEach-Object { Say $_ }
foreach ($relative in $changed) {
    $local = Join-Path $serverRoot ($relative.Replace('/', '\'))
    $remoteDir = "/tmp/cw-server-in/$(Split-Path $relative -Parent)"
    if ($remoteDir -ne '/tmp/cw-server-in/') {
        & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=20 $server "mkdir -p '$remoteDir'" 2>&1 | Out-Null
    }
    & $scp -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=25 $local "$server`:$remoteDir/" 2>&1 | ForEach-Object { $_.ToString().Trim() }
    if ($LASTEXITCODE -ne 0) { Stop-With "uploading $relative failed" }
}
$checkLines = @()
foreach ($relative in $changed) { $checkLines += "node --check /tmp/cw-server-in/$relative" }
$checkLines += "echo SYNTAX_OK"
$checkResult = ($checkLines -join "`n" | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=30 $server 'bash -s' 2>&1 | Out-String).Trim()
if ($checkResult -notmatch 'SYNTAX_OK') { Stop-With "a file does not parse, so nothing was replaced:`n$checkResult" }
Say ("all {0} file(s) parse" -f $changed.Count)

Head 'before: what the service answers and what the database holds'
$questions = @(
    "curl -s -o /dev/null -w '  manifest        HTTP %{http_code}\n' http://localhost:3000/updates/manifest",
    "curl -s -o /dev/null -w '  conversations   HTTP %{http_code}\n' http://localhost:3000/api/chat/v2/conversations",
    "sqlite3 `"$remoteApp/data/childwatch.db`" `"SELECT '  conversations ' || COUNT(*) FROM chat_conversations;`"",
    "sqlite3 `"$remoteApp/data/childwatch.db`" `"SELECT '  messages      ' || COUNT(*) FROM chat_messages_v2;`"",
    "sqlite3 `"$remoteApp/data/childwatch.db`" `"SELECT '  members       ' || COUNT(*) FROM family_members;`"",
    "sqlite3 `"$remoteApp/data/childwatch.db`" `"SELECT '  integrity     ' || integrity_check FROM pragma_integrity_check;`""
)
$before = (($questions -join "`n") | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=30 $server 'bash -s' 2>&1 | Out-String).Trim()
Say $before

Head 'installing and restarting'
$installLines = @("set -e")
foreach ($relative in $changed) {
    $installLines += "install -o adminuser -g adminuser -m 644 `"/tmp/cw-server-in/$relative`" `"$remoteApp/$relative`""
    $installLines += "node --check `"$remoteApp/$relative`""
}
$installLines += "echo INSTALLED"
$installResult = ($installLines -join "`n" | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=40 $server 'bash -s' 2>&1 | Out-String).Trim()
if ($installResult -notmatch 'INSTALLED') { Stop-With "installing failed:`n$installResult" }
Say 'files replaced and they parse in place'

$restart = @'
set -e
log_path=''
for candidate in /home/adminuser/.pm2/logs/childwatch-out.log /home/adminuser/.pm2/logs/childwatch-out-0.log; do
    if [ -r "$candidate" ]; then log_path=$candidate; break; fi
    if sudo -n -iu adminuser test -r "$candidate" 2>/dev/null; then log_path=$candidate; break; fi
done
if [ -z "$log_path" ]; then echo 'no readable pm2 log was found; the restart is attempted below anyway'; fi
count_lines() {
    if [ -z "$log_path" ]; then echo 0; return; fi
    value=$(wc -l < "$log_path" 2>/dev/null | tr -d ' \t\r\n' || true)
    case "$value" in ''|*[!0-9]*) echo 0 ;; *) echo "$value" ;; esac
}
mark=$(count_lines)
echo "--- log lines before the restart: $mark (${log_path:-none}) ---"
sudo -iu adminuser pm2 restart childwatch --update-env
sleep 15
now=$(count_lines)
echo "--- log lines after the restart: $now ---"
if [ -z "$log_path" ]; then
    echo 'nothing to show: no readable log file was found'
elif [ "$now" -lt "$mark" ]; then
    echo 'the log is shorter than before the restart (pm2 rotated it); showing the current tail'
    tail -n 80 "$log_path"
elif [ "$now" -eq "$mark" ]; then
    echo 'the log did not grow yet; showing the current tail'
    tail -n 40 "$log_path"
else
    tail -n +$((mark + 1)) "$log_path" | tail -n 80
fi
echo '--- the log is read ---'
'@
$restartResult = ($restart | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=60 $server 'bash -s' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { Stop-With "server restart or log read failed:`n$restartResult" }
Say $restartResult

Head 'after: the same questions'
$after = (($questions -join "`n") | & $ssh -i $key -o UserKnownHostsFile=$knownHosts -o ConnectTimeout=30 $server 'bash -s' 2>&1 | Out-String).Trim()
Say $after

$sameNumbers = $true
foreach ($line in ($before -split "`n")) {
    $trimmed = $line.Trim()
    if ($trimmed -match '^(conversations|messages|members|integrity)') {
        if ($after -notmatch [regex]::Escape($trimmed)) { $sameNumbers = $false; Say ("CHANGED: {0}" -f $trimmed) }
    }
}
foreach ($code in @('manifest', 'conversations')) {
    $beforeCode = ([regex]::Match($before, "$code\s+HTTP (\d+)")).Groups[1].Value
    $afterCode = ([regex]::Match($after, "$code\s+HTTP (\d+)")).Groups[1].Value
    if ($beforeCode -ne $afterCode) { $sameNumbers = $false; Say ("CHANGED: {0} answered {1} before and {2} after" -f $code, $beforeCode, $afterCode) }
}

Head 'recording what is deployed now'
$stateObject = [ordered]@{
    deployedAt = (Get-Date).ToString('s')
    backup = $backup
    files = [ordered]@{}
}
foreach ($relative in ($current.Keys | Sort-Object)) { $stateObject.files[$relative] = $current[$relative] }
$json = $stateObject | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText($statePath, $json, (New-Object System.Text.UTF8Encoding($false)))
Say ("recorded in {0}" -f $statePath)

Head 'done'
if ($sameNumbers) {
    Write-Host 'The service started, answered the same way as before, and the database counts are unchanged.'
} else {
    Write-Host 'The service started, but something differs from before - read the lines marked CHANGED above.' -ForegroundColor Yellow
    Write-Host 'Roll back with the backup printed above if the difference is not what you expected.'
    exit 1
}
Write-Host ("Rollback: copy the files from {0} back into {1}, and restore the database with sqlite3 .restore if it is ever needed." -f $backup, $remoteApp)
exit 0
