package ru.childwatch.shared.family

/** Identifies where the concrete target in a resolved snapshot came from. */
enum class FeatureTargetOrigin {
    ACTIVE_SELECTION,
    EXPLICIT_REQUEST
}

/** Closed set of reasons for which a targeted feature must not proceed. */
enum class FeatureTargetFailure {
    ACTIVE_CONTEXT_MISSING,
    TARGET_DEVICE_MISSING,
    TARGET_IS_SELF
}

sealed interface FeatureTargetResult {
    data class Resolved(
        val context: FeatureContext,
        val origin: FeatureTargetOrigin
    ) : FeatureTargetResult {
        /** Non-null target guaranteed by successful fail-closed resolution. */
        val targetDeviceId: String = context.targetDeviceId
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Resolved feature target must not be blank")
    }

    data class Rejected(
        val reason: FeatureTargetFailure
    ) : FeatureTargetResult
}

/**
 * Resolves one immutable, concrete feature target without reading or writing a
 * store. An explicit target is applied only to a derived snapshot, so one-off
 * actions cannot change the globally selected member/device.
 *
 * Passing `null` as [explicitTargetDeviceId] uses the active selection.
 * Passing a non-null but blank value is an explicit invalid request and fails
 * closed instead of falling back to the active selection.
 */
class FeatureTargetResolver {
    fun resolve(
        activeContext: ActiveContext?,
        ownerScope: String,
        feature: String,
        explicitTargetDeviceId: String? = null,
        explicitFocusedMemberId: String? = null
    ): FeatureTargetResult {
        val context = activeContext
            ?: return FeatureTargetResult.Rejected(FeatureTargetFailure.ACTIVE_CONTEXT_MISSING)

        val hasExplicitTarget = explicitTargetDeviceId != null
        val selectedTargetDeviceId = context.targetDeviceId.normalizedOrNull()
        val targetDeviceId = if (hasExplicitTarget) {
            explicitTargetDeviceId.normalizedOrNull()
        } else {
            selectedTargetDeviceId
        } ?: return FeatureTargetResult.Rejected(FeatureTargetFailure.TARGET_DEVICE_MISSING)

        val focusedMemberId = if (hasExplicitTarget) {
            explicitFocusedMemberId.normalizedOrNull()
                ?: context.focusedMemberId.normalizedOrNull()
                    ?.takeIf { targetDeviceId == selectedTargetDeviceId }
        } else {
            context.focusedMemberId.normalizedOrNull()
        }

        val targetsSelfDevice = targetDeviceId == context.selfDeviceId.trim()
        val targetsSelfMember = focusedMemberId != null &&
            focusedMemberId == context.selfMemberId.normalizedOrNull()
        if (targetsSelfDevice || targetsSelfMember) {
            return FeatureTargetResult.Rejected(FeatureTargetFailure.TARGET_IS_SELF)
        }

        val snapshotContext = context.copy(
            focusedMemberId = focusedMemberId,
            targetDeviceId = targetDeviceId
        )
        return FeatureTargetResult.Resolved(
            context = snapshotContext.forFeature(ownerScope, feature),
            origin = if (hasExplicitTarget) {
                FeatureTargetOrigin.EXPLICIT_REQUEST
            } else {
                FeatureTargetOrigin.ACTIVE_SELECTION
            }
        )
    }
}
