package ru.example.childwatch.database.repository

import kotlinx.coroutines.flow.Flow
import ru.example.childwatch.database.dao.ChildDao
import ru.example.childwatch.database.entity.Child

/**
 * Repository для работы с данными детей
 *
 * Предоставляет единую точку доступа к данным Child,
 * абстрагируя источник данных от остальной части приложения.
 */
class ChildRepository(private val childDao: ChildDao) {

    /**
     * Получить ребенка по ID
     */
    suspend fun getChildById(id: Long): Child? {
        return childDao.getById(id)
    }

    /**
     * Получить ребенка по ID устройства
     */
    suspend fun getChildByDeviceId(deviceId: String): Child? {
        return childDao.getByDeviceId(deviceId)
    }

    /**
     * Получить ребенка по ID устройства (Flow)
     */
    fun getChildByDeviceIdFlow(deviceId: String): Flow<Child?> {
        return childDao.getByDeviceIdFlow(deviceId)
    }

    /**
     * Получить всех детей
     */
    suspend fun getAllChildren(): List<Child> {
        return childDao.getAll()
    }

    /**
     * Получить всех детей (Flow)
     */
    fun getAllChildrenFlow(): Flow<List<Child>> {
        return childDao.getAllFlow()
    }

    /**
     * Получить активных детей
     */
    suspend fun getActiveChildren(): List<Child> {
        return childDao.getActiveChildren()
    }

    /**
     * Получить активных детей (Flow)
     */
    fun getActiveChildrenFlow(): Flow<List<Child>> {
        return childDao.getActiveChildrenFlow()
    }

    /**
     * Добавить или обновить ребенка
     */
    suspend fun insertOrUpdateChild(child: Child): Long {
        return childDao.insert(child)
    }

    /**
     * Добавить несколько детей
     */
    suspend fun insertChildren(children: List<Child>): List<Long> {
        return childDao.insertAll(children)
    }

    /**
     * Обновить ребенка
     */
    suspend fun updateChild(child: Child) {
        childDao.update(child)
    }

    /**
     * Удалить ребенка
     */
    suspend fun deleteChild(child: Child) {
        childDao.delete(child)
    }

    /**
     * Обновить время последней активности
     */
    suspend fun updateLastSeen(childId: Long, timestamp: Long = System.currentTimeMillis()) {
        childDao.updateLastSeen(childId, timestamp)
    }

    /**
     * Установить статус активности
     */
    suspend fun setActiveStatus(childId: Long, isActive: Boolean) {
        childDao.setActiveStatus(childId, isActive)
    }

    /**
     * Получить количество детей
     */
    suspend fun getChildCount(): Int {
        return childDao.getCount()
    }

    /**
     * Удалить всех детей
     */
    suspend fun deleteAllChildren() {
        childDao.deleteAll()
    }

    /**
     * Получить или создать ребенка по deviceId
     */
    suspend fun getOrCreateChild(deviceId: String, name: String = "Устройство"): Child {
        var child = getChildByDeviceId(deviceId)
        if (child == null) {
            val newChild = Child(
                deviceId = deviceId,
                name = name,
                isActive = true,
                lastSeenAt = System.currentTimeMillis()
            )
            val id = insertOrUpdateChild(newChild)
            child = newChild.copy(id = id)
        }
        return child
    }
}
