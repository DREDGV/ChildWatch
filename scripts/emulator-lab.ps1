[CmdletBinding()]
param(
    [ValidateSet("start", "pair", "status", "stop")]
    [string]$Action = "start",
    [string]$ParentSerial = "emulator-5556",
    [string]$ChildSerial = "emulator-5558"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$serverRoot = Join-Path $projectRoot "server"
$labRoot = Join-Path $serverRoot ".emulator-lab"
$serverUrlForEmulators = "http://10.0.2.2:3000"
$healthUrl = "http://127.0.0.1:3000/api/health"
$parentPackage = "ru.example.childwatch"
$childPackage = "ru.example.parentwatch.debug"

function Get-AdbPath {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $localProperties = Join-Path $projectRoot "local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties |
            Where-Object { $_ -like "sdk.dir=*" } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdk = ($sdkLine -replace "^sdk.dir=", "").Replace("\\", "\")
            $candidate = Join-Path $sdk "platform-tools\adb.exe"
            if (Test-Path $candidate) {
                return $candidate
            }
        }
    }
    throw "adb was not found. Open Android Studio and check local.properties."
}

function Assert-Device {
    param([string]$Adb, [string]$Serial)
    $state = (& $Adb -s $Serial get-state 2>$null | Out-String).Trim()
    if ($state -ne "device") {
        throw "Emulator $Serial is not running."
    }
}

function Assert-PackageInstalled {
    param([string]$Adb, [string]$Serial, [string]$Package)
    $path = (& $Adb -s $Serial shell pm path $Package 2>$null | Out-String).Trim()
    if (-not $path.StartsWith("package:")) {
        throw "Package $Package is not installed on $Serial. Install the debug APK first."
    }
}

function Invoke-AdbShellQuiet {
    param(
        [string]$Adb,
        [string]$Serial,
        [string[]]$ShellArgs
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Some Android releases do not expose every optional app-op. Such a
        # difference must not abort preparation of all other permissions.
        $ErrorActionPreference = "Continue"
        $result = & $Adb -s $Serial shell @ShellArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        Write-Verbose "adb $Serial shell $($ShellArgs -join ' ') skipped: $($result -join ' ')"
        return $false
    }
    return $true
}

function Grant-LabPermissions {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Package,
        [switch]$UsageAccess
    )

    Assert-PackageInstalled -Adb $Adb -Serial $Serial -Package $Package
    $packageDump = (& $Adb -s $Serial shell dumpsys package $Package 2>$null | Out-String)
    $runtimePermissions = @(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CONTACTS"
    )

    foreach ($permission in $runtimePermissions) {
        if ($packageDump.Contains($permission)) {
            Invoke-AdbShellQuiet -Adb $Adb -Serial $Serial `
                -ShellArgs @("pm", "grant", $Package, $permission) | Out-Null
        }
    }

    # Foreground location must be granted before background location.
    if ($packageDump.Contains("android.permission.ACCESS_BACKGROUND_LOCATION")) {
        Invoke-AdbShellQuiet -Adb $Adb -Serial $Serial `
            -ShellArgs @("pm", "grant", $Package, "android.permission.ACCESS_BACKGROUND_LOCATION") |
            Out-Null
    }

    # These changes are limited to the explicitly named Android emulators.
    Invoke-AdbShellQuiet -Adb $Adb -Serial $Serial `
        -ShellArgs @("dumpsys", "deviceidle", "whitelist", "+$Package") | Out-Null
    Invoke-AdbShellQuiet -Adb $Adb -Serial $Serial `
        -ShellArgs @("cmd", "appops", "set", $Package, "RUN_IN_BACKGROUND", "allow") | Out-Null
    Invoke-AdbShellQuiet -Adb $Adb -Serial $Serial `
        -ShellArgs @("cmd", "appops", "set", $Package, "RUN_ANY_IN_BACKGROUND", "allow") | Out-Null

    if ($UsageAccess) {
        Invoke-AdbShellQuiet -Adb $Adb -Serial $Serial `
            -ShellArgs @("cmd", "appops", "set", $Package, "GET_USAGE_STATS", "allow") | Out-Null
    }

    Write-Host "Permissions prepared: $Package on $Serial" -ForegroundColor Green
}

function Test-LocalServer {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $healthUrl -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Start-LocalServer {
    if (Test-LocalServer) {
        Write-Host "Local server is already running." -ForegroundColor Green
        return
    }

    [System.IO.Directory]::CreateDirectory($labRoot) | Out-Null
    $node = (Get-Command node -ErrorAction Stop).Source
    $oldPort = $env:PORT
    $oldDbPath = $env:CW_DB_PATH
    $oldSessionPath = $env:CW_AUTH_SESSION_PATH
    $oldNodeEnv = $env:NODE_ENV
    try {
        $env:PORT = "3000"
        $env:CW_DB_PATH = Join-Path $labRoot "childwatch.db"
        $env:CW_AUTH_SESSION_PATH = Join-Path $labRoot "auth-sessions.json"
        $env:NODE_ENV = "development"
        $process = Start-Process `
            -FilePath $node `
            -ArgumentList "index.js" `
            -WorkingDirectory $serverRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $labRoot "server.out.log") `
            -RedirectStandardError (Join-Path $labRoot "server.err.log") `
            -PassThru
        Set-Content -LiteralPath (Join-Path $labRoot "server.pid") -Value $process.Id
    } finally {
        $env:PORT = $oldPort
        $env:CW_DB_PATH = $oldDbPath
        $env:CW_AUTH_SESSION_PATH = $oldSessionPath
        $env:NODE_ENV = $oldNodeEnv
    }

    $deadline = (Get-Date).AddSeconds(25)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalServer) {
            Write-Host "Local server started." -ForegroundColor Green
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Local server did not start. See server\.emulator-lab\server.err.log"
}

function Configure-Emulators {
    param([string]$Adb)

    Assert-Device -Adb $Adb -Serial $ParentSerial
    Assert-Device -Adb $Adb -Serial $ChildSerial
    Grant-LabPermissions -Adb $Adb -Serial $ParentSerial -Package $parentPackage
    Grant-LabPermissions -Adb $Adb -Serial $ChildSerial -Package $childPackage -UsageAccess

    & $Adb -s $ParentSerial shell am broadcast `
        -a "ru.example.childwatch.DEBUG_EMULATOR_SETUP" `
        -p $parentPackage `
        --es server_url $serverUrlForEmulators `
        --ez grant_test_consents true | Out-Null
    & $Adb -s $ChildSerial shell am broadcast `
        -a "ru.example.parentwatch.DEBUG_EMULATOR_SETUP" `
        -p $childPackage `
        --es server_url $serverUrlForEmulators `
        --ez grant_test_consents true | Out-Null

    Start-Sleep -Seconds 3
    & $Adb -s $ParentSerial shell am force-stop $parentPackage | Out-Null
    & $Adb -s $ParentSerial shell monkey -p $parentPackage `
        -c android.intent.category.LAUNCHER 1 | Out-Null
    & $Adb -s $ChildSerial shell am force-stop $childPackage | Out-Null
    & $Adb -s $ChildSerial shell monkey -p $childPackage `
        -c android.intent.category.LAUNCHER 1 | Out-Null

    Write-Host "Both apps now use the local emulator test server." -ForegroundColor Green
}

function Open-InvitationOnChild {
    param([string]$Adb)

    Assert-Device -Adb $Adb -Serial $ParentSerial
    Assert-Device -Adb $Adb -Serial $ChildSerial

    $raw = & $Adb -s $ParentSerial exec-out run-as $parentPackage `
        cat "shared_prefs/childwatch_emulator_lab.xml" 2>$null
    if (-not $raw) {
        throw "Create an invitation in ParentMonitor first."
    }
    [xml]$xml = ($raw -join "`n")
    $entry = $xml.map.string |
        Where-Object { $_.name -eq "last_invitation_uri" } |
        Select-Object -First 1
    $invitationUri = [string]$entry.InnerText
    if ([string]::IsNullOrWhiteSpace($invitationUri)) {
        throw "No active invitation found. Create one in ParentMonitor."
    }

    & $Adb -s $ChildSerial shell am start `
        -n "$childPackage/ru.example.parentwatch.FamilyJoinActivity" `
        --es family_invitation $invitationUri | Out-Null
    Write-Host "Invitation opened on the child emulator. Check the name and confirm adding." `
        -ForegroundColor Green
}

function Show-Status {
    param([string]$Adb)
    $serverStatus = "stopped"
    if (Test-LocalServer) {
        $serverStatus = "running"
    }
    Write-Host "Server: $serverStatus"
    & $Adb devices -l
}

function Stop-LocalServer {
    $pidFile = Join-Path $labRoot "server.pid"
    if (-not (Test-Path $pidFile)) {
        Write-Host "Local server PID was not found."
        return
    }
    $serverPid = [int](Get-Content $pidFile | Select-Object -First 1)
    $process = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
    if ($process) {
        Stop-Process -Id $serverPid
        Write-Host "Local server stopped." -ForegroundColor Green
    }
    Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
}

$adb = Get-AdbPath
switch ($Action) {
    "start" {
        Start-LocalServer
        Configure-Emulators -Adb $adb
        Write-Host ""
        Write-Host "Next:" -ForegroundColor Cyan
        Write-Host "1. Create an invitation in ParentMonitor."
        Write-Host "2. Run: .\scripts\emulator-lab.ps1 -Action pair"
        Write-Host "3. Confirm adding on the child emulator."
    }
    "pair" {
        Open-InvitationOnChild -Adb $adb
    }
    "status" {
        Show-Status -Adb $adb
    }
    "stop" {
        Stop-LocalServer
    }
}
