package me.rerere.rikkahub.workflow.repository

import androidx.room.withTransaction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowResetReceipt
import me.rerere.rikkahub.learning.privacy.DurableScopeLearnedWorkflowEraseReceipt
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowEraseBatchReceipt
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import me.rerere.rikkahub.workflow.model.WorkflowToolSchemaSnapshot
import kotlin.uuid.Uuid

/**
 * Permanent, content-free AppDatabase fence for Learning exact-scope erase.
 *
 * A missing workflow receives a claim tombstone so a promotion that already read its
 * LearningDatabase candidate cannot insert after erase. An existing exact LEARNED workflow is
 * redacted in place. Both forms deliberately fail the promotion repository's exact-duplicate
 * predicate and therefore replay as a collision, never as a successful promotion.
 */
class AppDatabaseExactScopeLearnedWorkflowErasePort(
    private val database: AppDatabase,
) : ExactScopeLearnedWorkflowErasePort, DurableLearnedWorkflowPrivacyPort {
    override suspend fun redactAndFence(
        candidateIds: List<String>,
        frozenNowMs: Long,
    ): ExactScopeLearnedWorkflowEraseBatchReceipt {
        require(frozenNowMs >= 0L)
        require(candidateIds.size in 1..MAX_ERASE_BATCH_SIZE)
        require(candidateIds == candidateIds.distinct().sorted())
        require(candidateIds.all(LearnedWorkflowEraseTombstoneContract::isCandidateId))

        return database.withTransaction {
            val workflows = database.workflowDao()
            val runs = database.workflowRunDao()
            var redactedDefinitions = 0
            var insertedClaims = 0

            candidateIds.forEach { candidateId ->
                val workflowId = LearnedWorkflowEraseTombstoneContract.workflowId(candidateId)
                val existing = workflows.getById(workflowId)
                when (LearnedWorkflowEraseTombstoneContract.classify(candidateId, existing)) {
                    LearnedWorkflowEraseTombstoneContract.RowClass.CONFLICT ->
                        throw ExactScopeLearnedWorkflowEraseConflictException()

                    LearnedWorkflowEraseTombstoneContract.RowClass.ABSENT -> {
                        workflows.insert(
                            LearnedWorkflowEraseTombstoneContract.claimEntity(
                                candidateId = candidateId,
                                frozenNowMs = frozenNowMs,
                            ),
                        )
                        insertedClaims = Math.addExact(insertedClaims, 1)
                    }

                    LearnedWorkflowEraseTombstoneContract.RowClass.LIVE_DEFINITION -> {
                        checkNotNull(existing)
                        val affected = workflows.redactExactLearnedForScopeErase(
                            id = workflowId,
                            candidateId = candidateId,
                            expectedStateVersion = existing.stateVersion,
                            name = LearnedWorkflowEraseTombstoneContract.REDACTED_NAME,
                            definitionJson = LearnedWorkflowEraseTombstoneContract.REDACTED_DEFINITION_JSON,
                            staleReason = LearnedWorkflowEraseTombstoneContract.DEFINITION_TOMBSTONE_REASON,
                            updatedAtMs = frozenNowMs,
                        )
                        if (affected != 1) {
                            throw ExactScopeLearnedWorkflowEraseConflictException()
                        }
                        redactedDefinitions = Math.addExact(redactedDefinitions, 1)
                    }

                    LearnedWorkflowEraseTombstoneContract.RowClass.DEFINITION_TOMBSTONE ->
                        redactedDefinitions = Math.addExact(redactedDefinitions, 1)

                    LearnedWorkflowEraseTombstoneContract.RowClass.CLAIM_TOMBSTONE ->
                        insertedClaims = Math.addExact(insertedClaims, 1)
                }

                // Run rows can retain errors and timing even when the definition was already
                // redacted by a crashed attempt. Always clear them on every replay.
                runs.deleteAllFor(workflowId)
                val fenced = workflows.getById(workflowId)
                if (!LearnedWorkflowEraseTombstoneContract.isExactTombstone(candidateId, fenced)) {
                    throw ExactScopeLearnedWorkflowEraseConflictException()
                }
            }

            ExactScopeLearnedWorkflowEraseBatchReceipt(
                fencedCandidateIds = candidateIds.size,
                redactedWorkflowDefinitions = redactedDefinitions,
                insertedFenceClaims = insertedClaims,
            )
        }
    }

    override suspend fun redactExactScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): DurableScopeLearnedWorkflowEraseReceipt {
        require(frozenNowMs >= 0L)
        var afterIdExclusive = ""
        var scanned = 0
        var exact = 0
        var unknown = 0
        var batches = 0
        while (true) {
            val page = database.withTransaction {
                val workflows = database.workflowDao()
                val runs = database.workflowRunDao()
                val rows = workflows.listLiveLearnedPrivacyPage(
                    afterIdExclusive = afterIdExclusive,
                    limit = MAX_ERASE_BATCH_SIZE,
                )
                check(rows.size <= MAX_ERASE_BATCH_SIZE)
                check(rows.map(WorkflowEntity::id) == rows.map(WorkflowEntity::id).sorted())
                var pageExact = 0
                var pageUnknown = 0
                rows.forEach { row ->
                    when (row.scopeDisposition(scope)) {
                        DurableScopeDisposition.OTHER -> Unit
                        DurableScopeDisposition.EXACT -> {
                            redactPrivacyRow(workflows, runs, row, frozenNowMs)
                            pageExact = Math.addExact(pageExact, 1)
                        }
                        DurableScopeDisposition.UNKNOWN -> {
                            // Exact provenance is unavailable. Retaining the body could retain the
                            // requested scope, so privacy takes the conservative direction.
                            redactPrivacyRow(workflows, runs, row, frozenNowMs)
                            pageUnknown = Math.addExact(pageUnknown, 1)
                        }
                    }
                }
                DurableScopePageReceipt(
                    lastId = rows.lastOrNull()?.id,
                    scanned = rows.size,
                    exact = pageExact,
                    unknown = pageUnknown,
                )
            }
            if (page.lastId == null) break
            check(page.lastId > afterIdExclusive)
            scanned = Math.addExact(scanned, page.scanned)
            exact = Math.addExact(exact, page.exact)
            unknown = Math.addExact(unknown, page.unknown)
            batches = Math.addExact(batches, 1)
            afterIdExclusive = page.lastId
            if (page.scanned < MAX_ERASE_BATCH_SIZE) break
        }
        return DurableScopeLearnedWorkflowEraseReceipt(
            scannedLearnedDefinitions = scanned,
            redactedExactScopeDefinitions = exact,
            redactedUnknownScopeDefinitions = unknown,
            committedBatches = batches,
        )
    }

    override suspend fun redactAllForDerivedReset(
        frozenNowMs: Long,
    ): DurableLearnedWorkflowResetReceipt {
        require(frozenNowMs >= 0L)
        var redacted = 0
        var batches = 0
        while (true) {
            val affected = database.withTransaction {
                val workflows = database.workflowDao()
                val runs = database.workflowRunDao()
                // Always restart at the head. Successfully redacted rows leave this quarantine
                // query only after their complete sanitized shape is verified, which also closes
                // insertion-behind-a-cursor gaps during replay.
                val rows = workflows.listLiveLearnedPrivacyPage(
                    afterIdExclusive = "",
                    limit = MAX_ERASE_BATCH_SIZE,
                )
                rows.forEach { row ->
                    redactPrivacyRow(workflows, runs, row, frozenNowMs)
                }
                rows.size
            }
            if (affected == 0) break
            redacted = Math.addExact(redacted, affected)
            batches = Math.addExact(batches, 1)
        }
        return DurableLearnedWorkflowResetReceipt(
            redactedLearnedDefinitions = redacted,
            committedBatches = batches,
            complete = true,
        )
    }

    private suspend fun redactPrivacyRow(
        workflows: me.rerere.rikkahub.workflow.db.WorkflowDao,
        runs: me.rerere.rikkahub.workflow.db.WorkflowRunDao,
        row: WorkflowEntity,
        frozenNowMs: Long,
    ) {
        val affected = workflows.redactLearnedForPrivacyQuarantineCas(
            id = row.id,
            expectedStateVersion = row.stateVersion,
            name = LearnedWorkflowEraseTombstoneContract.REDACTED_NAME,
            definitionJson = LearnedWorkflowEraseTombstoneContract.REDACTED_DEFINITION_JSON,
            staleReason = LearnedWorkflowEraseTombstoneContract.DEFINITION_TOMBSTONE_REASON,
            updatedAtMs = frozenNowMs,
        )
        if (affected != 1) throw ExactScopeLearnedWorkflowEraseConflictException()
        runs.deleteAllFor(row.id)
        val tombstone = workflows.getById(row.id)
        if (!LearnedWorkflowEraseTombstoneContract.isSanitizedDefinitionTombstone(tombstone)) {
            throw ExactScopeLearnedWorkflowEraseConflictException()
        }
    }
}

private data class DurableScopePageReceipt(
    val lastId: String?,
    val scanned: Int,
    val exact: Int,
    val unknown: Int,
)

private enum class DurableScopeDisposition { EXACT, OTHER, UNKNOWN }

private fun WorkflowEntity.scopeDisposition(scope: LearningScope): DurableScopeDisposition {
    if (origin != WorkflowOrigin.LEARNED.name || stateVersion <= 0L) {
        return DurableScopeDisposition.UNKNOWN
    }
    val stored = WorkflowJson.parseStoredWithCompatibility(definitionJson)
        ?: return DurableScopeDisposition.UNKNOWN
    val definition = stored.definition
    if (stored.learnedScopeStorage != WorkflowJson.LearnedScopeStorage.PERSISTED ||
        definition.origin != WorkflowOrigin.LEARNED || definition.id != id ||
        definition.enabled != enabled || definition.sourceCandidateId != sourceCandidateId ||
        definition.sourceArtifactHash != sourceArtifactHash || definition.grantDigest != grantDigest ||
        definition.authoringAssistantId != authoringAssistantId ||
        JsonArray(definition.capabilitySnapshot.toSortedSet().map(::JsonPrimitive)).toString() !=
        capabilitySnapshotJson ||
        WorkflowToolSchemaSnapshot.canonicalProjection(definition.actions) !=
        toolSchemaFingerprintsJson
    ) {
        return DurableScopeDisposition.UNKNOWN
    }
    val assistantId = definition.authoringAssistantId
        ?: return DurableScopeDisposition.UNKNOWN
    val assistantScope = runCatching {
        val uuid = Uuid.parse(assistantId)
        if (uuid.toString() != assistantId) error("non_canonical_assistant")
        LearningScope.Assistant(uuid)
    }.getOrNull() ?: return DurableScopeDisposition.UNKNOWN
    val exact = when (scope) {
        is LearningScope.Assistant ->
            definition.authoritySubjectId == null && assistantScope == scope
        is LearningScope.AuthoritySubject ->
            definition.authoritySubjectId == scope.authoritySubjectId
    }
    return if (exact) DurableScopeDisposition.EXACT else DurableScopeDisposition.OTHER
}

/** Stable failure without candidate/workflow identifiers in the message. */
class ExactScopeLearnedWorkflowEraseConflictException internal constructor() :
    IllegalStateException("exact_scope_learned_workflow_erase_conflict")

/** Shared decision/marker contract; kept pure so replay and authority boundaries are unit-testable. */
internal object LearnedWorkflowEraseTombstoneContract {
    const val DEFINITION_TOMBSTONE_REASON = "learning_scope_erased_definition_v1"
    const val CLAIM_TOMBSTONE_REASON = "learning_scope_erased_claim_v1"
    const val REDACTED_NAME = "Erased learned workflow"
    const val REDACTED_DEFINITION_JSON = "{}"

    enum class RowClass {
        ABSENT,
        LIVE_DEFINITION,
        DEFINITION_TOMBSTONE,
        CLAIM_TOMBSTONE,
        CONFLICT,
    }

    fun isCandidateId(candidateId: String): Boolean {
        if (!candidateId.startsWith(CANDIDATE_ID_PREFIX)) return false
        val digest = candidateId.removePrefix(CANDIDATE_ID_PREFIX)
        return digest.length == SHA_256_HEX_LENGTH && digest.all { it in '0'..'9' || it in 'a'..'f' }
    }

    fun workflowId(candidateId: String): String = "learned:$candidateId"

    fun classify(candidateId: String, row: WorkflowEntity?): RowClass {
        if (!isCandidateId(candidateId)) return RowClass.CONFLICT
        if (row == null) return RowClass.ABSENT
        if (row.id != workflowId(candidateId) ||
            row.origin != WorkflowOrigin.LEARNED.name ||
            row.sourceCandidateId != candidateId ||
            row.stateVersion <= 0L
        ) return RowClass.CONFLICT

        return when (row.staleReason) {
            DEFINITION_TOMBSTONE_REASON -> if (isSanitized(row)) {
                RowClass.DEFINITION_TOMBSTONE
            } else RowClass.CONFLICT

            CLAIM_TOMBSTONE_REASON -> if (isSanitized(row)) {
                RowClass.CLAIM_TOMBSTONE
            } else RowClass.CONFLICT

            else -> RowClass.LIVE_DEFINITION
        }
    }

    fun isExactTombstone(candidateId: String, row: WorkflowEntity?): Boolean =
        classify(candidateId, row) in setOf(RowClass.DEFINITION_TOMBSTONE, RowClass.CLAIM_TOMBSTONE)

    fun isSanitizedDefinitionTombstone(row: WorkflowEntity?): Boolean =
        row != null && row.origin == WorkflowOrigin.LEARNED.name &&
            row.staleReason == DEFINITION_TOMBSTONE_REASON && isSanitized(row)

    fun claimEntity(candidateId: String, frozenNowMs: Long): WorkflowEntity {
        require(isCandidateId(candidateId))
        require(frozenNowMs >= 0L)
        return WorkflowEntity(
            id = workflowId(candidateId),
            name = REDACTED_NAME,
            description = null,
            enabled = false,
            definitionJson = REDACTED_DEFINITION_JSON,
            createdAtMs = 0L,
            updatedAtMs = frozenNowMs,
            lastRunAtMs = null,
            lastRunStatus = null,
            lastRunError = null,
            runsTodayCount = 0,
            runsTodayDate = "",
            stateVersion = 1L,
            origin = WorkflowOrigin.LEARNED.name,
            sourceCandidateId = candidateId,
            sourceArtifactHash = null,
            grantDigest = null,
            authoringAssistantId = null,
            capabilitySnapshotJson = "[]",
            toolSchemaFingerprintsJson = "[]",
            staleReason = CLAIM_TOMBSTONE_REASON,
        )
    }

    private fun isSanitized(row: WorkflowEntity): Boolean =
        !row.enabled &&
            row.name == REDACTED_NAME &&
            row.description == null &&
            row.definitionJson == REDACTED_DEFINITION_JSON &&
            row.createdAtMs == 0L &&
            row.updatedAtMs >= 0L &&
            row.lastRunAtMs == null &&
            row.lastRunStatus == null &&
            row.lastRunError == null &&
            row.runsTodayCount == 0 &&
            row.runsTodayDate.isEmpty() &&
            row.sourceArtifactHash == null &&
            row.grantDigest == null &&
            row.authoringAssistantId == null &&
            row.capabilitySnapshotJson == "[]" &&
            row.toolSchemaFingerprintsJson == "[]"

    private const val CANDIDATE_ID_PREFIX = "workflow-candidate-v1:"
    private const val SHA_256_HEX_LENGTH = 64
}

private const val MAX_ERASE_BATCH_SIZE = 128
