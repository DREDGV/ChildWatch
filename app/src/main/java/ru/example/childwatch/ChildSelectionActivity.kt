package ru.example.childwatch

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import ru.example.childwatch.adapter.ChildrenAdapter
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.database.entity.Child
import ru.example.childwatch.database.repository.ChildRepository
import ru.example.childwatch.databinding.ActivityChildSelectionBinding
import android.util.Log

/**
 * Activity для выбора и управления детскими устройствами
 *
 * Показывает список всех зарегистрированных детских устройств,
 * позволяет добавлять новые и выбирать текущее для мониторинга.
 */
class ChildSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildSelectionBinding
    private lateinit var childrenAdapter: ChildrenAdapter
    private lateinit var database: ChildWatchDatabase
    private lateinit var childRepository: ChildRepository
    private var selectedAvatarUri: Uri? = null

    // Launcher for avatar selection
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                // Запросить постоянный доступ к URI
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                selectedAvatarUri = it
                currentAvatarImageView?.setImageURI(it)
                Log.d(TAG, "Avatar selected with persistent permission: $it")
            } catch (e: Exception) {
                // Если не получилось получить постоянный доступ, все равно используем URI
                Log.w(TAG, "Could not take persistable URI permission, using temporary: ${e.message}")
                selectedAvatarUri = it
                currentAvatarImageView?.setImageURI(it)
            }
        }
    }

    // Keep reference to current avatar ImageView in dialog
    private var currentAvatarImageView: ImageView? = null

    // Keep references to current dialog inputs for contact picker
    private var currentNameInput: TextInputEditText? = null
    private var currentPhoneInput: TextInputEditText? = null
    private var currentDeviceIdInput: TextInputEditText? = null

    // Launcher for contact selection
    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri ->
        contactUri?.let {
            loadContactData(it)
        }
    }

    // Launcher for single phone number selection
    private val pickPhoneLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { contactUri ->
        contactUri?.let {
            loadContactPhone(it)
        }
    }

    // Launcher for QR code scanning
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            currentDeviceIdInput?.setText(result.contents)
            Log.d(TAG, "QR code scanned: ${result.contents}")
            Toast.makeText(this, "Device ID отсканирован", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "QR scan cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Log.d(TAG, "onCreate started")
            binding = ActivityChildSelectionBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(TAG, "View binding successful")

            database = ChildWatchDatabase.getInstance(this)
            childRepository = ChildRepository(database.childDao())
            Log.d(TAG, "Database initialized")

            setupToolbar()
            setupRecyclerView()
            setupFab()
            loadChildren()
            Log.d(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: onCreate failed", e)
            showError("Ошибка инициализации: ${e.message}")
            finish()
        }
    }

    /**
     * Настройка Toolbar
     */
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    /**
     * Настройка RecyclerView
     */
    private fun setupRecyclerView() {
        childrenAdapter = ChildrenAdapter(
            onChildClick = { child -> onChildSelected(child) },
            onChildEdit = { child -> showEditChildDialog(child) }
        )

        binding.childrenRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChildSelectionActivity)
            adapter = childrenAdapter
        }
    }

    /**
     * Настройка FAB для добавления устройства
     */
    private fun setupFab() {
        binding.addChildFab.setOnClickListener {
            showAddChildDialog()
        }
    }

    /**
     * Загрузка списка детей
     */
    private fun loadChildren() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val children = childRepository.getAllChildren()

                if (children.isEmpty()) {
                    showEmptyState()
                } else {
                    showChildrenList(children)
                }

                binding.progressBar.visibility = View.GONE
                Log.d(TAG, "Загружено устройств: ${children.size}")

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка загрузки устройств", e)
                binding.progressBar.visibility = View.GONE
                showError("Ошибка загрузки: ${e.message}")
            }
        }
    }

    /**
     * Показать список детей
     */
    private fun showChildrenList(children: List<Child>) {
        binding.childrenRecyclerView.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        childrenAdapter.submitList(children)
    }

    /**
     * Показать пустое состояние
     */
    private fun showEmptyState() {
        binding.childrenRecyclerView.visibility = View.GONE
        binding.emptyStateLayout.visibility = View.VISIBLE
    }

    /**
     * Обработка выбора ребенка
     */
    private fun onChildSelected(child: Child) {
        Log.d(TAG, "Выбран ребенок: ${child.name} (${child.deviceId})")

        // Сохранить выбранное устройство
        val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
        prefs.edit().putString("selected_device_id", child.deviceId).apply()

        // Вернуть результат
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SELECTED_DEVICE_ID, child.deviceId)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Показать диалог добавления нового устройства
     */
    private fun showAddChildDialog() {
        selectedAvatarUri = null  // Reset avatar selection
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_child, null)

        // Find views
        val avatarImage = dialogView.findViewById<ImageView>(R.id.childAvatarImage)
        val changeAvatarButton = dialogView.findViewById<MaterialButton>(R.id.changeAvatarButton)
        val selectContactButton = dialogView.findViewById<MaterialButton>(R.id.selectContactButton)
        val scanQrButton = dialogView.findViewById<MaterialButton>(R.id.scanQrButton)
        val deviceIdInputLayout = dialogView.findViewById<TextInputLayout>(R.id.deviceIdInputLayout)
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)
        val childAgeInput = dialogView.findViewById<TextInputEditText>(R.id.childAgeInput)
        val childPhoneInputLayout = dialogView.findViewById<TextInputLayout>(R.id.childPhoneInputLayout)
        val childPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.childPhoneInput)

        // Set up avatar selection
        currentAvatarImageView = avatarImage
        changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Set up contact picker
        currentNameInput = childNameInput
        currentPhoneInput = childPhoneInput
        currentDeviceIdInput = deviceIdInput
        Log.d(TAG, "Setting up contact picker button")
        selectContactButton.setOnClickListener {
            Log.d(TAG, "Contact picker button clicked!")
            requestContactsPermission {
                Log.d(TAG, "Contacts permission granted, launching picker")
                pickContactLauncher.launch(null)
            }
        }

        // Set up QR scanner button
        scanQrButton.setOnClickListener {
            Log.d(TAG, "QR scanner button clicked")
            requestCameraPermission {
                Log.d(TAG, "Camera permission granted, launching QR scanner")
                val options = ScanOptions()
                options.setPrompt("Наведите камеру на QR-код")
                options.setBeepEnabled(true)
                options.setBarcodeImageEnabled(false)
                options.setOrientationLocked(false)
                qrScannerLauncher.launch(options)
            }
        }

        // Set up Device ID paste button
        deviceIdInputLayout.setEndIconOnClickListener {
            pasteDeviceIdFromClipboard(deviceIdInput)
        }

        // Set up phone picker button
        childPhoneInputLayout.setEndIconOnClickListener {
            requestContactsPermission {
                pickPhoneLauncher.launch(null)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Добавить устройство")
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                val deviceId = deviceIdInput.text.toString().trim()
                val childName = childNameInput.text.toString().trim()
                val ageText = childAgeInput.text.toString().trim()
                val phoneNumber = childPhoneInput.text.toString().trim()

                if (deviceId.isNotEmpty() && childName.isNotEmpty()) {
                    val age = ageText.toIntOrNull()
                    addChild(
                        deviceId = deviceId,
                        name = childName,
                        age = age,
                        phoneNumber = phoneNumber.ifEmpty { null },
                        avatarUrl = selectedAvatarUri?.toString()
                    )
                } else {
                    showError("Заполните обязательные поля (Device ID и имя)")
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
            }
            .setOnDismissListener {
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
            }
            .show()
    }

    /**
     * Добавить новое устройство
     */
    private fun addChild(
        deviceId: String,
        name: String,
        age: Int? = null,
        phoneNumber: String? = null,
        avatarUrl: String? = null
    ) {
        lifecycleScope.launch {
            try {
                // Проверить, существует ли устройство
                val existingChild = childRepository.getChildByDeviceId(deviceId)

                if (existingChild != null) {
                    showError("Устройство с таким ID уже добавлено")
                    return@launch
                }

                // Создать новый профиль
                val child = Child(
                    deviceId = deviceId,
                    name = name,
                    age = age,
                    phoneNumber = phoneNumber,
                    avatarUrl = avatarUrl,
                    lastSeenAt = null,
                    isActive = true
                )

                childRepository.insertOrUpdateChild(child)
                Log.d(TAG, "Устройство добавлено: $name ($deviceId)")

                // Обновить список
                loadChildren()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка добавления устройства", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Показать диалог редактирования устройства
     */
    private fun showEditChildDialog(child: Child) {
        selectedAvatarUri = child.avatarUrl?.let { Uri.parse(it) }  // Load existing avatar
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_child, null)

        // Find views
        val avatarImage = dialogView.findViewById<ImageView>(R.id.childAvatarImage)
        val changeAvatarButton = dialogView.findViewById<MaterialButton>(R.id.changeAvatarButton)
        val selectContactButton = dialogView.findViewById<MaterialButton>(R.id.selectContactButton)
        val deviceIdInputLayout = dialogView.findViewById<TextInputLayout>(R.id.deviceIdInputLayout)
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)
        val childAgeInput = dialogView.findViewById<TextInputEditText>(R.id.childAgeInput)
        val childPhoneInputLayout = dialogView.findViewById<TextInputLayout>(R.id.childPhoneInputLayout)
        val childPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.childPhoneInput)

        // Заполнить текущие данные
        deviceIdInput.setText(child.deviceId)
        deviceIdInput.isEnabled = false // Device ID нельзя изменить
        deviceIdInputLayout.isEnabled = false // Также отключить layout
        childNameInput.setText(child.name)
        childAgeInput.setText(child.age?.toString() ?: "")
        childPhoneInput.setText(child.phoneNumber ?: "")

        // Set current avatar if exists
        if (child.avatarUrl != null) {
            try {
                val uri = Uri.parse(child.avatarUrl)
                // Проверяем доступность URI
                contentResolver.openInputStream(uri)?.use {
                    // URI доступен, загружаем аватар
                    avatarImage.setImageURI(uri)
                }
            } catch (e: SecurityException) {
                // URI больше недоступен - сбрасываем аватар
                Log.w(TAG, "Avatar URI no longer accessible, will use default icon", e)
                avatarImage.setImageResource(android.R.drawable.ic_menu_myplaces)
                // Очищаем недоступный URI в базе
                lifecycleScope.launch {
                    val updatedChild = child.copy(avatarUrl = null)
                    childRepository.insertOrUpdateChild(updatedChild)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading avatar", e)
                avatarImage.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        }

        // Set up avatar selection
        currentAvatarImageView = avatarImage
        changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Set up contact picker
        currentNameInput = childNameInput
        currentPhoneInput = childPhoneInput
        Log.d(TAG, "Setting up contact picker button")
        selectContactButton.setOnClickListener {
            Log.d(TAG, "Contact picker button clicked!")
            requestContactsPermission {
                Log.d(TAG, "Contacts permission granted, launching picker")
                pickContactLauncher.launch(null)
            }
        }

        // Set up phone picker button
        childPhoneInputLayout.setEndIconOnClickListener {
            requestContactsPermission {
                pickPhoneLauncher.launch(null)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Редактировать устройство")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = childNameInput.text.toString().trim()
                val ageText = childAgeInput.text.toString().trim()
                val phoneNumber = childPhoneInput.text.toString().trim()

                if (newName.isNotEmpty()) {
                    val age = ageText.toIntOrNull()
                    updateChild(
                        child = child,
                        newName = newName,
                        newAge = age,
                        newPhoneNumber = phoneNumber.ifEmpty { null },
                        newAvatarUrl = selectedAvatarUri?.toString()
                    )
                } else {
                    showError("Имя не может быть пустым")
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
            }
            .setNeutralButton("Удалить") { _, _ ->
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
                showDeleteConfirmDialog(child)
            }
            .setOnDismissListener {
                currentAvatarImageView = null
                currentNameInput = null
                currentPhoneInput = null
            }
            .show()
    }

    /**
     * Обновить данные устройства
     */
    private fun updateChild(
        child: Child,
        newName: String,
        newAge: Int? = null,
        newPhoneNumber: String? = null,
        newAvatarUrl: String? = null
    ) {
        lifecycleScope.launch {
            try {
                val updatedChild = child.copy(
                    name = newName,
                    age = newAge,
                    phoneNumber = newPhoneNumber,
                    avatarUrl = newAvatarUrl ?: child.avatarUrl,  // Keep old avatar if no new one selected
                    updatedAt = System.currentTimeMillis()
                )
                childRepository.insertOrUpdateChild(updatedChild)
                Log.d(TAG, "Устройство обновлено: $newName (${child.deviceId})")

                // Обновить список
                loadChildren()

                // Показать уведомление
                android.widget.Toast.makeText(
                    this@ChildSelectionActivity,
                    "Данные обновлены",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обновления устройства", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Показать диалог подтверждения удаления
     */
    private fun showDeleteConfirmDialog(child: Child) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить устройство?")
            .setMessage("Вы уверены, что хотите удалить устройство \"${child.name}\"?\n\nЭто действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteChild(child)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Удалить устройство
     */
    private fun deleteChild(child: Child) {
        lifecycleScope.launch {
            try {
                childRepository.deleteChild(child)
                Log.d(TAG, "Устройство удалено: ${child.name} (${child.deviceId})")

                // Обновить список
                loadChildren()

                // Показать уведомление
                android.widget.Toast.makeText(
                    this@ChildSelectionActivity,
                    "Устройство удалено",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                // Если удалённое устройство было выбрано, очистить выбор
                val prefs = getSharedPreferences("childwatch_prefs", MODE_PRIVATE)
                val selectedDeviceId = prefs.getString("selected_device_id", null)
                if (selectedDeviceId == child.deviceId) {
                    prefs.edit().remove("selected_device_id").apply()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка удаления устройства", e)
                showError("Ошибка: ${e.message}")
            }
        }
    }

    /**
     * Показать ошибку
     */
    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Ошибка")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Загрузить данные контакта (имя и телефон) для автозаполнения
     */
    private fun loadContactData(contactUri: Uri) {
        Log.d(TAG, "Loading contact data from URI: $contactUri")
        Log.d(TAG, "currentNameInput is null: ${currentNameInput == null}")
        Log.d(TAG, "currentPhoneInput is null: ${currentPhoneInput == null}")

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER
        )

        try {
            val cursor = contentResolver.query(contactUri, projection, null, null, null)
            if (cursor == null) {
                Log.e(TAG, "Contact query returned null")
                return
            }

            cursor.use {
                if (!it.moveToFirst()) {
                    Log.w(TAG, "Contact cursor is empty")
                    return@use
                }

                // Загрузить имя
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val contactName: String? = if (nameIndex >= 0) it.getString(nameIndex) else null

                contactName?.let { name ->
                    currentNameInput?.setText(name)
                    Log.d(TAG, "Contact name loaded: $name")
                }

                // Проверить наличие телефона
                val hasPhoneIndex = it.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                val hasPhoneNumber: Int = if (hasPhoneIndex >= 0) it.getInt(hasPhoneIndex) else 0
                val hasPhone = hasPhoneNumber > 0
                Log.d(TAG, "Contact has phone: $hasPhone")

                if (!hasPhone) {
                    Log.w(TAG, "Contact has no phone number")
                    Toast.makeText(this, "У контакта нет телефона", Toast.LENGTH_SHORT).show()
                    return@use
                }

                // Загрузить телефон
                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val contactId: String? = if (idIndex >= 0) it.getString(idIndex) else null

                if (contactId != null) {
                    Log.d(TAG, "Loading phone for contact ID: $contactId")
                    loadPhoneForContact(contactId)
                } else {
                    Log.w(TAG, "Contact ID is null")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact data", e)
            Toast.makeText(this, "Ошибка загрузки контакта: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Загрузить телефон контакта по ID
     */
    private fun loadPhoneForContact(contactId: String) {
        Log.d(TAG, "loadPhoneForContact called with ID: $contactId")
        val phoneProjection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val phoneSelectionArgs = arrayOf(contactId)

        try {
            val phoneCursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                phoneSelection,
                phoneSelectionArgs,
                null
            )
            if (phoneCursor == null) {
                Log.e(TAG, "Phone query returned null")
                return
            }

            phoneCursor.use {
                Log.d(TAG, "Phone cursor count: ${it.count}")
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    Log.d(TAG, "Phone number column index: $numberIndex")
                    val phoneNumber = if (numberIndex >= 0) it.getString(numberIndex) else null

                    Log.d(TAG, "Phone number retrieved: $phoneNumber")
                    if (phoneNumber != null) {
                        currentPhoneInput?.setText(phoneNumber)
                        Log.d(TAG, "Contact phone loaded and set: $phoneNumber")
                        Toast.makeText(this, "Телефон загружен: $phoneNumber", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.w(TAG, "Phone number is null")
                    }
                } else {
                    Log.w(TAG, "Phone cursor is empty")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact phone", e)
            Toast.makeText(this, "Ошибка загрузки телефона: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Загрузить только телефон из контакта
     */
    private fun loadContactPhone(contactUri: Uri) {
        val projection = arrayOf(ContactsContract.Contacts._ID)

        try {
            val cursor = contentResolver.query(contactUri, projection, null, null, null)
            if (cursor == null) {
                Log.e(TAG, "Contact query returned null for phone")
                return
            }

            cursor.use {
                if (it.moveToFirst()) {
                    val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                    if (idIndex >= 0) {
                        val contactId = it.getString(idIndex)
                        loadPhoneForContact(contactId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact phone", e)
            Toast.makeText(this, "Ошибка загрузки телефона: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Вставить Device ID из буфера обмена
     */
    private fun pasteDeviceIdFromClipboard(input: TextInputEditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (!text.isNullOrEmpty()) {
                input.setText(text)
                Toast.makeText(this, "Device ID вставлен", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Device ID pasted from clipboard: $text")
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Проверить разрешение на чтение контактов
     */
    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Запросить разрешение на чтение контактов
     */
    private fun requestContactsPermission(onGranted: () -> Unit) {
        Log.d(TAG, "requestContactsPermission called")
        when {
            checkContactsPermission() -> {
                Log.d(TAG, "Permission already granted")
                onGranted()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) -> {
                Log.d(TAG, "Showing permission rationale")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Разрешение на контакты")
                    .setMessage("Приложению нужен доступ к контактам для автозаполнения данных ребенка")
                    .setPositiveButton("Разрешить") { _, _ ->
                        Log.d(TAG, "User clicked allow in rationale dialog")
                        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS_PERMISSION)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            else -> {
                Log.d(TAG, "Requesting permission directly")
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS_PERMISSION)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CONTACTS_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Доступ к контактам разрешен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Доступ к контактам отклонен", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Доступ к камере разрешен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Доступ к камере отклонен", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Запросить разрешение на использование камеры
     */
    private fun requestCameraPermission(onGranted: () -> Unit) {
        Log.d(TAG, "requestCameraPermission called")
        when {
            checkCameraPermission() -> {
                Log.d(TAG, "Camera permission already granted")
                onGranted()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Showing camera permission rationale")
                MaterialAlertDialogBuilder(this)
                    .setTitle("Разрешение на камеру")
                    .setMessage("Приложению нужен доступ к камере для сканирования QR-кода")
                    .setPositiveButton("Разрешить") { _, _ ->
                        Log.d(TAG, "User clicked allow in camera rationale dialog")
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            else -> {
                Log.d(TAG, "Requesting camera permission directly")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            }
        }
    }

    /**
     * Проверить наличие разрешения на камеру
     */
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "ChildSelectionActivity"
        const val EXTRA_SELECTED_DEVICE_ID = "selected_device_id"
        private const val REQUEST_CONTACTS_PERMISSION = 100
        private const val REQUEST_CAMERA_PERMISSION = 101
    }
}
