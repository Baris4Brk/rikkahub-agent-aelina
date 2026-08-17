package me.rerere.rikkahub.assistant

import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import kotlin.uuid.Uuid

/**
 * Frozen identity of the authority epoch being destroyed.
 *
 * The fence is reconstructed from the durable `REVOKING` record on every process start.  It never
 * accepts a caller supplied subject string, so a retry cannot drift into a new epoch or Assistant.
 */
data class SecondUserLearningAuthorityRevocationFence(
    val assistantId: Uuid,
    val conversationId: Uuid,
    val authorityEpoch: Long,
    /** One wall-clock value for this replay turn; individual stores make it monotonic per row. */
    val frozenNowMs: Long,
) {
    init {
        require(authorityEpoch > 0L) { "Second-user revocation requires a positive epoch" }
        require(frozenNowMs >= 0L) { "Negative second-user revocation clock" }
    }

    val authoritySubjectId: String = SecondUserAdmissionSnapshot.subjectId(
        assistantId = assistantId,
        conversationId = conversationId,
        authorityEpoch = authorityEpoch,
    )

    /** Pre-epoch principal. It is drained once and can never be admitted for a new grant. */
    val legacyAuthoritySubjectId: String = "local_second_user:$assistantId:$conversationId"

    val exactAuthoritySubjectIds: List<String> =
        listOf(authoritySubjectId, legacyAuthoritySubjectId)

    fun ownsExactSubject(subjectId: String): Boolean = subjectId in exactAuthoritySubjectIds

    override fun toString(): String =
        "SecondUserLearningAuthorityRevocationFence(epoch=$authorityEpoch, ids=<redacted>)"
}

/** Immutable-key cursor. Mutating a grant's state/time cannot move it behind this cursor. */
data class SecondUserPolicyGrantRevocationCursor(
    val afterGrantId: String = "",
) {
    init {
        require(afterGrantId.length <= 256)
        require(afterGrantId.all(::isSafeGrantReferenceChar))
    }
}

data class SecondUserPolicyGrantRevocationPageRequest(
    val fence: SecondUserLearningAuthorityRevocationFence,
    val authoritySubjectId: String,
    val cursor: SecondUserPolicyGrantRevocationCursor =
        SecondUserPolicyGrantRevocationCursor(),
    val limit: Int = SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE,
) {
    init {
        require(fence.ownsExactSubject(authoritySubjectId)) {
            "Authority subject is outside the durable revocation fence"
        }
        require(limit in 1..SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE)
    }
}

data class SecondUserPolicyGrantRevocationPage(
    /** Exact post-transaction heads, including already-revoked replay rows. */
    val receipts: List<PolicyGrantAuthoritySnapshot>,
    val nextCursor: SecondUserPolicyGrantRevocationCursor?,
    val revokedInTransaction: Int,
) {
    init {
        require(receipts.size <= SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE)
        require(revokedInTransaction in 0..receipts.size)
        require(receipts.zipWithNext().all { (left, right) -> left.grantId < right.grantId })
        require((nextCursor == null) || nextCursor.afterGrantId == receipts.lastOrNull()?.grantId)
    }

    val endReached: Boolean get() = nextCursor == null
}

sealed interface SecondUserPolicyGrantRevocationPageResult {
    data class Ready(val page: SecondUserPolicyGrantRevocationPage) :
        SecondUserPolicyGrantRevocationPageResult

    /** Corruption, a lost CAS, or storage failure keeps the authority durably REVOKING. */
    data object Unavailable : SecondUserPolicyGrantRevocationPageResult
}

/** AppDatabase authority step: exact grants and their append-only receipts commit together. */
fun interface SecondUserPolicyGrantRevocationPort {
    suspend fun revokeExactPage(
        request: SecondUserPolicyGrantRevocationPageRequest,
    ): SecondUserPolicyGrantRevocationPageResult
}

data class SecondUserDerivedAuthorityInvalidationRequest(
    val fence: SecondUserLearningAuthorityRevocationFence,
    val authoritySubjectId: String,
    val limit: Int = SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE,
) {
    init {
        require(fence.ownsExactSubject(authoritySubjectId))
        require(limit in 1..SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE)
    }
}

data class SecondUserDerivedAuthorityInvalidationBatch(
    val policiesMadeStale: Int,
    val workflowCandidatesMadeStale: Int,
    /** True only after a bounded post-write query proves no transitionable exact row remains. */
    val complete: Boolean,
) {
    init {
        require(policiesMadeStale in 0..SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE)
        require(workflowCandidatesMadeStale in 0..SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE)
    }
}

sealed interface SecondUserDerivedAuthorityInvalidationResult {
    data class Ready(val batch: SecondUserDerivedAuthorityInvalidationBatch) :
        SecondUserDerivedAuthorityInvalidationResult

    /** A disabled/unopened derived runtime is pending, not success. */
    data object Unavailable : SecondUserDerivedAuthorityInvalidationResult
}

/** LearningDatabase step: exact Policies and workflow candidates become fail-closed with audit. */
fun interface SecondUserDerivedAuthorityInvalidationPort {
    suspend fun invalidateExactAuthorityBatch(
        request: SecondUserDerivedAuthorityInvalidationRequest,
    ): SecondUserDerivedAuthorityInvalidationResult
}

data class SecondUserLearningAuthorityRevocationSummary(
    val scannedGrantHeads: Int,
    val revokedGrantHeads: Int,
    val policiesMadeStale: Int,
    val workflowCandidatesMadeStale: Int,
)

sealed interface SecondUserLearningAuthorityRevocationResult {
    data class Completed(val summary: SecondUserLearningAuthorityRevocationSummary) :
        SecondUserLearningAuthorityRevocationResult

    data object Pending : SecondUserLearningAuthorityRevocationResult
}

/**
 * Replayable two-database saga. AppDatabase authority is removed before derived rows are touched;
 * an already-revoked head remains pageable so a crash between the two stores resumes projection.
 */
class SecondUserLearningAuthorityRevocationSaga(
    private val grants: SecondUserPolicyGrantRevocationPort,
    private val derived: SecondUserDerivedAuthorityInvalidationPort,
) {
    suspend fun resume(
        fence: SecondUserLearningAuthorityRevocationFence,
    ): SecondUserLearningAuthorityRevocationResult {
        var scannedGrantHeads = 0
        var revokedGrantHeads = 0
        var policiesMadeStale = 0
        var workflowCandidatesMadeStale = 0

        for (subjectId in fence.exactAuthoritySubjectIds) {
            var cursor = SecondUserPolicyGrantRevocationCursor()
            do {
                val result = grants.revokeExactPage(
                    SecondUserPolicyGrantRevocationPageRequest(
                        fence = fence,
                        authoritySubjectId = subjectId,
                        cursor = cursor,
                    ),
                )
                val page = (result as? SecondUserPolicyGrantRevocationPageResult.Ready)?.page
                    ?: return SecondUserLearningAuthorityRevocationResult.Pending
                if (page.receipts.any { receipt ->
                        receipt.scope.storageId != subjectId ||
                            receipt.consumingAssistantId != fence.assistantId
                    }
                ) {
                    return SecondUserLearningAuthorityRevocationResult.Pending
                }
                scannedGrantHeads += page.receipts.size
                revokedGrantHeads += page.revokedInTransaction
                cursor = page.nextCursor ?: break
            } while (true)

            do {
                val result = derived.invalidateExactAuthorityBatch(
                    SecondUserDerivedAuthorityInvalidationRequest(
                        fence = fence,
                        authoritySubjectId = subjectId,
                    ),
                )
                val batch = (result as? SecondUserDerivedAuthorityInvalidationResult.Ready)?.batch
                    ?: return SecondUserLearningAuthorityRevocationResult.Pending
                policiesMadeStale += batch.policiesMadeStale
                workflowCandidatesMadeStale += batch.workflowCandidatesMadeStale
                if (batch.complete) break
                // A non-complete batch must make progress. This rejects a broken adapter instead
                // of spinning forever inside startup recovery.
                if (batch.policiesMadeStale == 0 && batch.workflowCandidatesMadeStale == 0) {
                    return SecondUserLearningAuthorityRevocationResult.Pending
                }
            } while (true)
        }

        return SecondUserLearningAuthorityRevocationResult.Completed(
            SecondUserLearningAuthorityRevocationSummary(
                scannedGrantHeads = scannedGrantHeads,
                revokedGrantHeads = revokedGrantHeads,
                policiesMadeStale = policiesMadeStale,
                workflowCandidatesMadeStale = workflowCandidatesMadeStale,
            ),
        )
    }
}

private fun isSafeGrantReferenceChar(char: Char): Boolean =
    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
        char == '-' || char == '_' || char == '.' || char == ':' || char == '@'

const val SECOND_USER_AUTHORITY_REVOCATION_PAGE_SIZE: Int = 64

