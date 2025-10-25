package ru.example.childwatch

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_child, null)
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("Добавить устройство")
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                val deviceId = deviceIdInput.text.toString().trim()
                val childName = childNameInput.text.toString().trim()

                if (deviceId.isNotEmpty() && childName.isNotEmpty()) {
                    addChild(deviceId, childName)
                } else {
                    showError("Заполните все поля")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Добавить новое устройство
     */
    private fun addChild(deviceId: String, name: String) {
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_child, null)
        val deviceIdInput = dialogView.findViewById<TextInputEditText>(R.id.deviceIdInput)
        val childNameInput = dialogView.findViewById<TextInputEditText>(R.id.childNameInput)

        // Заполнить текущие данные
        deviceIdInput.setText(child.deviceId)
        deviceIdInput.isEnabled = false // Device ID нельзя изменить
        childNameInput.setText(child.name)

        MaterialAlertDialogBuilder(this)
            .setTitle("Редактировать устройство")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = childNameInput.text.toString().trim()

                if (newName.isNotEmpty()) {
                    updateChild(child, newName)
                } else {
                    showError("Имя не может быть пустым")
                }
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить") { _, _ ->
                showDeleteConfirmDialog(child)
            }
            .show()
    }

    /**
     * Обновить данные устройства
     */
    private fun updateChild(child: Child, newName: String) {
        lifecycleScope.launch {
            try {
                val updatedChild = child.copy(name = newName)
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
