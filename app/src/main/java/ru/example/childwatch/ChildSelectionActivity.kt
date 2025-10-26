package ru.example.childwatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
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

    companion object {
        private const val TAG = "ChildSelectionActivity"
        const val EXTRA_SELECTED_DEVICE_ID = "selected_device_id"
    }

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
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)
        val childAgeInput = dialogView.findViewById<TextInputEditText>(R.id.childAgeInput)
        val childPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.childPhoneInput)

        // Set up avatar selection
        currentAvatarImageView = avatarImage
        changeAvatarButton.setOnClickListener {
            pickImageLauncher.launch("image/*")
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
            }
            .setOnDismissListener {
                currentAvatarImageView = null
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
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)
        val childAgeInput = dialogView.findViewById<TextInputEditText>(R.id.childAgeInput)
        val childPhoneInput = dialogView.findViewById<TextInputEditText>(R.id.childPhoneInput)

        // Заполнить текущие данные
        deviceIdInput.setText(child.deviceId)
        deviceIdInput.isEnabled = false // Device ID нельзя изменить
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
            }
            .setNeutralButton("Удалить") { _, _ ->
                currentAvatarImageView = null
                showDeleteConfirmDialog(child)
            }
            .setOnDismissListener {
                currentAvatarImageView = null
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
}
