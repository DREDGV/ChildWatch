package ru.example.childwatch.audio

// Модель для карточки фильтра
data class AudioFilterItem(
    val mode: AudioEnhancer.FilterMode,
    val emoji: String,
    val title: String,
    val description: String
)
