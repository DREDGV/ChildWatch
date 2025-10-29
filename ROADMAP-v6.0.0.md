# ChildWatch v6.0.0 - Roadmap улучшений

**Дата создания**: 29 октября 2025
**Текущая версия**: v5.7.4
**Целевая версия**: v6.0.0
**Статус**: В разработке

---

## Обзор

Этот roadmap описывает план улучшений ChildWatch для версии 6.0.0, включающий:
- Отображение локации родителя для ребенка
- Улучшенные маршруты с real-time трекингом
- Повышение точности геолокации
- Проверка и улучшение чата
- Завершение дистанционного фото

**Общая оценка времени**: 21-31 час

---

## Приоритет 1: Карта локации родителя для ребенка
**Версия**: v6.0.0
**Оценка времени**: 8-12 часов
**Статус**: ⏳ Ожидает начала

### Описание
Реализовать функцию, позволяющую ребенку видеть местоположение родителя на карте в реальном времени. Это позволит ребенку знать, когда родитель подъезжает к школе или другому месту встречи.

### Use Case
1. Родитель включает "Делиться моей локацией" в настройках
2. Родительское устройство начинает отправлять локацию каждые 60 секунд
3. На детском устройстве появляется кнопка "Где родители?"
4. При нажатии открывается карта с маркерами родителя и ребенка
5. Показывается расстояние между ними и ETA если родитель в движении

### Задачи

#### 1.1. Database Layer (2 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/database/entity/ParentLocation.kt` (новый)
- `app/src/main/java/ru/example/childwatch/database/dao/ParentLocationDao.kt` (новый)
- `app/src/main/java/ru/example/childwatch/database/ChildWatchDatabase.kt` (изменение)

**Шаги**:
1. Создать `ParentLocation.kt` entity:
   ```kotlin
   @Entity(tableName = "parent_locations")
   data class ParentLocation(
       @PrimaryKey(autoGenerate = true) val id: Long = 0,
       @ColumnInfo(name = "parent_id") val parentId: String,
       val latitude: Double,
       val longitude: Double,
       val accuracy: Float,
       val timestamp: Long,
       val provider: String,
       @ColumnInfo(name = "battery_level") val batteryLevel: Int?,
       val speed: Float?,
       val bearing: Float?
   )
   ```

2. Создать `ParentLocationDao.kt`:
   ```kotlin
   @Dao
   interface ParentLocationDao {
       @Insert(onConflict = OnConflictStrategy.REPLACE)
       suspend fun insertLocation(location: ParentLocation): Long

       @Query("SELECT * FROM parent_locations WHERE parent_id = :parentId ORDER BY timestamp DESC LIMIT 1")
       suspend fun getLatestLocation(parentId: String): ParentLocation?

       @Query("SELECT * FROM parent_locations WHERE parent_id = :parentId AND timestamp >= :startTime ORDER BY timestamp DESC")
       suspend fun getLocationHistory(parentId: String, startTime: Long): List<ParentLocation>

       @Query("DELETE FROM parent_locations WHERE timestamp < :cutoffTime")
       suspend fun deleteOldLocations(cutoffTime: Long): Int
   }
   ```

3. Добавить в `ChildWatchDatabase`:
   - Увеличить версию: `version = 2`
   - Добавить `ParentLocation::class` в entities
   - Создать migration v1→v2:
     ```kotlin
     val MIGRATION_1_2 = object : Migration(1, 2) {
         override fun migrate(database: SupportSQLiteDatabase) {
             database.execSQL("""
                 CREATE TABLE IF NOT EXISTS parent_locations (
                     id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                     parent_id TEXT NOT NULL,
                     latitude REAL NOT NULL,
                     longitude REAL NOT NULL,
                     accuracy REAL NOT NULL,
                     timestamp INTEGER NOT NULL,
                     provider TEXT NOT NULL,
                     battery_level INTEGER,
                     speed REAL,
                     bearing REAL
                 )
             """)
             database.execSQL("CREATE INDEX index_parent_locations_parent_id ON parent_locations(parent_id)")
             database.execSQL("CREATE INDEX index_parent_locations_timestamp ON parent_locations(timestamp)")
         }
     }
     ```
   - Добавить `.addMigrations(MIGRATION_1_2)` при сборке базы

**Acceptance Criteria**:
- [ ] Entity ParentLocation создан и корректно аннотирован
- [ ] DAO содержит все необходимые методы
- [ ] Миграция выполняется без ошибок
- [ ] Индексы созданы для производительности

---

#### 1.2. Location Tracking для родителя (2 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/location/ParentLocationTracker.kt` (новый)
- `app/src/main/java/ru/example/childwatch/service/MonitorService.kt` (изменение)
- `app/src/main/res/xml/preferences.xml` (изменение)

**Шаги**:
1. Создать `ParentLocationTracker.kt`:
   - Использовать FusedLocationProviderClient
   - Периодичность: каждые 60 секунд
   - Priority: PRIORITY_BALANCED_POWER_ACCURACY (экономия батареи)
   - Методы:
     - `startTracking()` - начать отслеживание
     - `stopTracking()` - остановить
     - `uploadLocationToServer()` - загрузить на сервер

2. Добавить настройку в `preferences.xml`:
   ```xml
   <SwitchPreferenceCompat
       app:key="share_parent_location"
       app:title="Делиться моей локацией"
       app:summary="Позволить ребенку видеть, где вы находитесь"
       app:defaultValue="false" />
   ```

3. Интегрировать в `MonitorService`:
   - Проверять настройку `share_parent_location`
   - Если включено - запустить `ParentLocationTracker`
   - При остановке мониторинга - остановить трекер

**Acceptance Criteria**:
- [ ] Трекер запускается/останавливается по настройке
- [ ] Локация обновляется каждые 60 секунд
- [ ] Не влияет значительно на расход батареи

---

#### 1.3. Server API Endpoints (1 час)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/network/ChildWatchApi.kt` (изменение)
- `app/src/main/java/ru/example/childwatch/network/NetworkClient.kt` (изменение)

**Шаги**:
1. Добавить в `ChildWatchApi.kt`:
   ```kotlin
   @POST("api/location/parent/{parentId}")
   suspend fun uploadParentLocation(
       @Path("parentId") parentId: String,
       @Body location: LocationData
   ): Response<BaseResponse>

   @GET("api/location/parent/latest/{parentId}")
   suspend fun getParentLocation(
       @Path("parentId") parentId: String
   ): Response<LocationResponse>
   ```

2. Добавить в `NetworkClient.kt`:
   ```kotlin
   suspend fun uploadParentLocation(
       parentId: String,
       latitude: Double,
       longitude: Double,
       accuracy: Float,
       timestamp: Long
   ): Boolean

   suspend fun getParentLocation(parentId: String): LocationData?
   ```

**Acceptance Criteria**:
- [ ] Методы корректно вызывают REST API
- [ ] Обработка ошибок реализована
- [ ] Логирование добавлено

---

#### 1.4. UI на детском устройстве (3-4 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/ParentLocationMapActivity.kt` (новый)
- `app/src/main/res/layout/activity_parent_location_map.xml` (новый)
- `app/src/main/java/ru/example/childwatch/MainActivity.kt` (изменение)

**Шаги**:
1. Создать `ParentLocationMapActivity`:
   - Показывать OSMdroid карту
   - Маркер ребенка (синий цвет)
   - Маркер родителя (зеленый цвет)
   - Линия между ними с указанием расстояния
   - Текст с расстоянием и ETA
   - Auto-refresh каждые 30 секунд

2. Рассчет ETA:
   ```kotlin
   fun calculateETA(
       parentLat: Double, parentLon: Double,
       childLat: Double, childLon: Double,
       parentSpeed: Float  // м/с
   ): String {
       val distance = calculateDistance(...)  // в метрах
       if (parentSpeed < 0.5) return "Родитель на месте"
       val timeSeconds = distance / parentSpeed
       return formatTime(timeSeconds)  // "5 минут", "23 минуты"
   }
   ```

3. Добавить кнопку в `MainActivity`:
   ```xml
   <com.google.android.material.card.MaterialCardView
       android:id="@+id/parentLocationCard"
       android:layout_width="match_parent"
       android:layout_height="wrap_content">
       <TextView
           android:text="Где родители?"
           android:drawableStart="@drawable/ic_parent_location" />
   </com.google.android.material.card.MaterialCardView>
   ```

**Acceptance Criteria**:
- [ ] Карта отображает оба маркера
- [ ] Расстояние корректно рассчитывается
- [ ] ETA показывается если родитель движется
- [ ] Авто-обновление работает
- [ ] UI адаптивный и понятный

---

#### 1.5. Настройки и Permissions (1 час)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/SettingsActivity.kt` (изменение)
- `app/src/main/AndroidManifest.xml` (проверка)

**Шаги**:
1. Проверить наличие background location permission
2. Добавить диалог объяснения при включении функции:
   ```kotlin
   if (shareLocationEnabled && !hasBackgroundLocationPermission()) {
       showExplanationDialog(
           "Для этой функции нужен доступ к локации в фоне. " +
           "Это позволит ребенку видеть ваше местоположение даже " +
           "когда приложение свернуто."
       )
       requestBackgroundLocationPermission()
   }
   ```

**Acceptance Criteria**:
- [ ] Permission запрашивается корректно
- [ ] Диалог объяснения показывается
- [ ] Функция не работает без permission

---

### Тестирование Приоритета 1
- [ ] Родитель включает sharing - локация отправляется
- [ ] Ребенок видит родителя на карте
- [ ] Расстояние корректное (сверить с Google Maps)
- [ ] ETA адекватный при движении
- [ ] Работает в фоне минимум 1 час
- [ ] Нет значительного расхода батареи (< 5% в час)

---

## Приоритет 2: Маршруты ребенка
**Версия**: v6.1.0
**Оценка времени**: 4-6 часов
**Статус**: ⏳ Ожидает завершения Приоритета 1

### Описание
Улучшить существующую функцию просмотра истории перемещений, добавить выбор конкретных дат, статистику, и режим real-time трекинга.

### Задачи

#### 2.1. Улучшение UI истории маршрутов (2 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/LocationMapActivity.kt` (изменение линий 396-583)
- `app/src/main/res/layout/activity_location_map_new.xml` (изменение)

**Шаги**:
1. Добавить DatePicker для выбора конкретной даты:
   ```kotlin
   private fun showDateRangePicker() {
       val picker = MaterialDatePicker.Builder.dateRangePicker()
           .setTitleText("Выберите период")
           .build()

       picker.addOnPositiveButtonClickListener { selection ->
           val startDate = selection.first
           val endDate = selection.second
           loadHistoryForDateRange(startDate, endDate)
       }
       picker.show(supportFragmentManager, "date_picker")
   }
   ```

2. Добавить панель статистики:
   ```kotlin
   data class RouteStats(
       val totalDistance: Float,      // в километрах
       val averageSpeed: Float,        // км/ч
       val maxSpeed: Float,            // км/ч
       val timeInMotion: Long,         // в секундах
       val timeStationary: Long,       // в секундах
       val startTime: Long,
       val endTime: Long
   )

   private fun calculateRouteStats(locations: List<LocationData>): RouteStats {
       // Расчет расстояния между точками
       // Определение движения (speed > 1 м/с)
       // Агрегация времени
   }
   ```

3. Обновить UI для показа статистики:
   ```xml
   <LinearLayout
       android:id="@+id/statsPanel"
       android:visibility="gone">
       <TextView android:id="@+id/totalDistanceText" />
       <TextView android:id="@+id/avgSpeedText" />
       <TextView android:id="@+id/timeInMotionText" />
   </LinearLayout>
   ```

**Acceptance Criteria**:
- [ ] DatePicker работает и выбирает диапазон
- [ ] Статистика рассчитывается корректно
- [ ] UI адаптивный и не перегруженный

---

#### 2.2. Real-time трекинг (2-3 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/LocationMapActivity.kt` (изменение)

**Шаги**:
1. Добавить toggle кнопку "Live Tracking":
   ```xml
   <com.google.android.material.floatingactionbutton.FloatingActionButton
       android:id="@+id/liveTrackingFab"
       app:icon="@drawable/ic_live_tracking"
       app:backgroundTint="@color/accent" />
   ```

2. Реализовать live tracking:
   ```kotlin
   private var liveTrackingJob: Job? = null
   private val liveTrackPolyline = Polyline()

   private fun startLiveTracking() {
       liveTrackPolyline.outlinePaint.color = Color.RED
       osmMapView?.overlays?.add(liveTrackPolyline)

       liveTrackingJob = serviceScope.launch {
           while (isActive) {
               val location = fetchLatestLocation()
               location?.let {
                   addPointToLiveTrack(it)
                   updateMapCenter(it)
                   updateSpeedIndicator(it.speed)
               }
               delay(15000)  // 15 секунд
           }
       }
   }

   private fun stopLiveTracking() {
       liveTrackingJob?.cancel()
       osmMapView?.overlays?.remove(liveTrackPolyline)
       liveTrackPolyline.actualPoints.clear()
   }
   ```

3. Добавить индикатор скорости:
   ```xml
   <TextView
       android:id="@+id/speedIndicator"
       android:text="Скорость: 5 км/ч"
       android:textSize="16sp" />
   ```

**Acceptance Criteria**:
- [ ] Live tracking включается/выключается кнопкой
- [ ] Трек рисуется в реальном времени
- [ ] Карта auto-центрируется на текущей позиции
- [ ] Скорость показывается корректно
- [ ] Нет утечек памяти (Job отменяется)

---

#### 2.3. Экспорт маршрутов (1 час - опционально)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/utils/GpxExporter.kt` (новый)

**Шаги**:
1. Реализовать экспорт в GPX:
   ```kotlin
   class GpxExporter {
       fun exportToGpx(locations: List<LocationData>, fileName: String): File {
           val gpxContent = buildString {
               appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
               appendLine("""<gpx version="1.1">""")
               appendLine("""  <trk><name>ChildWatch Route</name><trkseg>""")
               locations.forEach { loc ->
                   appendLine("""    <trkpt lat="${loc.latitude}" lon="${loc.longitude}">""")
                   appendLine("""      <time>${formatIso8601(loc.timestamp)}</time>""")
                   appendLine("""    </trkpt>""")
               }
               appendLine("""  </trkseg></trk>""")
               appendLine("""</gpx>""")
           }

           val file = File(context.getExternalFilesDir(null), fileName)
           file.writeText(gpxContent)
           return file
       }
   }
   ```

2. Добавить кнопку "Экспорт" в меню

**Acceptance Criteria**:
- [ ] GPX файл корректного формата
- [ ] Можно открыть в Google Maps/других приложениях

---

### Тестирование Приоритета 2
- [ ] DatePicker выбирает диапазон
- [ ] Статистика соответствует реальности
- [ ] Live tracking обновляется каждые 15 сек
- [ ] Скорость корректная
- [ ] GPX экспортируется и открывается

---

## Приоритет 3: Улучшение точности локации
**Версия**: v6.2.0
**Оценка времени**: 2-3 часа
**Статус**: ⏳ Ожидает завершения Приоритета 2

### Описание
Повысить точность определения местоположения через применение Kalman фильтра и фильтрацию некачественных данных.

### Задачи

#### 3.1. Kalman Filter (2 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/location/KalmanLocationFilter.kt` (новый)
- `app/src/main/java/ru/example/childwatch/location/LocationManager.kt` (изменение)

**Шаги**:
1. Создать упрощенный Kalman filter:
   ```kotlin
   class KalmanLocationFilter {
       private var lastLat = 0.0
       private var lastLon = 0.0
       private var variance = -1.0

       companion object {
           private const val PROCESS_NOISE = 0.001  // Шум процесса
           private const val MIN_ACCURACY = 1.0     // Минимальная точность
       }

       fun filter(lat: Double, lon: Double, accuracy: Float): Pair<Double, Double> {
           if (variance < 0) {
               // Инициализация
               lastLat = lat
               lastLon = lon
               variance = accuracy.toDouble()
               return Pair(lat, lon)
           }

           // Kalman gain
           val accuracy = max(accuracy.toDouble(), MIN_ACCURACY)
           val gain = variance / (variance + accuracy)

           // Обновление оценки
           lastLat += gain * (lat - lastLat)
           lastLon += gain * (lon - lastLon)

           // Обновление дисперсии
           variance = (1 - gain) * variance + PROCESS_NOISE

           return Pair(lastLat, lastLon)
       }

       fun reset() {
           variance = -1.0
       }
   }
   ```

2. Интегрировать в LocationManager:
   ```kotlin
   private val kalmanFilter = KalmanLocationFilter()

   override fun onLocationResult(result: LocationResult) {
       val rawLocation = result.lastLocation ?: return

       // Применить фильтр
       val (filteredLat, filteredLon) = kalmanFilter.filter(
           rawLocation.latitude,
           rawLocation.longitude,
           rawLocation.accuracy
       )

       // Использовать отфильтрованные координаты
       val filteredLocation = Location(rawLocation).apply {
           latitude = filteredLat
           longitude = filteredLon
       }

       uploadLocation(filteredLocation)
   }
   ```

**Acceptance Criteria**:
- [ ] Фильтр инициализируется корректно
- [ ] Координаты сглаживаются
- [ ] Нет значительной задержки

---

#### 3.2. Фильтрация некачественных точек (1 час)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/location/LocationManager.kt` (изменение)

**Шаги**:
1. Добавить проверки качества:
   ```kotlin
   private fun isLocationValid(location: Location): Boolean {
       // Проверка 1: Точность
       if (location.accuracy > 50) {
           Log.w(TAG, "Location rejected: poor accuracy ${location.accuracy}m")
           return false
       }

       // Проверка 2: Невозможная скорость
       if (location.speed > 55) {  // > 200 км/ч
           Log.w(TAG, "Location rejected: impossible speed ${location.speed}m/s")
           return false
       }

       // Проверка 3: Прыжок координат
       lastLocation?.let { last ->
           val distance = location.distanceTo(last)
           val timeDiff = (location.time - last.time) / 1000.0
           if (timeDiff > 0) {
               val speed = distance / timeDiff
               if (speed > 55) {  // > 200 км/ч
                   Log.w(TAG, "Location rejected: teleportation detected")
                   return false
               }
           }
       }

       return true
   }
   ```

2. Применять фильтр перед Kalman:
   ```kotlin
   override fun onLocationResult(result: LocationResult) {
       val location = result.lastLocation ?: return

       if (!isLocationValid(location)) {
           return  // Пропустить некачественную точку
       }

       // Дальше Kalman фильтр и upload
   }
   ```

**Acceptance Criteria**:
- [ ] Некачественные точки отбрасываются
- [ ] Логируются причины отбрасывания
- [ ] Не отбрасываются хорошие точки

---

### Тестирование Приоритета 3
- [ ] Координаты стабильнее (меньше прыжков)
- [ ] Точки с плохой accuracy отбрасываются
- [ ] Нет "телепортации" на карте
- [ ] Маршрут выглядит более гладким

---

## Приоритет 4: Проверка и улучшение чата
**Версия**: v6.3.0
**Оценка времени**: 3-4 часа
**Статус**: ⏳ Ожидает завершения Приоритета 3

### Описание
Протестировать текущую реализацию чата на предмет доставки сообщений в обе стороны, исправить найденные проблемы, добавить персонализацию.

### Задачи

#### 4.1. Тестирование чата (1 час)

**Тест-кейсы**:
1. **Отправка родитель → ребенок (хороший интернет)**
   - [ ] Сообщение отправляется
   - [ ] Статус меняется: Отправка → Отправлено → Доставлено → Прочитано
   - [ ] Сообщение появляется на детском устройстве
   - [ ] Timestamp корректный

2. **Отправка ребенок → родитель (хороший интернет)**
   - [ ] Сообщение отправляется
   - [ ] Статусы обновляются
   - [ ] Сообщение появляется на родительском устройстве

3. **Отправка при плохом интернете**
   - [ ] Сообщение добавляется в очередь
   - [ ] Показывается индикатор "Ожидает отправки"
   - [ ] После восстановления связи - отправляется автоматически

4. **Отправка при отсутствии интернета**
   - [ ] Сообщение сохраняется локально
   - [ ] Статус "Не отправлено"
   - [ ] При появлении интернета - отправляется

5. **Получение при оффлайне**
   - [ ] После восстановления связи - получаются пропущенные сообщения
   - [ ] Все сообщения в правильном порядке

**Инструменты**:
- Logcat для отслеживания WebSocket событий
- Charles Proxy для симуляции плохого интернета
- Airplane mode для симуляции отсутствия сети

---

#### 4.2. Исправление найденных проблем (2 часа)

**Возможные проблемы и решения**:

1. **Проблема**: Сообщения теряются при сетевых сбоях
   - **Решение**: Улучшить MessageQueue
   ```kotlin
   // В MessageQueue.kt
   private suspend fun sendWithRetry(message: ChatMessage, maxAttempts: Int = 3) {
       var attempt = 0
       while (attempt < maxAttempts) {
           try {
               webSocket.sendMessage(message)
               updateMessageStatus(message.id, MessageStatus.SENT)
               return
           } catch (e: Exception) {
               attempt++
               if (attempt >= maxAttempts) {
                   updateMessageStatus(message.id, MessageStatus.FAILED)
                   throw e
               }
               delay(1000L * attempt)  // Экспоненциальная задержка
           }
       }
   }
   ```

2. **Проблема**: Статусы не обновляются
   - **Решение**: Проверить обработчики WebSocket событий
   ```kotlin
   // В WebSocketClient.kt
   socket.on("chat:status") { args ->
       val data = args[0] as JSONObject
       val messageId = data.getString("messageId")
       val status = MessageStatus.valueOf(data.getString("status"))

       chatMessageCallback?.invoke(
           ChatMessage(id = messageId, status = status, ...)
       )
   }
   ```

3. **Проблема**: Дубликаты сообщений
   - **Решение**: Проверять message_id перед вставкой
   ```kotlin
   // В ChatMessageDao.kt
   @Insert(onConflict = OnConflictStrategy.IGNORE)
   suspend fun insertMessage(message: ChatMessageEntity): Long
   ```

---

#### 4.3. Персонализация чата (1 час)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/ChatActivity.kt` (изменение)
- `app/src/main/java/ru/example/childwatch/chat/ChatAdapter.kt` (изменение)

**Шаги**:
1. Загрузить имя ребенка из БД:
   ```kotlin
   // В ChatActivity.onCreate()
   lifecycleScope.launch {
       val child = childRepository.getChildByDeviceId(childDeviceId)
       child?.let {
           supportActionBar?.title = "Чат с ${it.name}"
           chatAdapter.setChildName(it.name)
       }
   }
   ```

2. Обновить ChatAdapter:
   ```kotlin
   private var childName: String = "Ребенок"
   private var parentName: String = "Родитель"

   fun setChildName(name: String) {
       childName = name
       notifyDataSetChanged()
   }

   override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
       val message = messages[position]
       holder.senderName.text = if (message.isFromParent) parentName else childName
       // ...
   }
   ```

3. Добавить аватар:
   ```kotlin
   holder.avatarImage.load(child.avatarUri) {
       placeholder(R.drawable.ic_default_avatar)
       error(R.drawable.ic_default_avatar)
       transformations(CircleCropTransformation())
   }
   ```

**Acceptance Criteria**:
- [ ] Имя ребенка показывается в заголовке и сообщениях
- [ ] Аватар показывается если доступен
- [ ] Fallback на иконку по умолчанию

---

### Тестирование Приоритета 4
- [ ] Все тест-кейсы проходят
- [ ] Сообщения не теряются
- [ ] Статусы обновляются корректно
- [ ] Персонализация работает

---

## Приоритет 5: Дистанционное фото
**Версия**: v6.4.0
**Оценка времени**: 4-6 часов
**Статус**: ⏳ Ожидает завершения Приоритета 4

### Описание
Завершить реализацию функции удаленного фотографирования - команда от родителя, съемка на детском устройстве, загрузка на сервер, отображение на родительском устройстве.

### Задачи

#### 5.1. Server API Endpoints (1 час)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/network/ChildWatchApi.kt` (изменение)
- `app/src/main/java/ru/example/childwatch/network/NetworkClient.kt` (изменение)

**Шаги**:
1. Добавить в `ChildWatchApi.kt`:
   ```kotlin
   @Multipart
   @POST("api/photo/upload")
   suspend fun uploadPhoto(
       @Part("childId") childId: RequestBody,
       @Part("timestamp") timestamp: RequestBody,
       @Part("camera") camera: RequestBody,  // "front" or "back"
       @Part photo: MultipartBody.Part
   ): Response<PhotoUploadResponse>

   @GET("api/photo/latest/{childId}")
   suspend fun getLatestPhoto(
       @Path("childId") childId: String
   ): Response<PhotoResponse>

   @GET("api/photo/history/{childId}")
   suspend fun getPhotoHistory(
       @Path("childId") childId: String,
       @Query("limit") limit: Int = 20
   ): Response<PhotoHistoryResponse>
   ```

2. Добавить data classes:
   ```kotlin
   data class PhotoUploadResponse(
       val success: Boolean,
       val photoId: String,
       val url: String
   )

   data class PhotoResponse(
       val success: Boolean,
       val photo: PhotoData?
   )

   data class PhotoData(
       val id: String,
       val childId: String,
       val url: String,
       val timestamp: Long,
       val camera: String
   )
   ```

**Acceptance Criteria**:
- [ ] API методы определены
- [ ] Data classes созданы
- [ ] Retrofit правильно настроен для multipart

---

#### 5.2. Обработка команды на детском устройстве (2 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/network/WebSocketClient.kt` (изменение)
- `app/src/main/java/ru/example/childwatch/camera/RemotePhotoHandler.kt` (новый)

**Шаги**:
1. Добавить обработчик в WebSocketClient:
   ```kotlin
   socket.on("remote:photo") { args ->
       val data = args[0] as JSONObject
       val camera = data.getString("camera")  // "front" or "back"

       // Запустить capture в background
       remotePhotoHandler.capturePhoto(camera)
   }
   ```

2. Создать `RemotePhotoHandler.kt`:
   ```kotlin
   class RemotePhotoHandler(
       private val context: Context,
       private val cameraManager: CameraManager,
       private val networkClient: NetworkClient
   ) {
       suspend fun capturePhoto(cameraType: String) {
           try {
               // Показать уведомление
               showNotification("Делаем фото...")

               // Сделать фото
               val photoFile = cameraManager.takePicture(
                   cameraId = if (cameraType == "front") frontCameraId else backCameraId
               )

               // Загрузить на сервер
               val uploaded = networkClient.uploadPhoto(
                   childId = getChildDeviceId(),
                   photoFile = photoFile,
                   camera = cameraType,
                   timestamp = System.currentTimeMillis()
               )

               if (uploaded) {
                   showNotification("Фото отправлено родителю")
                   // Удалить локальный файл
                   photoFile.delete()
               } else {
                   showNotification("Ошибка отправки фото")
               }
           } catch (e: Exception) {
               Log.e(TAG, "Remote photo capture failed", e)
               showNotification("Не удалось сделать фото")
           }
       }

       private fun showNotification(message: String) {
           val notification = NotificationCompat.Builder(context, CHANNEL_ID)
               .setSmallIcon(R.drawable.ic_camera)
               .setContentTitle("Удаленное фото")
               .setContentText(message)
               .setPriority(NotificationCompat.PRIORITY_DEFAULT)
               .build()

           NotificationManagerCompat.from(context)
               .notify(PHOTO_NOTIFICATION_ID, notification)
       }
   }
   ```

**Acceptance Criteria**:
- [ ] Команда принимается по WebSocket
- [ ] Фото делается автоматически
- [ ] Загружается на сервер
- [ ] Уведомления показываются
- [ ] Обработка ошибок реализована

---

#### 5.3. UI на родительском устройстве (2-3 часа)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/PhotoActivity.kt` (значительные изменения)
- `app/src/main/res/layout/activity_photo.xml` (изменение)

**Шаги**:
1. Обновить UI PhotoActivity:
   ```xml
   <LinearLayout>
       <!-- Кнопки отправки команды -->
       <Button
           android:id="@+id/takeFrontPhotoButton"
           android:text="Фронтальная камера" />
       <Button
           android:id="@+id/takeBackPhotoButton"
           android:text="Задняя камера" />

       <!-- Индикатор загрузки -->
       <ProgressBar
           android:id="@+id/loadingIndicator"
           android:visibility="gone" />

       <!-- Превью последнего фото -->
       <ImageView
           android:id="@+id/photoPreview"
           android:layout_width="match_parent"
           android:layout_height="300dp"
           android:scaleType="centerCrop" />

       <TextView
           android:id="@+id/photoTimestamp"
           android:text="5 минут назад" />

       <!-- RecyclerView для галереи -->
       <androidx.recyclerview.widget.RecyclerView
           android:id="@+id/photoGallery"
           android:layout_width="match_parent"
           android:layout_height="wrap_content" />
   </LinearLayout>
   ```

2. Реализовать логику:
   ```kotlin
   class PhotoActivity : AppCompatActivity() {
       private var photoPollingJob: Job? = null

       private fun onTakeFrontPhotoClick() {
           sendPhotoCommand("front")
           showLoadingIndicator()
           startPhotoPolling()
       }

       private fun sendPhotoCommand(camera: String) {
           lifecycleScope.launch {
               try {
                   webSocketClient.sendCommand("take_photo", mapOf("camera" to camera))
                   Toast.makeText(this@PhotoActivity, "Команда отправлена", Toast.LENGTH_SHORT).show()
               } catch (e: Exception) {
                   Toast.makeText(this@PhotoActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
               }
           }
       }

       private fun startPhotoPolling() {
           photoPollingJob = lifecycleScope.launch {
               repeat(20) { // 20 попыток = 1 минута
                   delay(3000)  // Каждые 3 секунды

                   val photo = networkClient.getLatestPhoto(childDeviceId)
                   if (photo != null && photo.timestamp > lastPhotoTimestamp) {
                       displayPhoto(photo)
                       hideLoadingIndicator()
                       photoPollingJob?.cancel()
                       return@launch
                   }
               }

               // Таймаут
               hideLoadingIndicator()
               Toast.makeText(this@PhotoActivity, "Фото не получено (таймаут)", Toast.LENGTH_LONG).show()
           }
       }

       private fun displayPhoto(photo: PhotoData) {
           Glide.with(this)
               .load(photo.url)
               .into(binding.photoPreview)

           binding.photoTimestamp.text = formatTimestamp(photo.timestamp)

           // Добавить в галерею
           photoAdapter.addPhoto(photo)
       }

       private fun loadPhotoHistory() {
           lifecycleScope.launch {
               val history = networkClient.getPhotoHistory(childDeviceId, limit = 20)
               history?.let { photoAdapter.setPhotos(it) }
           }
       }
   }
   ```

3. Создать PhotoAdapter для галереи:
   ```kotlin
   class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {
       private val photos = mutableListOf<PhotoData>()

       fun addPhoto(photo: PhotoData) {
           photos.add(0, photo)  // Добавить в начало
           notifyItemInserted(0)
       }

       override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
           val photo = photos[position]
           Glide.with(holder.itemView)
               .load(photo.url)
               .thumbnail(0.1f)  // Миниатюра
               .into(holder.imageView)

           holder.itemView.setOnClickListener {
               // Открыть фото в полном размере
               showFullScreenPhoto(photo)
           }
       }
   }
   ```

**Acceptance Criteria**:
- [ ] Кнопки отправляют команду
- [ ] Loading indicator показывается
- [ ] Фото загружается и отображается
- [ ] Галерея работает
- [ ] Можно сохранить фото в устройство

---

#### 5.4. Оптимизации (опционально, 1 час)
**Файлы**:
- `app/src/main/java/ru/example/childwatch/utils/ImageCompressor.kt` (новый)

**Шаги**:
1. Сжимать фото перед отправкой:
   ```kotlin
   class ImageCompressor {
       fun compress(inputFile: File, quality: Int = 80): File {
           val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)

           // Resize если слишком большое
           val maxDimension = 1920
           val resized = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
               val ratio = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
               Bitmap.createScaledBitmap(
                   bitmap,
                   (bitmap.width * ratio).toInt(),
                   (bitmap.height * ratio).toInt(),
                   true
               )
           } else {
               bitmap
           }

           // Compress
           val outputFile = File.createTempFile("compressed_", ".jpg")
           FileOutputStream(outputFile).use { out ->
               resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
           }

           bitmap.recycle()
           if (resized != bitmap) resized.recycle()

           return outputFile
       }
   }
   ```

**Acceptance Criteria**:
- [ ] Фото сжимаются перед отправкой
- [ ] Размер файла разумный (< 500 KB)
- [ ] Качество приемлемое

---

### Тестирование Приоритета 5
- [ ] Команда отправляется от родителя
- [ ] Фото делается на детском устройстве
- [ ] Фото загружается на сервер
- [ ] Фото появляется у родителя
- [ ] Галерея работает
- [ ] Можно сохранить фото

---

## Дополнительные улучшения (Backlog)

### Геозоны (v6.5.0)
**Оценка**: 6-8 часов
- UI для создания зон на карте
- Сохранение в БД
- Мониторинг входа/выхода
- Push-уведомления

### Оптимизация батареи (v6.6.0)
**Оценка**: 2-3 часа
- Адаптивная частота локации
- Geofencing API
- Снижение частоты при низком заряде

---

## График реализации

```
Неделя 1:
├── День 1-2: Приоритет 1 (Parent Location) - 8-12 ч
├── День 3: Приоритет 2 (Routes) - 4-6 ч
└── День 4: Приоритет 3 (Accuracy) - 2-3 ч

Неделя 2:
├── День 1: Приоритет 4 (Chat) - 3-4 ч
├── День 2-3: Приоритет 5 (Photo) - 4-6 ч
└── День 4: Тестирование и баг-фиксы
```

---

## Риски и митигации

| Риск | Вероятность | Влияние | Митигация |
|------|-------------|---------|-----------|
| Серверные API не готовы | Средняя | Высокое | Начать с заглушек, параллельно координировать с backend |
| Проблемы с миграцией БД | Низкая | Высокое | Тщательное тестирование миграции, бэкап данных |
| Расход батареи выше ожидаемого | Средняя | Среднее | Профилирование, оптимизация частоты обновлений |
| Ошибки в Kalman фильтре | Средняя | Низкое | Тестирование на реальных данных, возможность отключить |
| Проблемы с WebSocket на детском устройстве | Низкая | Высокое | Тестирование в различных сетевых условиях |

---

## Критерии приемки версии 6.0.0

### Функциональные требования
- [ ] Родитель может включить sharing своей локации
- [ ] Ребенок видит родителя на карте
- [ ] ETA рассчитывается корректно
- [ ] История маршрутов с выбором даты
- [ ] Live tracking обновляется каждые 15 сек
- [ ] Kalman фильтр улучшает точность
- [ ] Чат доставляет сообщения в обе стороны
- [ ] Удаленное фото работает end-to-end

### Нефункциональные требования
- [ ] Расход батареи приемлемый (< 10% в час при активном использовании)
- [ ] Приложение стабильно работает 8+ часов
- [ ] Нет memory leaks
- [ ] UI responsive, нет ANR
- [ ] Работает на Android 8.0+ (API 26+)

### Документация
- [ ] CHANGELOG обновлен
- [ ] README содержит информацию о новых функциях
- [ ] Комментарии в коде для сложной логики

---

## Метрики успеха

1. **Функциональность**:
   - 100% тест-кейсов проходят
   - 0 критических багов

2. **Производительность**:
   - Время запуска < 2 секунд
   - Время отклика UI < 100 мс
   - Расход батареи < 10% в час

3. **Качество**:
   - Crash-free rate > 99%
   - 0 memory leaks
   - Все TODO из roadmap закрыты

---

## Контакты и ресурсы

- **GitHub**: https://github.com/username/ChildWatch (если есть)
- **Документация**: /docs
- **Backend API**: (URL сервера)
- **Тестовые устройства**: Родительское (модель), Детское (модель)

---

**Последнее обновление**: 29 октября 2025
**Автор**: Claude (Sonnet 4.5)
**Статус**: Утверждено для реализации
