package ru.example.childwatch.onboarding

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.childwatch.shared.onboarding.FamilyBootstrapRequest
import ru.childwatch.shared.onboarding.FamilyProfileConfirmationRequest
import ru.childwatch.shared.onboarding.OnboardingMemberData
import ru.example.childwatch.ParentSetupActivity
import ru.example.childwatch.database.ChildWatchDatabase
import ru.example.childwatch.network.NetworkClient
import ru.example.childwatch.profile.ParentParticipantNameResolver
import java.util.concurrent.TimeUnit

/**
 * Completes server-side family setup without keeping the first-run screen open.
 * Local setup is already usable while this worker waits for a working network.
 */
class ParentOnboardingSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    companion object {
        private const val TAG = "ParentOnboardingSync"
        private const val UNIQUE_WORK_NAME = "parent-onboarding-profile-sync"
        private const val KEY_FAMILY_NAME = "family_name"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_AVATAR_VALUE = "avatar_value"
        private const val KEY_SYNC_PENDING = "onboarding_sync_pending"

        fun enqueue(
            context: Context,
            familyName: String,
            displayName: String,
            avatarValue: String
        ) {
            val request = OneTimeWorkRequestBuilder<ParentOnboardingSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_FAMILY_NAME, familyName)
                        .putString(KEY_DISPLAY_NAME, displayName)
                        .putString(KEY_AVATAR_VALUE, avatarValue)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            context.getSharedPreferences(ParentSetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SYNC_PENDING, true)
                .putString(KEY_FAMILY_NAME, familyName)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_AVATAR_VALUE, avatarValue)
                .apply()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        /**
         * Recreates the pending onboarding request from durable local state.
         *
         * This is useful after a long network outage and in the emulator lab:
         * WorkManager input is not the only copy of the user's setup anymore.
         */
        suspend fun enqueueFromLocalProfile(
            context: Context,
            onlyIfPending: Boolean = true
        ): Boolean = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(
                ParentSetupActivity.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            if (onlyIfPending && !prefs.getBoolean(KEY_SYNC_PENDING, false)) {
                return@withContext false
            }

            val database = ChildWatchDatabase.getInstance(appContext)
            val parentId = prefs.getLong("parent_id", 0L)
            val parent = if (parentId > 0L) {
                database.parentDao().getById(parentId)
            } else {
                database.parentDao().getAll().firstOrNull()
            }
            val familyName = prefs.getString(KEY_FAMILY_NAME, null)
                ?.trim()
                .orEmpty()
                .ifBlank {
                    appContext.getString(ru.example.childwatch.R.string.parent_setup_default_family)
                }
            val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
                ?.trim()
                .orEmpty()
                .ifBlank { parent?.name?.trim().orEmpty() }
                .ifBlank {
                    appContext.getString(ru.example.childwatch.R.string.parent_setup_default_name)
                }
            val avatarValue = prefs.getString(KEY_AVATAR_VALUE, null)
                ?.trim()
                .orEmpty()
                .ifBlank { parent?.avatarUrl?.trim().orEmpty() }

            enqueue(appContext, familyName, displayName, avatarValue)
            true
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val familyName = inputData.getString(KEY_FAMILY_NAME).orEmpty().trim()
            .ifEmpty { applicationContext.getString(ru.example.childwatch.R.string.parent_setup_default_family) }
        val displayName = inputData.getString(KEY_DISPLAY_NAME).orEmpty().trim()
            .ifEmpty { applicationContext.getString(ru.example.childwatch.R.string.parent_setup_default_name) }
        val avatarValue = inputData.getString(KEY_AVATAR_VALUE).orEmpty().trim()
        val portableAvatar = avatarValue.takeIf { it.startsWith("preset:") }

        try {
            val networkClient = NetworkClient(applicationContext)
            if (!networkClient.ensureOnboardingAuthentication()) {
                return@withContext Result.retry()
            }
            val identityResponse = networkClient.getAuthenticatedIdentity()
            val identity = identityResponse.body()
                ?.takeIf { identityResponse.isSuccessful && it.success }
                ?: return@withContext Result.retry()
            val existing = identity.memberships
                .sortedWith(
                    compareByDescending<ru.example.childwatch.network.AuthenticatedMembershipData> {
                        it.binding.memberBindingSource == "EXPLICIT"
                    }.thenByDescending { it.binding.updatedAt }
                )
                .firstOrNull()

            val member = when {
                existing == null -> {
                    val response = networkClient.bootstrapFamily(
                        FamilyBootstrapRequest(
                            familyName = familyName,
                            displayName = displayName,
                            role = "PARENT",
                            avatarKey = portableAvatar
                        )
                    )
                    response.body()
                        ?.takeIf { response.isSuccessful && it.success }
                        ?.member
                        ?: return@withContext Result.retry()
                }

                existing.binding.memberBindingSource != "EXPLICIT" -> {
                    val response = networkClient.confirmOwnFamilyProfile(
                        existing.familyId,
                        FamilyProfileConfirmationRequest(
                            displayName = displayName,
                            avatarKey = portableAvatar
                        )
                    )
                    response.body()
                        ?.takeIf { response.isSuccessful && it.success }
                        ?.member
                        ?: return@withContext Result.retry()
                }

                else -> {
                    val response = networkClient.updateFamilyMemberProfile(
                        familyId = existing.familyId,
                        memberId = existing.memberId,
                        displayName = displayName,
                        avatarKey = portableAvatar
                    )
                    if (!response.isSuccessful || response.body()?.success != true) {
                        return@withContext Result.retry()
                    }
                    OnboardingMemberData(
                        id = existing.member.id,
                        familyId = existing.familyId,
                        displayName = displayName,
                        role = existing.member.role,
                        avatarKey = portableAvatar ?: existing.member.avatarKey
                    )
                }
            }

            updateLocalIdentity(member, avatarValue)
            applicationContext.getSharedPreferences(
                ParentSetupActivity.PREFS_NAME,
                Context.MODE_PRIVATE
            ).edit()
                .putBoolean(KEY_SYNC_PENDING, false)
                .apply()
            Log.i(TAG, "Family profile synchronized")
            Result.success()
        } catch (error: Exception) {
            Log.w(TAG, "Family profile sync postponed", error)
            Result.retry()
        }
    }

    private suspend fun updateLocalIdentity(
        member: OnboardingMemberData,
        localAvatarValue: String
    ) {
        val database = ChildWatchDatabase.getInstance(applicationContext)
        val prefs = applicationContext.getSharedPreferences(
            ParentSetupActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val parentId = prefs.getLong("parent_id", 0L)
        val existing = if (parentId > 0) {
            database.parentDao().getById(parentId)
        } else {
            database.parentDao().getAll().firstOrNull()
        } ?: return
        database.parentDao().update(
            existing.copy(
                accountId = member.id?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: existing.accountId,
                name = member.displayName,
                avatarUrl = member.avatarKey
                    ?: localAvatarValue.takeIf(String::isNotBlank)
                    ?: existing.avatarUrl,
                updatedAt = System.currentTimeMillis()
            )
        )
        applicationContext.getSharedPreferences("childwatch_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(ParentParticipantNameResolver.KEY_SELF_DISPLAY_NAME, member.displayName)
            .apply()
    }
}
