package me.rerere.rikkahub.workflow.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.workflow.db.WorkflowDao
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunDao
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.model.WorkflowConstants
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.model.WorkflowToolSchemaSnapshot
import me.rerere.rikkahub.workflow.execution.WorkflowFailureCode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Phase 12 — single repository covering workflows + run history. The JSON blob in
 * [WorkflowEntity.definitionJson] is the source of truth — projected columns are derived.
 *
 * Phase-17 stability — the JSON parse is hot. Every Room emission re-fetches all rows; with
 * heavy fire rates and projection-only updates (lastRunAtMs / runsTodayCount) the parser
 * was running thousands of times an hour for ~16KB blobs that hadn't actually changed.
 * The audit measured 100 parses/min for one `@every 60s` workflow with two subscribers.
 *
 * Cache strategy: keyed on `(id, updatedAtMs)`. Projection-only writes don't bump
 * updatedAtMs (Phase 12 deliberately decoupled them from the JSON), so the cache hits.
 * Bounded to 200 entries — far above realistic workflow count and survives normal churn.
 */
class WorkflowRepository(
    private val workflowDao: WorkflowDao,
    private val workflowRunDao: WorkflowRunDao,
) {

    enum class LearnedPromotionWrite { INSERTED, ALREADY_EXACT, CONFLICT }

    data class Loaded(val entity: WorkflowEntity, val definition: WorkflowDefinition)

    /** (id, updatedAtMs) → parsed definition. */
    private val parseCache = androidx.collection.LruCache<String, Pair<String, WorkflowDefinition>>(200)

    private fun parseCached(row: WorkflowEntity): WorkflowDefinition? {
        val stamp = "${row.updatedAtMs}:${row.stateVersion}"
        val cached = parseCache.get(row.id)
        if (cached != null && cached.first == stamp) return cached.second
        val parsedRaw = WorkflowJson.parseStored(row.definitionJson) ?: return null
        val parsed = if (row.origin == WorkflowOrigin.USER.name) {
            parsedRaw.copy(enabled = row.enabled)
        } else {
            parsedRaw
        }
        if (!projectionMatches(row, parsed)) return null
        parseCache.put(row.id, stamp to parsed)
        return parsed
    }

    fun observeAll(): Flow<List<Loaded>> = workflowDao.observeAll().map { rows ->
        rows.mapNotNull { row -> parseCached(row)?.let { Loaded(row, it) } }
    }

    fun observeById(id: String): Flow<Loaded?> = workflowDao.observeById(id).map { row ->
        row?.let { parseCached(it)?.let { def -> Loaded(it, def) } }
    }

    suspend fun listAll(): List<Loaded> =
        workflowDao.listAll().mapNotNull { row ->
            parseCached(row)?.let { Loaded(row, it) }
        }

    suspend fun listEnabled(): List<Loaded> =
        workflowDao.listEnabled().mapNotNull { row ->
            parseCached(row)?.let { Loaded(row, it) }
        }

    suspend fun getById(id: String): Loaded? = workflowDao.getById(id)?.let { row ->
        parseCached(row)?.let { Loaded(row, it) }
    }

    /** Insert or optimistic-CAS update. Generic updates preserve enabled. */
    suspend fun upsert(definition: WorkflowDefinition) {
        val existing = workflowDao.getById(definition.id)
        if (existing == null) {
            require(definition.origin == WorkflowOrigin.USER) {
                "learned_workflow_requires_insertLearnedDisabled"
            }
            workflowDao.insert(entityFor(definition.copy(
                origin = WorkflowOrigin.USER,
                sourceCandidateId = null,
                sourceArtifactHash = null,
                grantDigest = null,
            )))
            return
        }
        check(existing.origin != WorkflowOrigin.LEARNED.name) {
            "learned_workflow_generic_update_denied"
        }
        val current = parseCached(existing) ?: error("workflow_definition_corrupt")
        val updated = definition.copy(
            id = existing.id,
            enabled = existing.enabled,
            createdAtMs = existing.createdAtMs,
            origin = current.origin,
            sourceCandidateId = current.sourceCandidateId,
            sourceArtifactHash = current.sourceArtifactHash,
            grantDigest = current.grantDigest,
        )
        val count = workflowDao.updateDefinitionCas(
            id = existing.id,
            expectedStateVersion = existing.stateVersion,
            name = updated.name,
            description = updated.description,
            definitionJson = WorkflowJson.encode(updated),
            updatedAtMs = updated.updatedAtMs,
            origin = updated.origin.name,
            sourceCandidateId = updated.sourceCandidateId,
            sourceArtifactHash = updated.sourceArtifactHash,
            grantDigest = updated.grantDigest,
            authoringAssistantId = updated.authoringAssistantId,
            capabilitySnapshotJson = capabilityProjection(updated),
            toolSchemaFingerprintsJson = WorkflowToolSchemaSnapshot.canonicalProjection(updated.actions),
        )
        check(count == 1) { "workflow_update_conflict" }
        parseCache.remove(existing.id)
    }

    /** Promotion-only INSERT ABORT with exact duplicate recognition for saga replay. */
    suspend fun ensureExactLearnedPromotionDisabled(
        definition: WorkflowDefinition,
    ): LearnedPromotionWrite {
        require(definition.origin == WorkflowOrigin.LEARNED && !definition.enabled)
        require(definition.id == "learned:${definition.sourceCandidateId}")
        val encoded = WorkflowJson.encodeForLearned(definition)
            ?: return LearnedPromotionWrite.CONFLICT
        fun exact(row: WorkflowEntity): Boolean =
            !row.enabled && row.origin == WorkflowOrigin.LEARNED.name &&
                row.sourceCandidateId == definition.sourceCandidateId &&
                row.sourceArtifactHash == definition.sourceArtifactHash &&
                row.grantDigest == definition.grantDigest &&
                row.authoringAssistantId == definition.authoringAssistantId &&
                row.capabilitySnapshotJson == capabilityProjection(definition) &&
                row.toolSchemaFingerprintsJson ==
                    WorkflowToolSchemaSnapshot.canonicalProjection(definition.actions) &&
                row.definitionJson == encoded
        workflowDao.getById(definition.id)?.let { existing ->
            return if (exact(existing)) LearnedPromotionWrite.ALREADY_EXACT
            else LearnedPromotionWrite.CONFLICT
        }
        return try {
            workflowDao.insert(entityFor(definition).copy(definitionJson = encoded))
            parseCache.remove(definition.id)
            LearnedPromotionWrite.INSERTED
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (workflowDao.getById(definition.id)?.let(::exact) == true) {
                LearnedPromotionWrite.ALREADY_EXACT
            } else LearnedPromotionWrite.CONFLICT
        }
    }

    /** The only enable path for a promoted learned workflow; all provenance is re-fenced. */
    suspend fun enableExactLearnedPromotion(
        id: String,
        candidateId: String,
        artifactSha256: String,
        grantDigest: String,
        expectedStateVersion: Long,
        nowMs: Long,
    ): Boolean {
        if (expectedStateVersion <= 0L || nowMs < 0L) return false
        val row = workflowDao.getById(id) ?: return false
        if (row.stateVersion != expectedStateVersion || row.enabled ||
            row.origin != WorkflowOrigin.LEARNED.name || row.sourceCandidateId != candidateId ||
            row.sourceArtifactHash != artifactSha256 || row.grantDigest != grantDigest ||
            row.staleReason != null
        ) return false
        val current = parseCached(row) ?: return false
        // Enablement is operational state, not an artifact edit. Preserve the candidate-frozen
        // definition timestamp so execution attestation can compare every artifact field exactly.
        val enabled = current.copy(enabled = true)
        val encoded = WorkflowJson.encodeForLearned(enabled) ?: return false
        val updated = workflowDao.setEnabledCas(
            id, expectedStateVersion, true, encoded, enabled.updatedAtMs,
        ) == 1
        if (updated) parseCache.remove(id)
        return updated
    }

    /** Generic toggle may disable LEARNED state but can never enable it. */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
        val row = workflowDao.getById(id) ?: return false
        if (row.origin == WorkflowOrigin.LEARNED.name && enabled) return false
        val current = parseCached(row) ?: return false
        val now = System.currentTimeMillis()
        val definition = current.copy(
            enabled = enabled,
            updatedAtMs = if (current.origin == WorkflowOrigin.LEARNED) {
                current.updatedAtMs
            } else {
                now
            },
        )
        val encoded = if (definition.origin == WorkflowOrigin.LEARNED) {
            WorkflowJson.encodeForLearned(definition) ?: return false
        } else {
            WorkflowJson.encode(definition)
        }
        val updated = workflowDao.setEnabledCas(
            id = id,
            expectedStateVersion = row.stateVersion,
            enabled = enabled,
            definitionJson = encoded,
            updatedAtMs = now,
        ) == 1
        if (updated) parseCache.remove(id)
        return updated
    }

    suspend fun disableLearnedAsStale(loaded: Loaded, reason: String): Boolean {
        if (loaded.entity.origin != WorkflowOrigin.LEARNED.name) return false
        val now = System.currentTimeMillis()
        val disabled = loaded.definition.copy(enabled = false, updatedAtMs = now)
        val encoded = WorkflowJson.encodeForLearned(disabled) ?: return false
        val updated = workflowDao.disableLearnedAsStaleCas(
            id = loaded.entity.id,
            expectedStateVersion = loaded.entity.stateVersion,
            definitionJson = encoded,
            reason = WorkflowFailureCode.durableOrGeneric(reason)
                ?: WorkflowFailureCode.ACTION_RUNTIME_FAILURE,
            updatedAtMs = now,
        ) == 1
        if (updated) parseCache.remove(loaded.entity.id)
        return updated
    }

    suspend fun disableInvalidLearnedById(id: String, reason: String): Boolean {
        val row = workflowDao.getById(id) ?: return false
        if (row.origin != WorkflowOrigin.LEARNED.name) return false
        val updated = workflowDao.disableInvalidLearnedCas(
            id = id,
            expectedStateVersion = row.stateVersion,
            reason = WorkflowFailureCode.durableOrGeneric(reason)
                ?: WorkflowFailureCode.ACTION_RUNTIME_FAILURE,
            updatedAtMs = System.currentTimeMillis(),
        ) == 1
        if (updated) parseCache.remove(id)
        return updated
    }

    private fun entityFor(definition: WorkflowDefinition): WorkflowEntity = WorkflowEntity(
        id = definition.id,
        name = definition.name,
        description = definition.description,
        enabled = definition.enabled,
        definitionJson = WorkflowJson.encode(definition),
        createdAtMs = definition.createdAtMs,
        updatedAtMs = definition.updatedAtMs,
        stateVersion = 1L,
        origin = definition.origin.name,
        sourceCandidateId = definition.sourceCandidateId,
        sourceArtifactHash = definition.sourceArtifactHash,
        grantDigest = definition.grantDigest,
        authoringAssistantId = definition.authoringAssistantId,
        capabilitySnapshotJson = capabilityProjection(definition),
        toolSchemaFingerprintsJson = WorkflowToolSchemaSnapshot.canonicalProjection(definition.actions),
    )

    private fun projectionMatches(row: WorkflowEntity, definition: WorkflowDefinition): Boolean =
        row.origin == definition.origin.name &&
            row.sourceCandidateId == definition.sourceCandidateId &&
            row.sourceArtifactHash == definition.sourceArtifactHash &&
            row.grantDigest == definition.grantDigest &&
            row.authoringAssistantId == definition.authoringAssistantId &&
            row.capabilitySnapshotJson == capabilityProjection(definition) &&
            row.toolSchemaFingerprintsJson ==
                WorkflowToolSchemaSnapshot.canonicalProjection(definition.actions) &&
            (definition.origin != WorkflowOrigin.LEARNED || row.enabled == definition.enabled)

    private fun capabilityProjection(definition: WorkflowDefinition): String = JsonArray(
        definition.capabilitySnapshot.toSortedSet().map(::JsonPrimitive),
    ).toString()

    /** Delete the workflow row and its run history. */
    suspend fun deleteCascading(id: String): Boolean {
        workflowRunDao.deleteAllFor(id)
        val deleted = workflowDao.deleteById(id) > 0
        // Drop the per-workflow lock from the engine so its in-memory map can't grow
        // unbounded across LLM-driven create/delete churn. Resolved lazily because the
        // engine and repository have a circular DI relationship.
        if (deleted) {
            parseCache.remove(id)
            runCatching { engineRef?.forgetWorkflow(id) }
        }
        return deleted
    }

    @Volatile private var engineRef: me.rerere.rikkahub.workflow.execution.WorkflowEngine? = null
    /**
     * Set by the DI module after both singletons exist — workaround for the circular
     * Engine-needs-Repo / Repo-needs-Engine dependency. The repository owns delete; it has
     * to notify the engine to drop in-memory caches.
     */
    fun bindEngine(engine: me.rerere.rikkahub.workflow.execution.WorkflowEngine) {
        engineRef = engine
    }

    /**
     * Record a fire — write a [WorkflowRunEntity] history row, update the projected
     * last-run columns + daily counter on the workflow, and trim history to
     * [WorkflowConstants.MAX_RUNS_HISTORY] rows.
     */
    suspend fun recordFire(
        workflowId: String,
        firedAtMs: Long,
        status: WorkflowRunStatus,
        durationMs: Long,
        errorMessage: String?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val truncatedErr = WorkflowFailureCode.durableOrGeneric(errorMessage)
        workflowRunDao.insert(WorkflowRunEntity(
            workflowId = workflowId,
            firedAtMs = firedAtMs,
            status = status.name,
            durationMs = durationMs,
            errorMessage = truncatedErr,
        ))
        // Daily-cap counter: only counted if the fire was real (SUCCESS or FAILED). Skip
        // statuses don't count, per spec.
        val countsTowardCap = status == WorkflowRunStatus.SUCCESS || status == WorkflowRunStatus.FAILED
        val today = LocalDate.now(zoneId).toString()  // "yyyy-MM-dd"
        val current = workflowDao.getById(workflowId)
        val newCount = when {
            current == null -> if (countsTowardCap) 1 else 0
            current.runsTodayDate != today -> if (countsTowardCap) 1 else 0  // rolled over
            else -> current.runsTodayCount + (if (countsTowardCap) 1 else 0)
        }
        workflowDao.recordFire(
            id = workflowId,
            firedAtMs = firedAtMs,
            status = status.name,
            errorMessage = truncatedErr,
            runsTodayCount = newCount,
            runsTodayDate = today,
        )
        workflowRunDao.trim(workflowId, WorkflowConstants.MAX_RUNS_HISTORY)
    }

    /**
     * Most-recent SUCCESS/FAILED fire — used by the cooldown gate. The projected
     * `lastRunAtMs` column is bumped on every attempt (including skips) so it can't be
     * used here without breaking cooldown semantics.
     */
    suspend fun lastActualFireAtMs(workflowId: String): Long? =
        workflowRunDao.lastActualFireAtMs(workflowId)

    suspend fun lastRuns(workflowId: String, limit: Int = 20): List<WorkflowRun> =
        workflowRunDao.lastN(workflowId, limit).map { row ->
            WorkflowRun(
                rowId = row.rowId,
                workflowId = row.workflowId,
                firedAtMs = row.firedAtMs,
                status = runCatching { WorkflowRunStatus.valueOf(row.status) }
                    .getOrDefault(WorkflowRunStatus.FAILED),
                durationMs = row.durationMs,
                errorMessage = row.errorMessage,
            )
        }
}
