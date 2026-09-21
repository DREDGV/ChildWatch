# Restores working credentials for the parent app on the test phone.
#
# Why this is needed: the app's access token expired and its refresh token is no
# longer valid on the server (the server session is gone), so every family
# directory request answers 401. The app has no re-registration fallback, so it
# stays locked out and the invite screen disables its create button.
#
# The fix writes freshly issued credentials into the app's token store while the
# app is stopped, then the app picks them up on start.
# ASCII-only (Windows PowerShell 5.1 reads scripts as ANSI).
[CmdletBinding()]
param(
    [string]$Serial = "PT19655KA1280800674",
    [string]$ServerUrl = "http://31.28.27.96:3000",
    [string]$DeviceId = "device_15e991bb5d8f906d"
)

$repo = "C:\Users\dr-ed\ChildWatch"
$sdkLine = Get-Content (Join-Path $repo "local.properties") | Where-Object { $_ -like 'sdk.dir=*' }
$adb = Join-Path (($sdkLine -replace '^sdk.dir=', '').Replace('\\', '\')) 'platform-tools\adb.exe'
$pkg = "ru.example.childwatch"

function Q { param([string[]]$A) ((& $adb @A 2>&1) | Out-String).Trim() }

Write-Host "=== 1. issue fresh credentials for this device ==="
$body = @{
    deviceId   = $DeviceId
    deviceName = "ParentMonitor"
    deviceType = "android"
    appVersion = "7.3.26257"
} | ConvertTo-Json -Compress
$reg = Invoke-RestMethod -Uri "$ServerUrl/api/auth/register" -Method POST -Body $body -ContentType "application/json" -TimeoutSec 20
if (-not $reg.success) { Write-Host "  registration failed"; exit 1 }
$expiryMs = [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) + ([long]$reg.expiresIn * 1000)
Write-Host ("  access token expires in " + $reg.expiresIn + " s (at " + ([DateTimeOffset]::FromUnixTimeMilliseconds($expiryMs).UtcDateTime) + " UTC)")

Write-Host "=== 2. verify the new token against the family directory ==="
try {
    $r = Invoke-WebRequest -UseBasicParsing -Uri "$ServerUrl/api/families" -Headers @{ Authorization = "Bearer $($reg.authToken)" } -TimeoutSec 15
    Write-Host ("  /api/families -> " + $r.StatusCode)
} catch {
    Write-Host ("  /api/families FAILED: " + $_.Exception.Message)
    exit 1
}

& $adb start-server 2>&1 | Out-Null
Start-Sleep -Seconds 1
Write-Host "=== 3. stop the app so it cannot overwrite the store ==="
Q @('-s', $Serial, 'shell', 'am', 'force-stop', $pkg) | Out-Null
Start-Sleep -Seconds 2
Write-Host ("  running pids: '" + (Q @('-s', $Serial, 'shell', 'pidof', $pkg)) + "'")

Write-Host "=== 4. write the token store ==="
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="refresh_token">$($reg.refreshToken)</string>
    <string name="device_id">$DeviceId</string>
    <string name="auth_token">$($reg.authToken)</string>
    <long name="token_expiry" value="$expiryMs" />
</map>
"@
$b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($xml))
$path = "shared_prefs/childwatch_tokens.xml"
$push = Q @('-s', $Serial, 'shell', "run-as $pkg sh -c 'echo $b64 | base64 -d > $path'")
Start-Sleep -Milliseconds 500
$check = Q @('-s', $Serial, 'shell', "run-as $pkg cat $path")
Write-Host ("  auth_token now: " + ([regex]::Match($check, 'name="auth_token">([^<]*)<')).Groups[1].Value.Substring(0, 16) + "...")
Write-Host ("  expiry now:     " + ([regex]::Match($check, 'name="token_expiry" value="([^"]*)"')).Groups[1].Value)
if ($push) { Write-Host ("  push output: " + $push) }

Write-Host "=== 5. restart the app ==="
Q @('-s', $Serial, 'shell', 'monkey', '-p', $pkg, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
Start-Sleep -Seconds 12
Write-Host ("  focus: " + ((Q @('-s', $Serial, 'shell', 'dumpsys', 'window')) -split "`n" | Where-Object { $_ -match 'mCurrentFocus' } | Select-Object -First 1))
