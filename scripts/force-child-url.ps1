# Fixes the broken server URL on the test child phone while the app is stopped,
# then restarts it and reports whether the connection now works.
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [string]$UsbSerial = "102742534J001408",
    [string]$WifiEndpoint = "192.168.3.71:40383",
    [string]$ServerUrl = "http://31.28.27.96:3000"
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.parentwatch.debug"

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1
$serial = $null
foreach ($c in @($UsbSerial, $WifiEndpoint)) {
    if ($c -match ':\d+$') { Q @('connect', $c) | Out-Null; Start-Sleep -Seconds 2 }
    if ((Q @('-s', $c, 'shell', 'echo', 'ok')) -match 'ok') { $serial = $c; break }
}
if (-not $serial) { Write-Host "DEVICE NOT FOUND"; exit 1 }
Write-Host "device: $serial"

Write-Host "=== 1. stop the app completely ==="
Q @('-s', $serial, 'shell', 'am', 'force-stop', $pkg) | Out-Null
Start-Sleep -Seconds 2
Write-Host ("  running pids: " + (Q @('-s', $serial, 'shell', 'pidof', $pkg)))

Write-Host "=== 2. rewrite server_url in both preference files ==="
foreach ($name in @('parentwatch_prefs', 'childwatch_prefs')) {
    $path = "shared_prefs/$name.xml"
    $raw = Q @('-s', $serial, 'shell', "run-as $pkg cat $path")
    if ($raw -notmatch '<map') { Write-Host "  $name : cannot read, skipping"; continue }

    $fixed = $raw -replace '<string name="server_url">[^<]*</string>', "<string name=`"server_url`">$ServerUrl</string>"
    # Repair the copy embedded in the saved session JSON as well.
    $fixed = $fixed -replace 'https:\\/\\/', 'http:\/\/31.28.27.96:3000'

    $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($fixed))
    $push = Q @('-s', $serial, 'shell', "run-as $pkg sh -c 'echo $b64 | base64 -d > $path'")
    Start-Sleep -Milliseconds 500
    $check = Q @('-s', $serial, 'shell', "run-as $pkg cat $path")
    $value = ([regex]::Match($check, 'name="server_url">([^<]*)<')).Groups[1].Value
    $sessionOk = if ($check -match 'https:\\/\\/') { "NO" } else { "yes" }
    Write-Host ("  {0,-20} server_url='{1}' session_repaired={2}" -f $name, $value, $sessionOk)
    if ($push) { Write-Host ("     push output: " + $push) }
}

Write-Host "=== 3. restart the app ==="
Q @('-s', $serial, 'shell', 'logcat', '-c') | Out-Null
Q @('-s', $serial, 'shell', 'monkey', '-p', $pkg, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 25

Write-Host "=== 4. what the app does now ==="
$log = Q @('-s', $serial, 'logcat', '-d', '-t', '500')
($log -split "`n") | Where-Object {
    $_ -match 'ChatBackgroundService|WebSocketClient|Register|register|LocationService|URISyntax|Failed|authority'
} | Select-Object -Last 25 | ForEach-Object { Write-Host ("  " + $_.Trim()) }

Write-Host "=== 5. stored value after restart ==="
$after = ([regex]::Match((Q @('-s', $serial, 'shell', "run-as $pkg cat shared_prefs/parentwatch_prefs.xml")), 'name="server_url">([^<]*)<')).Groups[1].Value
Write-Host ("  server_url = '" + $after + "'")
