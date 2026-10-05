param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Package = 'ru.example.parentwatch.debug'
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$adb = (Get-Command adb -ErrorAction Stop).Source

function Read-Device([string[]]$Arguments) {
    $result = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Device inspection failed: $($Arguments -join ' ')" }
    return ($result -join "`n")
}

# Read-only inspection: never provisions an owner, grants a permission, starts
# capture, launches an activity, removes an account, or resets a device.
$state = Read-Device @('get-state')
if ($state.Trim() -ne 'device') { throw 'The selected device is not ready.' }
$policy = Read-Device @('shell', 'dumpsys', 'device_policy')
$services = Read-Device @('shell', 'dumpsys', 'activity', 'services', $Package)
$packageInfo = Read-Device @('shell', 'dumpsys', 'package', $Package)
if ($packageInfo -notmatch 'versionCode=') { throw 'The selected application is not installed.' }
$sdk = (Read-Device @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
$release = (Read-Device @('shell', 'getprop', 'ro.build.version.release')).Trim()

$ownerMatch = [regex]::Match($policy, '(?s)Device Owner.*?admin=ComponentInfo\{([^/\s]+)')
$deviceOwnerPackage = if ($ownerMatch.Success) { $ownerMatch.Groups[1].Value } else { $null }
$profileMatch = [regex]::Match($policy, '(?s)Profile Owner.*?admin=ComponentInfo\{([^/\s]+)')
$profileOwnerPackage = if ($profileMatch.Success) { $profileMatch.Groups[1].Value } else { $null }
$isDeviceOwner = $deviceOwnerPackage -eq $Package
$recordPermission = [regex]::Match($packageInfo, 'android.permission.RECORD_AUDIO:\s*granted=(true|false)')
$cameraPermission = [regex]::Match($packageInfo, 'android.permission.CAMERA:\s*granted=(true|false)')
$version = [regex]::Match($packageInfo, 'versionName=([^\s]+)').Groups[1].Value

$report = [ordered]@{
    capturedAt = (Get-Date).ToString('o')
    serial = $Serial
    package = $Package
    version = $version
    androidRelease = $release
    sdk = [int]$sdk
    deviceOwnerPackage = $deviceOwnerPackage
    profileOwnerPackage = $profileOwnerPackage
    childWatchIsDeviceOwner = $isDeviceOwner
    microphonePermission = if ($recordPermission.Success) { $recordPermission.Groups[1].Value -eq 'true' } else { $null }
    cameraPermission = if ($cameraPermission.Success) { $cameraPermission.Groups[1].Value -eq 'true' } else { $null }
    restrictedRunningServiceCount = [regex]::Matches($services, 'allowWhileInUsePermissionInFgs=false').Count
    interpretation = if ($isDeviceOwner) {
        'Device-owner exemption candidate. Reboot, microphone and camera tests are still required; this is not a runtime verdict.'
    } elseif ($deviceOwnerPackage -or $profileOwnerPackage) {
        'Another controller is present. Do not provision or replace it automatically. Ordinary runtime permission does not establish boot microphone/camera eligibility.'
    } else {
        'No device-owner exemption detected for ChildWatch. Provisioning is a separate, explicitly approved device setup operation.'
    }
    allFeaturesAfterRebootVerified = $false
}
$runtime = Join-Path $workspace '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$path = Join-Path $runtime ('boot-capture-readiness-' + $Serial + '.json')
if ($Serial -notmatch '^[a-zA-Z0-9._:-]+$') { throw 'Invalid serial for report filename.' }
$report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $path -Encoding UTF8
$report | ConvertTo-Json -Depth 4
Write-Host "Report saved: $path"
