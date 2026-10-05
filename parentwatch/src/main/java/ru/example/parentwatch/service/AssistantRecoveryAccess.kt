package ru.example.parentwatch.service

import android.content.ComponentName
import android.content.Context
import android.service.voice.VoiceInteractionService

/** Eligibility only: the system must actually have selected our opt-in assistant. */
object AssistantRecoveryAccess {
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("assistant_recovery_pilot", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

    fun isSelected(context: Context): Boolean = isEnabled(context) &&
        VoiceInteractionService.isActiveService(context, ComponentName(context.packageName,
            "ru.example.parentwatch.debug.FamilyAssistantService"))
}
