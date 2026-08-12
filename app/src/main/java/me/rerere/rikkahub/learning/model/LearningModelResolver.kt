package me.rerere.rikkahub.learning.model

import me.rerere.rikkahub.learning.resources.LearningCancellationCapability
import me.rerere.rikkahub.learning.resources.LearningExecutionClass
import me.rerere.rikkahub.learning.resources.LearningRouteCapabilities

enum class LearningProviderKind {
    LOCAL_LITERT,
    REMOTE,
    AICORE,
}

/** Content-free candidate produced only by the Settings/provider adapter. */
data class LearningModelCandidate(
    val providerKind: LearningProviderKind,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    val configurationDigest: String,
    val userExplicitlyAuthorizedForBackground: Boolean,
) {
    override fun toString(): String =
        "LearningModelCandidate(providerKind=$providerKind, backgroundAuthorized=" +
            "$userExplicitlyAuthorizedForBackground, identity=<redacted>)"
}

data class LearningModelResolutionPolicy(
    val allowRemoteReflection: Boolean = false,
    /** Exact host-owned provider identities; one safe adapter must not bless every REMOTE route. */
    val providerIdentityDigestsWithProvenCancellation: Set<String> = emptySet(),
) {
    init {
        require(providerIdentityDigestsWithProvenCancellation.all { it.isSha256() }) {
            "Invalid proven-cancellation provider identity"
        }
    }
}

data class ResolvedLearningModel(
    val providerKind: LearningProviderKind,
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    val configurationDigest: String,
    val route: LearningRouteCapabilities,
) {
    override fun toString(): String =
        "ResolvedLearningModel(providerKind=$providerKind, route=$route, identity=<redacted>)"
}

enum class LearningModelResolutionFailure {
    NO_CONFIGURATION,
    BACKGROUND_NOT_AUTHORIZED,
    REMOTE_REFLECTION_DISABLED,
    AICORE_EXCLUDED,
    CANCELLATION_UNSAFE,
    INVALID_IDENTITY,
}

sealed interface LearningModelResolution {
    data class Resolved(val model: ResolvedLearningModel) : LearningModelResolution

    data class Unavailable(val reason: LearningModelResolutionFailure) : LearningModelResolution
}

/**
 * Pure fail-closed policy. A production two-stage Settings adapter freezes these digests at claim
 * and binds the exact ProviderSetting/Model again immediately before execution.
 */
object LearningModelResolver {
    fun resolve(
        candidate: LearningModelCandidate?,
        policy: LearningModelResolutionPolicy,
    ): LearningModelResolution {
        candidate ?: return LearningModelResolution.Unavailable(
            LearningModelResolutionFailure.NO_CONFIGURATION,
        )
        if (!candidate.userExplicitlyAuthorizedForBackground) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.BACKGROUND_NOT_AUTHORIZED,
            )
        }
        if (candidate.providerKind == LearningProviderKind.AICORE) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.AICORE_EXCLUDED)
        }
        if (
            candidate.providerKind == LearningProviderKind.REMOTE &&
            !policy.allowRemoteReflection
        ) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.REMOTE_REFLECTION_DISABLED,
            )
        }
        if (
            !candidate.providerIdentityDigest.isSha256() ||
            !candidate.modelIdentityDigest.isSha256() ||
            !candidate.configurationDigest.isSha256()
        ) {
            return LearningModelResolution.Unavailable(LearningModelResolutionFailure.INVALID_IDENTITY)
        }
        if (
            candidate.providerIdentityDigest !in
            policy.providerIdentityDigestsWithProvenCancellation
        ) {
            return LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            )
        }
        val remote = candidate.providerKind == LearningProviderKind.REMOTE
        return LearningModelResolution.Resolved(
            ResolvedLearningModel(
                providerKind = candidate.providerKind,
                providerIdentityDigest = candidate.providerIdentityDigest,
                modelIdentityDigest = candidate.modelIdentityDigest,
                configurationDigest = candidate.configurationDigest,
                route = LearningRouteCapabilities(
                    executionClass = if (remote) {
                        LearningExecutionClass.REMOTE_NETWORK
                    } else {
                        LearningExecutionClass.LOCAL_COMPUTE
                    },
                    requiresNetwork = remote,
                    cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
                ),
            ),
        )
    }
}

private fun String.isSha256(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
