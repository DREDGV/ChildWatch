# 🎯 Скрипт для работы с двумя приложениями одновременно
# ChildWatch (детское) + ParentWatch (родительское)

param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("setup", "start", "build", "deploy", "test", "cleanup")]
    [string]$Action = "start",
    
    [switch]$UseEmulator,
    [switch]$DualMode
)

# Цвета для вывода
$colors = @{
    Success = "Green"
    Error   = "Red"
    Info    = "Cyan"
    Warning = "Yellow"
}

function Write-ColorOutput {
    param([string]$Message, [string]$Color = "White")
    Write-Host $Message -ForegroundColor $Color
}

# Конфигурация устройств
$realDevice = "PT19655KA1280800674"  # Nokia G21 - РОДИТЕЛЬ
$emulatorSerial = "emulator-5554"    # Pixel 8 - РЕБЕНОК
$emulatorAVD = "Pixel_8_API_35"

# Пути
$sdkPath = "C:\Users\dr-ed\AppData\Local\Android\Sdk"
$emulatorExe = "$sdkPath\emulator\emulator.exe"
$projectRoot = "C:\Users\dr-ed\ChildWatch"

function Test-DeviceConnected {
    param([string]$Serial)
    
    $devices = adb devices | Select-String -Pattern "$Serial\s+device$"
    return $null -ne $devices
}

function Start-Emulator {
    Write-ColorOutput "🚀 Запуск эмулятора $emulatorAVD..." $colors.Info
    
    Start-Process -FilePath $emulatorExe -ArgumentList "-avd $emulatorAVD" -WindowStyle Hidden
    
    Write-ColorOutput "⏳ Ожидание загрузки эмулятора (это может занять 30-60 секунд)..." $colors.Warning
    
    $timeout = 120
    $elapsed = 0
    
    while (-not (Test-DeviceConnected $emulatorSerial) -and $elapsed -lt $timeout) {
        Start-Sleep -Seconds 5
        $elapsed += 5
        Write-Host "." -NoNewline
    }
    
    Write-Host ""
    
    if (Test-DeviceConnected $emulatorSerial) {
        Write-ColorOutput "✅ Эмулятор запущен и подключен!" $colors.Success
        
        # Ждем полной загрузки
        Start-Sleep -Seconds 10
        
        # Оптимизация для разработки
        Write-ColorOutput "⚙️  Настройка эмулятора для разработки..." $colors.Info
        adb -s $emulatorSerial shell settings put global window_animation_scale 0 2>$null
        adb -s $emulatorSerial shell settings put global transition_animation_scale 0 2>$null
        adb -s $emulatorSerial shell settings put global animator_duration_scale 0 2>$null
        
        return $true
    }
    else {
        Write-ColorOutput "❌ Не удалось запустить эмулятор за $timeout секунд" $colors.Error
        return $false
    }
}

function Start-LivePreview {
    param([string]$DeviceSerial, [string]$AppName, [int]$WindowX = 0)
    
    Write-ColorOutput "🔴 Запуск live preview для $AppName на $DeviceSerial..." $colors.Info
    
    $args = @(
        "--serial", $DeviceSerial,
        "--max-size", "1024",
        "--video-bit-rate", "2M",
        "--window-title", $AppName,
        "--window-x", $WindowX,
        "--window-y", "0"
    )
    
    if ($AppName -like "*Child*") {
        $args += "--always-on-top"
    }
    
    Start-Process scrcpy -ArgumentList $args
}

function Build-Applications {
    Write-ColorOutput "🔨 Сборка приложений..." $colors.Info
    
    Push-Location $projectRoot
    
    try {
        # Сборка обоих приложений
        Write-ColorOutput "  📦 Сборка ChildWatch..." $colors.Info
        .\gradlew.bat :app:assembleDebug --quiet
        
        Write-ColorOutput "  📦 Сборка ParentWatch..." $colors.Info
        .\gradlew.bat :parentwatch:assembleDebug --quiet
        
        Write-ColorOutput "✅ Сборка завершена!" $colors.Success
        return $true
    }
    catch {
        Write-ColorOutput "❌ Ошибка сборки: $_" $colors.Error
        return $false
    }
    finally {
        Pop-Location
    }
}

function Install-App {
    param([string]$DeviceSerial, [string]$ApkPath, [string]$PackageName)
    
    Write-ColorOutput "📲 Установка на $DeviceSerial..." $colors.Info
    
    $apkFile = Get-ChildItem -Path $ApkPath -Filter "*.apk" | Select-Object -First 1
    
    if ($null -eq $apkFile) {
        Write-ColorOutput "❌ APK не найден: $ApkPath" $colors.Error
        return $false
    }
    
    adb -s $DeviceSerial install -r $apkFile.FullName 2>&1 | Out-Null
    
    if ($LASTEXITCODE -eq 0) {
        Write-ColorOutput "✅ Установлено: $($apkFile.Name)" $colors.Success
        
        # Определяем правильный package name для debug версии
        $debugPackage = if ($PackageName -eq "ru.example.parentwatch") { 
            "ru.example.parentwatch.debug" 
        }
        else { 
            $PackageName 
        }
        
        # Перезапуск приложения
        adb -s $DeviceSerial shell am force-stop $debugPackage 2>$null
        Start-Sleep -Milliseconds 500
        
        # Пробуем запустить через monkey, если не получается - через am start
        $monkeyOutput = adb -s $DeviceSerial shell monkey -p $debugPackage -c android.intent.category.LAUNCHER 1 2>&1
        if ($monkeyOutput -like "*No activities found*") {
            # Запускаем явно через MainActivity
            $activityName = if ($debugPackage -eq "ru.example.parentwatch.debug") {
                "$debugPackage/ru.example.parentwatch.MainActivity"
            }
            else {
                "$debugPackage/ru.example.childwatch.MainActivity"
            }
            adb -s $DeviceSerial shell am start -n $activityName 2>&1 | Out-Null
        }
        
        return $true
    }
    else {
        Write-ColorOutput "❌ Ошибка установки" $colors.Error
        return $false
    }
}

function Show-DeviceInfo {
    Write-ColorOutput "`n📱 Подключенные устройства:" $colors.Info
    
    $devices = adb devices -l | Select-String -Pattern "device\s+"
    
    if ($devices) {
        $devices | ForEach-Object {
            $line = $_ -split '\s+'
            $serial = $line[0]
            $model = if ($_ -match "model:(\S+)") { $matches[1] } else { "Unknown" }
            
            $icon = if ($serial -like "emulator-*") { "🖥️" } else { "📱" }
            Write-ColorOutput "  $icon $serial - $model" "White"
        }
    }
    else {
        Write-ColorOutput "  ⚠️  Нет подключенных устройств" $colors.Warning
    }
    Write-Host ""
}

# ============================================
# Основная логика
# ============================================

Write-ColorOutput "`n🎯 ChildWatch + ParentWatch Developer Tool`n" $colors.Success

switch ($Action) {
    "setup" {
        Write-ColorOutput "🔧 Настройка окружения для разработки двух приложений...`n" $colors.Info
        
        # Проверка реального устройства
        if (Test-DeviceConnected $realDevice) {
            Write-ColorOutput "✅ Nokia G21 подключен ($realDevice)" $colors.Success
        }
        else {
            Write-ColorOutput "⚠️  Nokia G21 не подключен - ParentWatch будет недоступен" $colors.Warning
        }
        
        # Проверка/запуск эмулятора
        if (-not (Test-DeviceConnected $emulatorSerial)) {
            Write-ColorOutput "⚠️  Эмулятор не запущен" $colors.Warning
            $response = Read-Host "Запустить эмулятор? (y/n)"
            if ($response -eq "y") {
                Start-Emulator
            }
        }
        else {
            Write-ColorOutput "✅ Эмулятор уже запущен ($emulatorSerial)" $colors.Success
        }
        
        Show-DeviceInfo
    }
    
    "start" {
        Write-ColorOutput "🚀 Запуск live preview...`n" $colors.Info
        
        # Проверяем устройства
        $realConnected = Test-DeviceConnected $realDevice
        $emulatorConnected = Test-DeviceConnected $emulatorSerial
        
        if (-not $emulatorConnected -and $UseEmulator) {
            Write-ColorOutput "Эмулятор не запущен. Запускаем..." $colors.Warning
            if (-not (Start-Emulator)) {
                exit 1
            }
            $emulatorConnected = $true
        }
        
        # Запуск scrcpy для устройств
        if ($DualMode) {
            if ($realConnected) {
                Start-LivePreview -DeviceSerial $realDevice -AppName "ParentWatch (Nokia)" -WindowX 0
                Start-Sleep -Seconds 2
            }
            if ($emulatorConnected) {
                Start-LivePreview -DeviceSerial $emulatorSerial -AppName "ChildWatch (Pixel 8)" -WindowX 550
            }
        }
        else {
            if ($realConnected) {
                Start-LivePreview -DeviceSerial $realDevice -AppName "ParentWatch (Nokia)" -WindowX 0
            }
            elseif ($emulatorConnected) {
                Start-LivePreview -DeviceSerial $emulatorSerial -AppName "ChildWatch (Pixel 8)" -WindowX 0
            }
        }
        
        Write-ColorOutput "`n✅ Live preview запущен!" $colors.Success
    }
    
    "build" {
        Build-Applications
    }
    
    "deploy" {
        Write-ColorOutput "🚀 Развертывание приложений...`n" $colors.Info
        
        # Сборка
        if (-not (Build-Applications)) {
            exit 1
        }
        
        Write-Host ""
        
        # Установка ChildWatch (РОДИТЕЛЬСКОЕ) на реальное устройство (Nokia)
        if (Test-DeviceConnected $realDevice) {
            Install-App -DeviceSerial $realDevice `
                -ApkPath "$projectRoot\app\build\outputs\apk\debug" `
                -PackageName "ru.example.childwatch"
        }
        else {
            Write-ColorOutput "⚠️  Nokia G21 не подключен - ChildWatch (родительское) пропущен" $colors.Warning
        }
        
        # Установка ParentWatch/ChildDevice (ДЕТСКОЕ) на эмулятор
        if (Test-DeviceConnected $emulatorSerial) {
            Install-App -DeviceSerial $emulatorSerial `
                -ApkPath "$projectRoot\parentwatch\build\outputs\apk\debug" `
                -PackageName "ru.example.parentwatch"
        }
        else {
            Write-ColorOutput "⚠️  Эмулятор не подключен - ChildWatch пропущен" $colors.Warning
        }
        
        Write-ColorOutput "`n✅ Развертывание завершено!" $colors.Success
    }
    
    "test" {
        Write-ColorOutput "🧪 Запуск полного тестового цикла...`n" $colors.Info
        
        # 1. Проверка устройств
        Show-DeviceInfo
        
        # 2. Сборка
        if (-not (Build-Applications)) {
            exit 1
        }
        
        # 3. Установка
        & $PSCommandPath -Action deploy
        
        # 4. Запуск live preview
        Start-Sleep -Seconds 3
        & $PSCommandPath -Action start -DualMode
    }
    
    "cleanup" {
        Write-ColorOutput "🧹 Очистка...`n" $colors.Info
        
        # Закрыть все scrcpy процессы
        Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force
        Write-ColorOutput "✅ scrcpy процессы остановлены" $colors.Success
        
        # Опционально: остановка эмулятора
        $response = Read-Host "Остановить эмулятор? (y/n)"
        if ($response -eq "y") {
            adb -s $emulatorSerial emu kill 2>$null
            Write-ColorOutput "✅ Эмулятор остановлен" $colors.Success
        }
    }
}

Write-ColorOutput "`n✨ Готово!`n" $colors.Success
