[CmdletBinding()]
param(
    [ValidateSet("status", "devices", "studio", "build", "deploy", "watch", "cleanup", "setup", "start", "test")]
    [string]$Action = "status",

    [ValidateSet("app", "parentwatch", "both")]
    [string]$Target = "both",

    [string]$AppSerial,
    [string]$ParentwatchSerial,
    [switch]$PreferSeparateDevices,
    [switch]$SkipInitialDeploy,
    [int]$DebounceMs = 1200
)

$ErrorActionPreference = "Stop"
$script:ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:LocalPropertiesPath = Join-Path $script:ProjectRoot "local.properties"
$script:RootWatchFiles = @("build.gradle", "settings.gradle", "gradle.properties")
$script:IgnorePathFragments = @(
    "\.git\",
    "\.gradle\",
    "\.idea\",
    "\.android\",
    "\.android-user\",
    "\build\",
    "\out\"
)

$script:Targets = @{
    app = [pscustomobject]@{
        Key = "app"
        Module = "app"
        DisplayName = "ParentMonitor"
        PackageName = "ru.example.childwatch"
        SourceRoot = Join-Path $script:ProjectRoot "app\src"
        ModuleRoot = Join-Path $script:ProjectRoot "app"
        ModuleWatchFiles = @("build.gradle", "proguard-rules.pro")
    }
    parentwatch = [pscustomobject]@{
        Key = "parentwatch"
        Module = "parentwatch"
        DisplayName = "ChildDevice"
        PackageName = "ru.example.parentwatch.debug"
        SourceRoot = Join-Path $script:ProjectRoot "parentwatch\src"
        ModuleRoot = Join-Path $script:ProjectRoot "parentwatch"
        ModuleWatchFiles = @("build.gradle", "proguard-rules.pro")
    }
}

function Write-Info {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Green
}

function Write-WarnLine {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Yellow
}

function Write-ErrLine {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Red
}

function Get-SdkDir {
    if (Test-Path $script:LocalPropertiesPath) {
        $line = Get-Content $script:LocalPropertiesPath | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
        if ($line) {
            return ($line -replace "^sdk.dir=", "").Replace("\\", "\")
        }
    }

    if ($env:ANDROID_HOME) {
        return $env:ANDROID_HOME
    }

    if ($env:ANDROID_SDK_ROOT) {
        return $env:ANDROID_SDK_ROOT
    }

    return $null
}

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CommandName,

        [string[]]$FallbackPaths = @()
    )

    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    foreach ($path in $FallbackPaths) {
        if ($path -and (Test-Path $path)) {
            return $path
        }
    }

    return $null
}

function Get-AdbPath {
    $sdkDir = Get-SdkDir
    $fallbacks = @()

    if ($sdkDir) {
        $fallbacks += (Join-Path $sdkDir "platform-tools\adb.exe")
    }

    return Resolve-ToolPath -CommandName "adb" -FallbackPaths $fallbacks
}

function Get-EmulatorPath {
    $sdkDir = Get-SdkDir
    if (-not $sdkDir) {
        return $null
    }

    $emulatorPath = Join-Path $sdkDir "emulator\emulator.exe"
    if (Test-Path $emulatorPath) {
        return $emulatorPath
    }

    return $null
}

function Get-AndroidStudioPath {
    $candidates = @(
        "C:\Program Files\Android\Android Studio\bin\studio64.exe",
        "C:\Users\$env:USERNAME\AppData\Local\Programs\Android Studio\bin\studio64.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    return $null
}

function Get-ConnectedDevices {
    $adbPath = Get-AdbPath
    if (-not $adbPath) {
        throw "adb was not found. Install Android platform-tools or Android Studio."
    }

    $lines = & $adbPath devices -l
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to run adb devices."
    }

    $devices = @()
    foreach ($line in $lines) {
        if ($line -match "^(?<serial>\S+)\s+device\b") {
            $serial = $matches.serial
            $isEmulator = $serial -like "emulator-*"
            $model = ""
            if ($line -match "model:(?<model>\S+)") {
                $model = $matches.model
            }

            $devices += [pscustomobject]@{
                Serial = $serial
                IsEmulator = $isEmulator
                Model = $model
            }
        }
    }

    return $devices
}

function Get-TargetKeys {
    param([string]$SelectedTarget)

    switch ($SelectedTarget) {
        "app" { return @("app") }
        "parentwatch" { return @("parentwatch") }
        default { return @("app", "parentwatch") }
    }
}

function Resolve-DeviceForTarget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetKey,

        [Parameter(Mandatory = $true)]
        [object[]]$Devices,

        [string]$ExplicitSerial,
        [string]$AlreadyAssignedSerial
    )

    if ($ExplicitSerial) {
        $explicit = $Devices | Where-Object { $_.Serial -eq $ExplicitSerial } | Select-Object -First 1
        if (-not $explicit) {
            throw "Requested device '$ExplicitSerial' for target '$TargetKey' is not connected."
        }

        return $explicit
    }

    $emulators = @($Devices | Where-Object { $_.IsEmulator })
    $physical = @($Devices | Where-Object { -not $_.IsEmulator })

    if (-not $PreferSeparateDevices) {
        if ($emulators.Count -gt 0) {
            return $emulators[0]
        }

        if ($Devices.Count -gt 0) {
            return $Devices[0]
        }
    }

    $preferred = @($emulators + $physical)
    if ($AlreadyAssignedSerial) {
        $otherDevice = $preferred | Where-Object { $_.Serial -ne $AlreadyAssignedSerial } | Select-Object -First 1
        if ($otherDevice) {
            return $otherDevice
        }
    }

    if ($preferred.Count -gt 0) {
        return $preferred[0]
    }

    throw "No connected devices were found."
}

function Resolve-DeploymentPlan {
    param([string[]]$TargetKeys)

    $devices = Get-ConnectedDevices
    if ($devices.Count -eq 0) {
        throw "No running device or emulator found. Start an emulator in Android Studio Device Manager first."
    }

    $plan = @{}
    $appDevice = $null

    foreach ($targetKey in $TargetKeys) {
        $explicitSerial = switch ($targetKey) {
            "app" { $AppSerial }
            "parentwatch" { $ParentwatchSerial }
            default { $null }
        }

        $assignedDevice = Resolve-DeviceForTarget `
            -TargetKey $targetKey `
            -Devices $devices `
            -ExplicitSerial $explicitSerial `
            -AlreadyAssignedSerial $appDevice

        $plan[$targetKey] = $assignedDevice

        if ($targetKey -eq "app") {
            $appDevice = $assignedDevice.Serial
        }
    }

    return $plan
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $gradlePath = Join-Path $script:ProjectRoot "gradlew.bat"
    if (-not (Test-Path $gradlePath)) {
        throw "gradlew.bat was not found in the project root."
    }

    Push-Location $script:ProjectRoot
    try {
        & $gradlePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

function Build-Target {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetKey
    )

    $target = $script:Targets[$TargetKey]
    Write-Info "Building $($target.DisplayName) ($($target.Module))..."
    Invoke-Gradle -Arguments @(":$($target.Module):assembleDebug", "--console=plain")
}

function Get-LatestApk {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetKey
    )

    $target = $script:Targets[$TargetKey]
    $apkDir = Join-Path $script:ProjectRoot "$($target.Module)\build\outputs\apk\debug"
    if (-not (Test-Path $apkDir)) {
        throw "APK directory was not found: $apkDir"
    }

    $apk = Get-ChildItem $apkDir -Recurse -Filter *.apk |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if (-not $apk) {
        throw "No debug APK was produced for target '$TargetKey'."
    }

    return $apk.FullName
}

function Install-Apk {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$ApkPath
    )

    $adbPath = Get-AdbPath
    Write-Info "Installing $(Split-Path $ApkPath -Leaf) on $Serial..."
    & $adbPath -s $Serial install -r $ApkPath
    if ($LASTEXITCODE -ne 0) {
        throw "adb install failed for $ApkPath on $Serial."
    }
}

function Resolve-LauncherComponent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    $adbPath = Get-AdbPath
    $output = & $adbPath -s $Serial shell cmd package resolve-activity --brief $PackageName 2>$null
    foreach ($line in $output) {
        $trimmed = $line.Trim()
        if ($trimmed -like "*/*") {
            return $trimmed
        }
    }

    return $null
}

function Launch-Target {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetKey,

        [Parameter(Mandatory = $true)]
        [string]$Serial
    )

    $adbPath = Get-AdbPath
    $target = $script:Targets[$TargetKey]

    & $adbPath -s $Serial shell am force-stop $target.PackageName 2>$null | Out-Null
    Start-Sleep -Milliseconds 400

    $monkeyOutput = & $adbPath -s $Serial shell monkey -p $target.PackageName -c android.intent.category.LAUNCHER 1 2>&1
    if ($LASTEXITCODE -eq 0 -and -not ($monkeyOutput -join "`n" -match "No activities found")) {
        return
    }

    $component = Resolve-LauncherComponent -Serial $Serial -PackageName $target.PackageName
    if ($component) {
        & $adbPath -s $Serial shell am start -n $component | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return
        }
    }

    throw "Unable to launch package $($target.PackageName) on $Serial."
}

function Deploy-Target {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetKey,

        [Parameter(Mandatory = $true)]
        [string]$Serial
    )

    $target = $script:Targets[$TargetKey]
    Build-Target -TargetKey $TargetKey
    $apkPath = Get-LatestApk -TargetKey $TargetKey
    Install-Apk -Serial $Serial -ApkPath $apkPath
    Launch-Target -TargetKey $TargetKey -Serial $Serial
    Write-Ok "Ready: $($target.DisplayName) on $Serial"
}

function Show-ConnectedDevices {
    $devices = Get-ConnectedDevices
    if ($devices.Count -eq 0) {
        Write-WarnLine "No devices detected. Start an emulator from Android Studio Device Manager."
        return
    }

    Write-Host ""
    Write-Info "Connected devices:"
    foreach ($device in $devices) {
        $kind = if ($device.IsEmulator) { "emulator" } else { "physical" }
        $model = if ($device.Model) { $device.Model } else { "unknown-model" }
        Write-Host "  $($device.Serial)  [$kind]  $model"
    }
}

function Show-Status {
    param([string[]]$TargetKeys)

    $adbPath = Get-AdbPath
    $studioPath = Get-AndroidStudioPath
    $emulatorPath = Get-EmulatorPath

    Write-Host ""
    Write-Info "Environment:"
    Write-Host "  project:   $script:ProjectRoot"
    Write-Host "  adb:       $(if ($adbPath) { $adbPath } else { 'missing' })"
    Write-Host "  studio:    $(if ($studioPath) { $studioPath } else { 'missing' })"
    Write-Host "  emulator:  $(if ($emulatorPath) { $emulatorPath } else { 'missing' })"

    Show-ConnectedDevices

    try {
        $plan = Resolve-DeploymentPlan -TargetKeys $TargetKeys
        Write-Host ""
        Write-Info "Current preview mapping:"
        foreach ($targetKey in $TargetKeys) {
            $target = $script:Targets[$targetKey]
            $serial = $plan[$targetKey].Serial
            Write-Host "  $($target.Module) -> $serial"
        }
    }
    catch {
        Write-Host ""
        Write-WarnLine $_.Exception.Message
    }

    Write-Host ""
    Write-Host "Recommended Android Studio workflow:"
    Write-Host "  1. Open Device Manager and start an emulator."
    Write-Host "  2. Run the needed module once."
    Write-Host "  3. Use Apply Changes for small UI/code updates."
    Write-Host "  4. Use this script or VS Code tasks for rebuild+install when needed."
}

function Open-AndroidStudio {
    $studioPath = Get-AndroidStudioPath
    if (-not $studioPath) {
        throw "Android Studio was not found on this machine."
    }

    Write-Info "Opening Android Studio..."
    Start-Process -FilePath $studioPath -ArgumentList "`"$script:ProjectRoot`""
}

function Should-IgnorePath {
    param([string]$Path)

    foreach ($fragment in $script:IgnorePathFragments) {
        if ($Path.IndexOf($fragment, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }

    return $false
}

function Get-TargetsForChangedPath {
    param([string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (Should-IgnorePath -Path $fullPath) {
        return @()
    }

    $hits = New-Object System.Collections.Generic.List[string]

    foreach ($rootFile in $script:RootWatchFiles) {
        $rootFilePath = Join-Path $script:ProjectRoot $rootFile
        if ($fullPath.Equals($rootFilePath, [System.StringComparison]::OrdinalIgnoreCase)) {
            $hits.Add("app")
            $hits.Add("parentwatch")
            return $hits | Select-Object -Unique
        }
    }

    foreach ($targetKey in $script:Targets.Keys) {
        $sourceRoot = $script:Targets[$targetKey].SourceRoot
        if ($fullPath.StartsWith($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            $hits.Add($targetKey)
            continue
        }

        foreach ($moduleFile in $script:Targets[$targetKey].ModuleWatchFiles) {
            $moduleFilePath = Join-Path $script:Targets[$targetKey].ModuleRoot $moduleFile
            if ($fullPath.Equals($moduleFilePath, [System.StringComparison]::OrdinalIgnoreCase)) {
                $hits.Add($targetKey)
                break
            }
        }
    }

    return $hits | Select-Object -Unique
}

function Register-Watchers {
    $watchers = New-Object System.Collections.Generic.List[object]

    foreach ($target in $script:Targets.Values) {
        $watcher = New-Object System.IO.FileSystemWatcher
        $watcher.Path = $target.SourceRoot
        $watcher.IncludeSubdirectories = $true
        $watcher.NotifyFilter = [System.IO.NotifyFilters]"FileName, DirectoryName, LastWrite"
        $watcher.EnableRaisingEvents = $true

        foreach ($eventName in @("Changed", "Created", "Deleted", "Renamed")) {
            Register-ObjectEvent -InputObject $watcher -EventName $eventName -SourceIdentifier "cw.$($target.Key).$eventName" -Action {
                $path = $Event.SourceEventArgs.FullPath
                New-Event -SourceIdentifier "cw.source" -MessageData $path | Out-Null
            } | Out-Null
        }

        $watchers.Add($watcher) | Out-Null

        foreach ($moduleFile in $target.ModuleWatchFiles) {
            $fileWatcher = New-Object System.IO.FileSystemWatcher
            $fileWatcher.Path = $target.ModuleRoot
            $fileWatcher.Filter = $moduleFile
            $fileWatcher.IncludeSubdirectories = $false
            $fileWatcher.NotifyFilter = [System.IO.NotifyFilters]"FileName, LastWrite"
            $fileWatcher.EnableRaisingEvents = $true

            foreach ($eventName in @("Changed", "Created", "Deleted", "Renamed")) {
                Register-ObjectEvent -InputObject $fileWatcher -EventName $eventName -SourceIdentifier "cw.$($target.Key).$moduleFile.$eventName" -Action {
                    $path = $Event.SourceEventArgs.FullPath
                    New-Event -SourceIdentifier "cw.source" -MessageData $path | Out-Null
                } | Out-Null
            }

            $watchers.Add($fileWatcher) | Out-Null
        }
    }

    foreach ($rootFile in $script:RootWatchFiles) {
        $rootWatcher = New-Object System.IO.FileSystemWatcher
        $rootWatcher.Path = $script:ProjectRoot
        $rootWatcher.Filter = $rootFile
        $rootWatcher.IncludeSubdirectories = $false
        $rootWatcher.NotifyFilter = [System.IO.NotifyFilters]"FileName, LastWrite"
        $rootWatcher.EnableRaisingEvents = $true

        foreach ($eventName in @("Changed", "Created", "Deleted", "Renamed")) {
            Register-ObjectEvent -InputObject $rootWatcher -EventName $eventName -SourceIdentifier "cw.root.$rootFile.$eventName" -Action {
                $path = $Event.SourceEventArgs.FullPath
                New-Event -SourceIdentifier "cw.source" -MessageData $path | Out-Null
            } | Out-Null
        }

        $watchers.Add($rootWatcher) | Out-Null
    }

    return $watchers
}

function Unregister-Watchers {
    Get-EventSubscriber | Where-Object { $_.SourceIdentifier -like "cw.*" } | Unregister-Event
    Get-Event | Where-Object { $_.SourceIdentifier -like "cw.*" } | Remove-Event
}

function Watch-Targets {
    param([string[]]$TargetKeys)

    $plan = Resolve-DeploymentPlan -TargetKeys $TargetKeys

    if (-not $SkipInitialDeploy) {
        foreach ($targetKey in $TargetKeys) {
            Deploy-Target -TargetKey $targetKey -Serial $plan[$targetKey].Serial
        }
    }

    Write-Host ""
    Write-Info "Watching for file changes. Press Ctrl+C to stop."

    $pending = @{}
    $watchers = Register-Watchers

    try {
        while ($true) {
            $events = @(Get-Event -SourceIdentifier "cw.source" -ErrorAction SilentlyContinue)
            foreach ($event in $events) {
                $changedPath = [string]$event.MessageData
                Remove-Event -EventIdentifier $event.EventIdentifier

                $changedTargets = Get-TargetsForChangedPath -Path $changedPath
                foreach ($targetKey in $changedTargets) {
                    if ($TargetKeys -contains $targetKey) {
                        $pending[$targetKey] = Get-Date
                    }
                }

                if ($changedTargets.Count -gt 0) {
                    $relativePath = Resolve-Path -LiteralPath $changedPath -ErrorAction SilentlyContinue
                    $shownPath = if ($relativePath) { $relativePath.Path.Replace($script:ProjectRoot + "\", "") } else { $changedPath }
                    Write-Info "Change detected: $shownPath"
                }
            }

            $now = Get-Date
            $readyTargets = @(
                $pending.GetEnumerator() |
                Where-Object { ($now - $_.Value).TotalMilliseconds -ge $DebounceMs } |
                ForEach-Object { $_.Key }
            )

            foreach ($targetKey in $readyTargets) {
                Write-Host ""
                Deploy-Target -TargetKey $targetKey -Serial $plan[$targetKey].Serial
                $pending.Remove($targetKey)
            }

            Start-Sleep -Milliseconds 350
        }
    }
    finally {
        foreach ($watcher in $watchers) {
            $watcher.EnableRaisingEvents = $false
            $watcher.Dispose()
        }

        Unregister-Watchers
    }
}

function Invoke-BuildOnly {
    param([string[]]$TargetKeys)

    foreach ($targetKey in $TargetKeys) {
        Build-Target -TargetKey $targetKey
        Write-Ok "Built: $targetKey"
    }
}

function Invoke-Deploy {
    param([string[]]$TargetKeys)

    $plan = Resolve-DeploymentPlan -TargetKeys $TargetKeys

    Write-Host ""
    Write-Info "Deployment plan:"
    foreach ($targetKey in $TargetKeys) {
        $target = $script:Targets[$targetKey]
        Write-Host "  $($target.Module) -> $($plan[$targetKey].Serial)"
    }

    Write-Host ""
    foreach ($targetKey in $TargetKeys) {
        Deploy-Target -TargetKey $targetKey -Serial $plan[$targetKey].Serial
    }
}

function Invoke-Cleanup {
    $scrcpy = Get-Command scrcpy -ErrorAction SilentlyContinue
    if ($scrcpy) {
        Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force
        Write-Ok "Closed running scrcpy windows."
    }
    else {
        Write-Info "Nothing to clean up."
    }
}

$normalizedAction = switch ($Action) {
    "setup" { "status" }
    "start" { "status" }
    "test" { "deploy" }
    default { $Action }
}

$selectedTargets = Get-TargetKeys -SelectedTarget $Target

switch ($normalizedAction) {
    "status" {
        Show-Status -TargetKeys $selectedTargets
    }
    "devices" {
        Show-ConnectedDevices
    }
    "studio" {
        Open-AndroidStudio
    }
    "build" {
        Invoke-BuildOnly -TargetKeys $selectedTargets
    }
    "deploy" {
        Invoke-Deploy -TargetKeys $selectedTargets
    }
    "watch" {
        Watch-Targets -TargetKeys $selectedTargets
    }
    "cleanup" {
        Invoke-Cleanup
    }
}
