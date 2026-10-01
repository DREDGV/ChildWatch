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
import org.json.JSONObject
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

        /**
         * Whether the person asked for a new family at setup.
         *
         * Kept as an explicit instruction rather than derived from "no membership
         * found": a phone without a membership is usually a phone that means to
         * join an existing family, and creating one on its behalf put the person in
         * a family of their own with no way to reach the real one.
         */
        private const val KEY_MAY_CREATE_FAMILY = "may_create_family"

        /** The server's answer when a phone already belongs to a confirmed family. */
        private const val DEVICE_ALREADY_ONBOARDED = "DEVICE_ALREADY_ONBOARDED"
        private const val HTTP_CONFLICT = 409

        fun enqueue(
            context: Context,
            familyName: String,
            displayName: String,
            avatarValue: String,
            mayCreateFamily: Boolean = false
        ) {
            val request = OneTimeWorkRequestBuilder<ParentOnboardingSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_FAMILY_NAME, familyName)
                        .putString(KEY_DISPLAY_NAME, displayName)
                        .putString(KEY_AVATAR_VALUE, avatarValue)
                        .putBoolean(KEY_MAY_CREATE_FAMILY, mayCreateFamily)
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
                .putBoolean(KEY_MAY_CREATE_FAMILY, mayCreateFamily)
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

            enqueue(
                appContext,
                familyName,
                displayName,
                avatarValue,
                // Restored from the same durable state, so a retry after an outage
                // cannot create a family the person never asked for.
                mayCreateFamily = prefs.getBoolean(KEY_MAY_CREATE_FAMILY, false)
            )
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
        val mayCreateFamily = inputData.getBoolean(KEY_MAY_CREATE_FAMILY, false)

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
                existing == null && !mayCreateFamily -> {
                    // The phone belongs to no family and nobody asked for a new one.
                    // Leave it alone: the setup screen offers joining an existing
                    // family, and nothing here may decide that on the person's behalf.
                    Log.i(
                        TAG,
                        "No family membership and creating one was not requested; " +
                            "leaving setup to the person"
                    )
                    return@withContext Result.success()
                }

                existing == null -> {
                    val response = networkClient.bootstrapFamily(
                        FamilyBootstrapRequest(
                            familyName = familyName,
                            displayName = displayName,
                            role = "PARENT",
                            avatarKey = portableAvatar
                        )
                    )
                    if (response.code() == HTTP_CONFLICT &&
                        readErrorCode(response.errorBody()?.string()) == DEVICE_ALREADY_ONBOARDED
                    ) {
                        // The identity answer above can predate what the server knows,
                        // and the server refuses a second family for a phone that already
                        // has one. Retrying that refusal forever would leave the phone
                        // synchronising endlessly, so it is treated as the success it
                        // is: nothing was created, and a person must not see an error for
                        // a family that already exists.
                        Log.i(
                            TAG,
                            "Server reports this phone already belongs to a family; " +
                                "nothing was created"
                        )
                        OnboardingMemberData(
                            id = null,
                            familyId = null,
                            displayName = displayName,
                            role = "PARENT",
                            avatarKey = portableAvatar
                        )
                    } else {
                        response.body()
                            ?.takeIf { response.isSuccessful && it.success }
                            ?.member
                            ?: return@withContext Result.retry()
                    }
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
                    // Joining an existing person hydrates this phone from the family.
                    // Cached setup defaults are never an explicit profile edit.
                    OnboardingMemberData(
                        id = existing.member.id,
                        familyId = existing.familyId,
                        displayName = existing.member.displayName,
                        role = existing.member.role,
                        avatarKey = existing.member.avatarKey
                    )
                }
            }

            updateLocalIdentity(member, avatarValue,
                preferFamilyAvatar = existing?.binding?.memberBindingSource == "EXPLICIT")
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

    /**
     * The machine-readable half of a server refusal.
     *
     * The message is written for a person and can be reworded at any time; the
     * code is the part a client is allowed to decide on.
     */
    private fun readErrorCode(raw: String?): String? =
        runCatching { JSONObject(raw.orEmpty()).optString("code") }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private suspend fun updateLocalIdentity(
        member: OnboardingMemberData,
        localAvatarValue: String,
        preferFamilyAvatar: Boolean
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
                avatarUrl = if (preferFamilyAvatar) member.avatarKey else member.avatarKey
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
