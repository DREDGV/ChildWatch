# Repairs the test child device in ONE run: the DSH sandbox ends child
# processes with each tool call, which kills the adb server and every
# WiFi/USB connection with it. Running the whole sequence inside a single
# process keeps the daemon alive for the duration of the work.
[CmdletBinding()]
param(
    [string]$WifiEndpoint = "192.168.3.71:40383",
    [string]$ServerUrl = "http://31.28.27.96:3000"
)

$ErrorActionPreference = "Continue"
$repo = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $repo "local.properties"))) { $repo = "C:\Users\dr-ed\ChildWatch" }

$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"

function Invoke-Adb {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 45)
    $job = Start-Job -ScriptBlock {
        param($exe, $args2)
        & $exe @args2 2>&1 | Out-String
    } -ArgumentList $adb, $Arguments
    if (Wait-Job -Job $job -Timeout $TimeoutSeconds) {
        $out = Receive-Job -Job $job
        Remove-Job -Job $job -Force
        return ($out | Out-String).Trim()
    }
    Stop-Job -Job $job -ErrorAction SilentlyContinue
    Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    return "__TIMEOUT__"
}

Write-Host "=== 1. adb server + connection ==="
Invoke-Adb @('start-server') | Out-Null
Start-Sleep -Seconds 1
$serial = $null
foreach ($candidate in @($WifiEndpoint) + ((Invoke-Adb @('devices')) -split "`n" |
        Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_.Trim() -split '\s+')[0] })) {
    if (-not $candidate) { continue }
    if ($candidate -match ':\d+$') { Invoke-Adb @('connect', $candidate) | Out-Null; Start-Sleep -Seconds 2 }
    $state = Invoke-Adb @('-s', $candidate, 'shell', 'echo', 'ok')
    if ($state -match 'ok') { $serial = $candidate; break }
}
if (-not $serial) { Write-Host "НЕТ УСТРОЙСТВА"; exit 1 }
Write-Host "device: $serial"

Write-Host "=== 2. stop app ==="
Invoke-Adb @('-s', $serial, 'shell', 'am', 'force-stop', $pkg) | Out-Null

Write-Host "=== 3. read current preferences ==="
foreach ($name in @('parentwatch_prefs', 'childwatch_prefs')) {
    $path = "shared_prefs/$name.xml"
    $raw = Invoke-Adb @('-s', $serial, 'shell', "run-as $pkg cat $path")
    $hasMap = $raw -match '<map'
    $serverUrl = ([regex]::Match($raw, 'name="server_url">([^<]*)<')).Groups[1].Value
    Write-Host ("  {0,-20} present={1} server_url='{2}'" -f $name, $hasMap, $serverUrl)
}

Write-Host "=== 4. repair ==="
foreach ($name in @('parentwatch_prefs', 'childwatch_prefs')) {
    $path = "shared_prefs/$name.xml"
    $raw = Invoke-Adb @('-s', $serial, 'shell', "run-as $pkg cat $path")
    if ($raw -notmatch '<map') { Write-Host "  $name : пропуск"; continue }

    $fixed = $raw -replace '<string name="server_url">[^<]*</string>', "<string name=`"server_url`">$ServerUrl</string>"
    $fixed = $fixed -replace 'https:\\/\\/', ($ServerUrl -replace '/', '\/' -replace ':', '\:')

    # Android 14 run-as does not reliably honour shell redirection, so the
    # content travels as a base64 argument and is decoded by the app's own
    # toolbox inside the sandbox.
    $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($fixed))
    $cmd = "run-as $pkg sh -c 'echo $b64 | base64 -d > $path'"
    $push = Invoke-Adb @('-s', $serial, 'shell', $cmd)

    $verify = Invoke-Adb @('-s', $serial, 'shell', "run-as $pkg cat $path")
    $newValue = ([regex]::Match($verify, 'name="server_url">([^<]*)<')).Groups[1].Value
    $sessionFixed = if ($verify -match 'https:\\/\\/') { "нет" } else { "да" }
    Write-Host "  $name -> server_url='$newValue' сессия=$sessionFixed"
    if ($push -and $push -ne "__TIMEOUT__") { Write-Host "     push output: $push" }
}

Write-Host "=== 5. relaunch app ==="
Invoke-Adb @('-s', $serial, 'shell', 'logcat', '-c') | Out-Null
Invoke-Adb @('-s', $serial, 'shell', 'monkey', '-p', $pkg, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 20

Write-Host "=== 6. what the app does now ==="
$log = Invoke-Adb @('-s', $serial, 'logcat', '-d', '-t', '400')
($log -split "`n") |
    Where-Object { $_ -match 'ChatBackgroundService|WebSocketClient|LocationService|register|Register|Expected authority|Failed to connect|FATAL|MainActivity|FamilyJoin' } |
    Select-Object -Last 25 | ForEach-Object { Write-Host ("  " + $_.Trim()) }

Write-Host "=== 7. saved server url ==="
$net = Invoke-Adb @('-s', $serial, 'shell', "run-as $pkg cat shared_prefs/parentwatch_prefs.xml")
$finalUrl = ([regex]::Match($net, 'name="server_url">([^<]*)<')).Groups[1].Value
Write-Host "  server_url = $finalUrl"
