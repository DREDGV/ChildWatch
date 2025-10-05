# Google Maps API Setup

## Получение API ключа

1. Перейдите на [Google Cloud Console](https://console.cloud.google.com/)
2. Создайте новый проект или выберите существующий
3. Откройте **APIs & Services** → **Credentials**
4. Нажмите **Create Credentials** → **API key**
5. Скопируйте созданный ключ
6. Ограничьте ключ (рекомендуется):
   - **Application restrictions**: Android apps
   - Добавьте SHA-1 отпечаток сертификата и package name: `ru.example.childwatch`
   - **API restrictions**: Maps SDK for Android

## Получение SHA-1 отпечатка (для debug)

В терминале выполните:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Для Windows:
```cmd
keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

## Вставка ключа в приложение

### Вариант 1: Через файл strings.xml (рекомендуется)

Откройте `app/src/main/res/values/strings.xml` и добавьте:

```xml
<string name="google_maps_key">YOUR_API_KEY_HERE</string>
```

### Вариант 2: Через google_maps_api.xml

Создайте файл `app/src/main/res/values/google_maps_api.xml`:

```xml
<resources>
    <string name="google_maps_key" templateMergeStrategy="preserve" translatable="false">YOUR_API_KEY_HERE</string>
</resources>
```

## Проверка

После вставки ключа:
1. Пересоберите приложение: `./gradlew assembleDebug`
2. Запустите приложение
3. Откройте Location Map - карта должна загрузиться

## Бесплатный лимит

Google Maps предоставляет **$200 бесплатных кредитов в месяц**, чего достаточно для:
- ~28,000 загрузок карт
- Для тестирования этого более чем достаточно

## Без API ключа

Если не хотите использовать Google Maps:
- Можете вводить координаты вручную
- Используйте веб-карты (открывает браузер с координатами)
- В будущем можно интегрировать 2GIS или Yandex Maps
