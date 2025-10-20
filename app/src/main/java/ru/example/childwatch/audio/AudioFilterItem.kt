package ru.example.childwatch.audio

import ru.example.childwatch.audio.AudioQualityMode

// Модель для карточки фильтра
 data class AudioFilterItem(
    val mode: AudioQualityMode,
    val emoji: String,
    val title: String,
    val description: String
)