package me.rerere.rikkahub.learning.privacy

import me.rerere.rikkahub.learning.model.LearningScope

/**
 * Main-database half of exact-scope Learning erase.
 *
 * [candidateIds] are collected from one exact LearningDatabase scope in bounded pages. The
 * implementation must atomically make every corresponding LEARNED workflow permanently
 * non-executable before the LearningDatabase candidate rows may be deleted. USER workflows are an
 * authority conflict, never an erase target.
 */
fun interface ExactScopeLearnedWorkflowErasePort {
    suspend fun redactAndFence(
        candidateIds: List<String>,
        frozenNowMs: Long,
    ): ExactScopeLearnedWorkflowEraseBatchReceipt
}

/**
 * AppDatabase-owned provenance scan used when the rebuildable candidate table is incomplete or
 * gone. Implementations must operate in bounded transactions, preserve USER workflows, and
 * conservatively redact malformed LEARNED rows whose exact scope cannot be proven.
 */
interface DurableLearnedWorkflowPrivacyPort {
    suspend fun redactExactScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): DurableScopeLearnedWorkflowEraseReceipt

    /**
     * Global pre-reset/restore quarantine. Once candidate roots are about to disappear, every
     * remaining LEARNED definition is orphaned; all must become permanent non-executable
     * tombstones before the LearningDatabase delete can commit.
     */
    suspend fun redactAllForDerivedReset(
        frozenNowMs: Long,
    ): DurableLearnedWorkflowResetReceipt
}

/** Content-free bounded-scan receipt. No workflow, assistant, or authority identifiers escape. */
data class DurableScopeLearnedWorkflowEraseReceipt(
    val scannedLearnedDefinitions: Int,
    val redactedExactScopeDefinitions: Int,
    val redactedUnknownScopeDefinitions: Int,
    val committedBatches: Int,
) {
    init {
        require(scannedLearnedDefinitions >= 0)
        require(redactedExactScopeDefinitions >= 0)
        require(redactedUnknownScopeDefinitions >= 0)
        require(committedBatches >= 0)
        require(
            redactedExactScopeDefinitions + redactedUnknownScopeDefinitions <=
                scannedLearnedDefinitions,
        )
    }

    val redactedDefinitions: Int
        get() = Math.addExact(redactedExactScopeDefinitions, redactedUnknownScopeDefinitions)
}

/** Content-free global reset receipt. */
data class DurableLearnedWorkflowResetReceipt(
    val redactedLearnedDefinitions: Int,
    val committedBatches: Int,
    /** True only after a final bounded query observed zero live LEARNED definitions. */
    val complete: Boolean,
) {
    init {
        require(redactedLearnedDefinitions >= 0)
        require(committedBatches >= 0)
    }
}

/** Content-free, replay-stable counts for one bounded AppDatabase transaction. */
data class ExactScopeLearnedWorkflowEraseBatchReceipt(
    val fencedCandidateIds: Int,
    val redactedWorkflowDefinitions: Int,
    val insertedFenceClaims: Int,
) {
    init {
        require(fencedCandidateIds >= 0)
        require(redactedWorkflowDefinitions >= 0)
        require(insertedFenceClaims >= 0)
        require(redactedWorkflowDefinitions + insertedFenceClaims == fencedCandidateIds)
    }
}

fun interface ExactScopeLearnedWorkflowCandidatePageSource {
    suspend fun listCandidateIds(
        scope: LearningScope,
        afterIdExclusive: String,
        limit: Int,
    ): List<String>
}

data class ExactScopeLearnedWorkflowEraseSagaReceipt(
    val fencedCandidateIds: Int,
    val redactedWorkflowDefinitions: Int,
    val insertedFenceClaims: Int,
) {
    init {
        require(fencedCandidateIds >= 0)
        require(redactedWorkflowDefinitions >= 0)
        require(insertedFenceClaims >= 0)
        require(redactedWorkflowDefinitions + insertedFenceClaims == fencedCandidateIds)
    }
}

/**
 * Bounded, crash-replayable cross-database pre-delete phase. It never mutates LearningDatabase;
 * callers may delete candidate authority rows only after this method returns successfully.
 */
class ExactScopeLearnedWorkflowEraseSaga(
    private val candidates: ExactScopeLearnedWorkflowCandidatePageSource,
    private val workflows: ExactScopeLearnedWorkflowErasePort,
    private val batchSize: Int = MAX_EXACT_SCOPE_WORKFLOW_ERASE_BATCH,
) {
    init {
        require(batchSize in 1..MAX_EXACT_SCOPE_WORKFLOW_ERASE_BATCH)
    }

    suspend fun fenceBeforeLearningDelete(
        scope: LearningScope,
        frozenNowMs: Long,
    ): ExactScopeLearnedWorkflowEraseSagaReceipt {
        require(frozenNowMs >= 0L)
        var afterIdExclusive = ""
        var fenced = 0
        var redacted = 0
        var claims = 0
        while (true) {
            val ids = candidates.listCandidateIds(scope, afterIdExclusive, batchSize)
            check(ids.size <= batchSize)
            check(ids == ids.distinct().sorted())
            if (ids.isEmpty()) break
            check(ids.first() > afterIdExclusive)

            // Advance only after the complete AppDatabase transaction succeeds. Exceptions leave
            // the cursor and all LearningDatabase authority rows available for a later replay.
            val receipt = workflows.redactAndFence(ids, frozenNowMs)
            check(receipt.fencedCandidateIds == ids.size)
            fenced = Math.addExact(fenced, receipt.fencedCandidateIds)
            redacted = Math.addExact(redacted, receipt.redactedWorkflowDefinitions)
            claims = Math.addExact(claims, receipt.insertedFenceClaims)
            afterIdExclusive = ids.last()
            if (ids.size < batchSize) break
        }
        return ExactScopeLearnedWorkflowEraseSagaReceipt(fenced, redacted, claims)
    }
}

const val MAX_EXACT_SCOPE_WORKFLOW_ERASE_BATCH = 128
