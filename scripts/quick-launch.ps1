# ⚡ Быстрый запуск обоих приложений

Write-Host "`n🚀 Запускаем ChildWatch и ParentWatch...`n" -ForegroundColor Yellow

# ChildWatch (РОДИТЕЛЬСКОЕ) на Nokia G21
Write-Host "📱 Запуск ChildWatch (родительское) на Nokia G21..." -ForegroundColor Cyan
adb -s PT19655KA1280800674 shell am force-stop ru.example.childwatch 2>$null
Start-Sleep -Milliseconds 500
adb -s PT19655KA1280800674 shell monkey -p ru.example.childwatch -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Write-Host "✅ ChildWatch запущен на Nokia" -ForegroundColor Green

# ParentWatch/ChildDevice (ДЕТСКОЕ) на эмуляторе
Write-Host "`n📱 Запуск ParentWatch (детское) на эмуляторе..." -ForegroundColor Cyan
adb -s emulator-5554 shell am force-stop ru.example.parentwatch.debug 2>$null
Start-Sleep -Milliseconds 500
adb -s emulator-5554 shell am start -n ru.example.parentwatch.debug/ru.example.parentwatch.MainActivity 2>&1 | Out-Null
Write-Host "✅ ParentWatch запущен на эмуляторе" -ForegroundColor Green

Write-Host "`n✅ ГОТОВО! Оба приложения запущены!`n" -ForegroundColor Yellow
Write-Host "👀 Смотрите в окна scrcpy:" -ForegroundColor White
Write-Host "   - Nokia G21: ChildWatch (родительское)" -ForegroundColor Cyan
Write-Host "   - Pixel 8: ParentWatch/ChildDevice (детское)" -ForegroundColor Cyan
