package ru.example.childwatch.audio

// Модель для карточки фильтра
data class AudioFilterItem(
    val mode: FilterMode,
    val emoji: String,
    val title: String,
    val description: String
)
