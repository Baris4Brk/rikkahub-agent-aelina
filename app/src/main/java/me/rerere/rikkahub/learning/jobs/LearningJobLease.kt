package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.data.ai.background.BackgroundProviderAttemptAuthority
import me.rerere.rikkahub.learning.storage.LearningJobEntity

/**
 * An opaque capability proving ownership of one concrete Learning job attempt.
 *
 * There is intentionally no public constructor, mutable field, component function, or `copy`.
 * Only a successful database claim can create the private implementation accepted by the store.
 */
sealed interface LearningJobLease {
    val jobId: String

    val leaseUntilMs: Long

    /** Present only when this lease atomically reserved one durable provider attempt. */
    val providerAttemptAuthority: BackgroundProviderAttemptAuthority?

    /** Content-free receipt for the exact manifest admitted with this lease. */
    val providerManifestReceipt: LearningProviderManifestReceipt?
}

sealed interface LearningJobClaimResult {
    data class Claimed(
        val job: LearningJobEntity,
        val lease: LearningJobLease,
    ) : LearningJobClaimResult {
        val providerAttemptAuthority: BackgroundProviderAttemptAuthority?
            get() = lease.providerAttemptAuthority

        val providerManifestReceipt: LearningProviderManifestReceipt?
            get() = lease.providerManifestReceipt
    }

    data object NoWork : LearningJobClaimResult

    data object Contended : LearningJobClaimResult

    data class ClockRollback(val jobId: String) : LearningJobClaimResult
}

sealed interface LearningJobStartupRecoveryResult {
    data class Recovered(
        val otherProcessSessions: Int,
        val expiredLeases: Int,
        val exhaustedAttempts: Int,
        val orphanReservationsReleased: Int = 0,
        val orphanDispatchesIndeterminate: Int = 0,
        val providerJobsDeadLettered: Int = 0,
        val missingManifestJobsDeadLettered: Int = 0,
        val requeuedMandatoryInvalidations: Int = 0,
    ) : LearningJobStartupRecoveryResult {
        init {
            require(
                listOf(
                    otherProcessSessions,
                    expiredLeases,
                    exhaustedAttempts,
                    orphanReservationsReleased,
                    orphanDispatchesIndeterminate,
                    providerJobsDeadLettered,
                    missingManifestJobsDeadLettered,
                    requeuedMandatoryInvalidations,
                ).all { it >= 0 },
            )
        }
    }

    data class ClockRollback(val jobId: String) : LearningJobStartupRecoveryResult
}

class LearningLostLeaseException : IllegalStateException("Learning job lease was lost")

class LearningJobClockRollbackException :
    IllegalStateException("Learning job authority clock moved backwards")

class LearningJobInvariantException :
    IllegalStateException("Learning job storage invariant failed")

enum class LearningJobAttemptFailureResult {
    RETRIED,
    DEAD_LETTERED,
}

/**
 * Immutable, content-free proof of the exact provider manifest/cohort admitted at claim time.
 * The constructor is storage-owned; handlers can inspect caps and audit identities but receive no
 * Room entity, DAO, endpoint, credential, prompt, or provider configuration.
 */
class LearningProviderManifestReceipt internal constructor(
    val cohortId: String,
    val providerKind: String,
    val providerIdentitySha256: String,
    val modelIdentitySha256: String,
    val configurationIdentitySha256: String,
    val configurationGeneration: Long,
    val manifestSchemaVersion: Int,
    val requestHmacSha256: String,
    val inputIdentitySha256: String,
    /**
     * Storage-compatible column value. LOCAL stores the runtime/artifact attestation; REMOTE
     * stores the exact official dispatch-transport/request attestation.
     */
    val runtimeAttestationSha256: String,
    val redactionPolicyIdentity: String,
    val fieldCategoriesIdentity: String,
    val tokenEstimatorIdentity: String,
    val providerRequestKey: String,
    val inputUtf8Bytes: Long,
    val maxInputUtf8Bytes: Long,
    val estimatedInputTokens: Long,
    val maxOutputTokens: Long,
    val maxOutputUtf8Bytes: Long,
    val maxProviderCalls: Int,
    val maxCostMicros: Long,
    val timeoutMs: Long,
    val frozenAtMs: Long,
) {
    val dispatchAttestationSha256: String
        get() = runtimeAttestationSha256

    override fun toString(): String =
        "LearningProviderManifestReceipt(kind=$providerKind, generation=$configurationGeneration, " +
            "schema=$manifestSchemaVersion, inputBytes=$inputUtf8Bytes/$maxInputUtf8Bytes, " +
            "maxOutputTokens=$maxOutputTokens, maxCalls=$maxProviderCalls, identities=<redacted>)"
}

internal fun String.isSafeLeaseIdentifier(): Boolean =
    length in 1..192 && all { char ->
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == ':' ||
            char == '.'
    }
