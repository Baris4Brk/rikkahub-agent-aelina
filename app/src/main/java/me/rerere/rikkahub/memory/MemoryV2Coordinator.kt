package me.rerere.rikkahub.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface MemoryV2Coordinator {
    suspend fun capture(turn: CompletedMemoryTurn): MemoryCaptureResult

    suspend fun process(request: MemoryProcessRequest): MemoryProcessResult

    suspend fun review(command: MemoryReviewCommand): MemoryReviewResult
}

interface MemoryCaptureStore {
    suspend fun insert(record: MemoryCaptureRecord): MemoryCaptureInsertResult

    suspend fun pendingCount(scopeId: String): Int
}

fun interface MemoryWorkScheduler {
    suspend fun schedule(request: MemoryWorkRequest)

    suspend fun continueNow(scopeId: String) = Unit

    suspend fun cancel(scopeId: String) = Unit
}

interface MemoryProcessingStore {
    /** Returns a bounded, leased batch; the adapter enforces every limit in [MemoryClaimRequest]. */
    suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord>

    suspend fun findExisting(
        scopeId: String,
        query: String,
        limit: Int,
    ): List<ExistingMemoryRecord>

    /** Atomically resolves captures, candidate rows, formal memories and revisions. */
    suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult

    suspend fun markFailed(
        captureIds: List<String>,
        code: String,
        message: String?,
        retryPolicy: MemoryFailureRetryPolicy,
        nowMs: Long,
    )

    suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long)

    /** Returns interrupted leases without consuming an automatic retry. */
    suspend fun releaseClaimed(captureIds: List<String>, nowMs: Long) = Unit

    suspend fun review(command: MemoryReviewCommand, nowMs: Long): MemoryReviewResult

    suspend fun mutate(
        command: MemoryMutationCommand,
        nowMs: Long,
    ): MemoryMutationResult = MemoryMutationResult.Rejected("memory_mutation_unavailable")
}

fun interface MemoryEmergencyGate {
    suspend fun isStopped(): Boolean
}

class DefaultMemoryV2Coordinator(
    private val captureStore: MemoryCaptureStore,
    private val workScheduler: MemoryWorkScheduler,
    private val processingStore: MemoryProcessingStore? = null,
    private val extractor: MemoryExtractor? = null,
    private val emergencyGate: MemoryEmergencyGate = MemoryEmergencyGate { false },
    private val extractionParser: MemoryExtractionParser = MemoryExtractionParser(),
    private val proposalValidator: MemoryProposalValidator = MemoryProposalValidator(),
    private val candidatePolicy: MemoryCandidatePolicy = MemoryCandidatePolicy(),
    private val duplicateDetector: MemoryDuplicateDetector = MemoryDuplicateDetector(),
    private val contentGuard: MemoryContentGuard = MemoryContentGuard(),
    private val narrativePolicy: MemoryNarrativePolicy = MemoryNarrativePolicy(),
    private val narrativeIdentityResolver: MemoryNarrativeIdentityResolver =
        MemoryNarrativeIdentityResolver {
            MemoryNarrativeIdentity(
                selfName = DEFAULT_MEMORY_NARRATIVE_SELF_NAME,
                companionName = DEFAULT_MEMORY_NARRATIVE_COMPANION_NAME,
            )
        },
    private val idGenerator: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MemoryV2Coordinator {
    private val extractionInputComposer = MemoryExtractionInputComposer(contentGuard)

    override suspend fun capture(turn: CompletedMemoryTurn): MemoryCaptureResult {
        captureSkipReason(turn)?.let { return MemoryCaptureResult.Skipped(it) }

        val captureId = idGenerator()
        val record = MemoryCaptureRecord(
            id = captureId,
            assistantId = turn.assistantId.toString(),
            scopeId = turn.scopeId,
            conversationId = turn.conversationId.toString(),
            userMessageId = turn.userMessageId.toString(),
            assistantMessageId = turn.assistantMessageId.toString(),
            origin = turn.origin,
            captureSource = turn.captureSource,
            autoSaveMode = turn.autoSaveMode,
            userText = turn.userText,
            assistantText = turn.assistantText,
            createdAtMs = nowMs(),
            conversationContextTurns = turn.conversationContextTurns.coerceIn(
                MIN_MEMORY_CONVERSATION_CONTEXT_TURNS,
                MAX_MEMORY_CONVERSATION_CONTEXT_TURNS,
            ),
            narrativeEventsEnabled = turn.narrativeEventsEnabled,
            insightsTheoriesEnabled = turn.insightsTheoriesEnabled,
        )
        when (val insert = captureStore.insert(record)) {
            is MemoryCaptureInsertResult.Duplicate -> {
                return MemoryCaptureResult.Duplicate(insert.existingId)
            }

            MemoryCaptureInsertResult.Inserted -> Unit
        }

        val pendingCount = captureStore.pendingCount(turn.scopeId)
        val threshold = turn.immediateCaptureThreshold.coerceIn(1, 50)
        val delayMs = if (pendingCount >= threshold) {
            0L
        } else {
            turn.idleDelayMs.coerceIn(MIN_MEMORY_IDLE_DELAY_MS, MAX_MEMORY_IDLE_DELAY_MS)
        }
        workScheduler.schedule(MemoryWorkRequest(turn.scopeId, delayMs))
        return MemoryCaptureResult.Queued(captureId, pendingCount, delayMs)
    }

    override suspend fun process(request: MemoryProcessRequest): MemoryProcessResult {
        val store = processingStore
            ?: return MemoryProcessResult.Failed("memory_processing_store_missing", false)
        val memoryExtractor = extractor
            ?: return MemoryProcessResult.Failed("memory_extractor_missing", false)
        val now = nowMs()
        if (emergencyGate.isStopped()) {
            store.pauseScope(request.scopeId, "emergency_stop", now)
            return MemoryProcessResult.Paused("emergency_stop")
        }

        val captures = try {
            store.claim(
                MemoryClaimRequest(
                    scopeId = request.scopeId,
                    workerId = request.workerId,
                    nowMs = now,
                    leaseUntilMs = now + request.leaseDurationMs,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return MemoryProcessResult.Failed("memory_capture_claim_failed", true)
        }
        if (captures.isEmpty()) return MemoryProcessResult.NothingToDo

        var processedCaptures = 0
        var autoApplied = 0
        var pendingReview = 0
        var superseded = 0
        var rejectedProposals = 0
        var failedCaptures = 0
        var automaticRetryFailedCaptures = 0
        // User-selected messages are a distinct intent. Never combine them with automatic turns
        // from the same conversation when constructing an extraction request.
        try {
            captures.groupBy { it.conversationId to it.captureSource }.values.forEach { group ->
                val outcome = processGroup(store, memoryExtractor, request.scopeId, group, now)
                processedCaptures += outcome.processedCaptures
                autoApplied += outcome.autoApplied
                pendingReview += outcome.pendingReview
                superseded += outcome.superseded
                rejectedProposals += outcome.rejectedProposals
                failedCaptures += outcome.failedCaptures
                automaticRetryFailedCaptures += outcome.automaticRetryFailedCaptures
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                store.releaseClaimed(captures.map(MemoryCaptureRecord::id), nowMs())
            }
            throw cancelled
        }
        return MemoryProcessResult.Completed(
            processedCaptures = processedCaptures,
            autoApplied = autoApplied,
            pendingReview = pendingReview,
            superseded = superseded,
            rejectedProposals = rejectedProposals,
            failedCaptures = failedCaptures,
            automaticRetryFailedCaptures = automaticRetryFailedCaptures,
        )
    }

    override suspend fun review(command: MemoryReviewCommand): MemoryReviewResult {
        val store = processingStore ?: return MemoryReviewResult.Failed("memory_store_missing")
        return store.review(command, nowMs())
    }

    private suspend fun processGroup(
        store: MemoryProcessingStore,
        memoryExtractor: MemoryExtractor,
        scopeId: String,
        captures: List<MemoryCaptureRecord>,
        now: Long,
    ): ProcessGroupOutcome {
        val bounded = captures.sortedBy(MemoryCaptureRecord::createdAtMs)
            .takeLast(MAX_MEMORY_TURNS_PER_EXTRACTION)
        val preparedInput = extractionInputComposer.compose(bounded)
        val turns = preparedInput.turns
        val captureIds = bounded.map(MemoryCaptureRecord::id)
        if (turns.isEmpty()) {
            store.markFailed(
                captureIds,
                "memory_extraction_empty",
                null,
                retryPolicy = MemoryFailureRetryPolicy.NONE,
                nowMs = now,
            )
            return ProcessGroupOutcome(failedCaptures = bounded.size)
        }

        val query = turns.joinToString("\n") { it.userText }.take(MEMORY_EXISTING_QUERY_CHARS)
        val existing = try {
            store.findExisting(scopeId, query, MAX_EXISTING_MEMORIES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }
        val existingForExtraction = compactExistingMemoriesForExtraction(existing)
        val narrativeIdentity = runCatching {
            narrativeIdentityResolver.resolve(bounded.first().assistantId)
        }.getOrElse {
            MemoryNarrativeIdentity(
                selfName = DEFAULT_MEMORY_NARRATIVE_SELF_NAME,
                companionName = DEFAULT_MEMORY_NARRATIVE_COMPANION_NAME,
            )
        }
        val extraction = try {
            memoryExtractor.extract(
                MemoryExtractionRequest(
                    scopeId = scopeId,
                    assistantId = bounded.first().assistantId,
                    conversationId = bounded.first().conversationId,
                    turns = turns,
                    existingMemories = existingForExtraction,
                    narrativeEventsEnabled = bounded.any(MemoryCaptureRecord::narrativeEventsEnabled),
                    insightsTheoriesEnabled = bounded.any(MemoryCaptureRecord::insightsTheoriesEnabled),
                    evidenceRefToMessageId = preparedInput.evidenceRefToMessageId,
                    isConversationContextCompacted = preparedInput.isConversationContextCompacted,
                    narrativeIdentity = narrativeIdentity,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            MemoryExtractorResult.Failure(
                code = "memory_extractor_exception",
                message = error.message,
                retryPolicy = MemoryFailureRetryPolicy.AUTOMATIC,
            )
        }
        if (extraction is MemoryExtractorResult.Failure) {
            store.markFailed(
                captureIds,
                extraction.code,
                extraction.message,
                extraction.retryPolicy,
                now,
            )
            return ProcessGroupOutcome(
                failedCaptures = bounded.size,
                automaticRetryFailedCaptures = extraction.retryPolicy
                    .automaticRetryCaptureCount(bounded.size),
            )
        }

        val parsed = extractionParser.parse((extraction as MemoryExtractorResult.Success).raw)
        if (parsed is MemoryExtractionParseResult.Failure) {
            store.markFailed(
                captureIds,
                parsed.message,
                null,
                retryPolicy = MemoryFailureRetryPolicy.AUTOMATIC,
                nowMs = now,
            )
            return ProcessGroupOutcome(
                failedCaptures = bounded.size,
                automaticRetryFailedCaptures = bounded.size,
            )
        }
        val allowedEvidence = preparedInput.evidenceRefToMessageId.values.toMutableSet().apply {
            turns.forEach { turn ->
                add(turn.userMessageId)
                add(turn.assistantMessageId)
            }
        }
        val parsedEnvelope = (parsed as MemoryExtractionParseResult.Success).envelope
        val resolvedEnvelope = parsedEnvelope.resolveEvidenceReferences(preparedInput.evidenceRefToMessageId)
        val normalizedEnvelope = resolvedEnvelope.copy(
            proposals = resolvedEnvelope.proposals.map { proposal ->
                narrativePolicy.normalize(proposal, narrativeIdentity)
            },
            relations = resolvedEnvelope.relations.map { relation ->
                narrativePolicy.normalize(relation, narrativeIdentity)
            },
        )
        val validation = proposalValidator.validate(
            envelope = normalizedEnvelope,
            context = MemoryProposalValidationContext(
                allowedEvidenceMessageIds = allowedEvidence,
                visibleExistingMemories = existingForExtraction.associate { it.id to it.revision },
                narrativeEventsEnabled = bounded.any(MemoryCaptureRecord::narrativeEventsEnabled),
                insightsTheoriesEnabled = bounded.any(MemoryCaptureRecord::insightsTheoriesEnabled),
                nowMs = now,
            ),
        )
        val mode = if (bounded.all { it.autoSaveMode == MemoryAutoSaveMode.SAFE_NEW_ONLY }) {
            MemoryAutoSaveMode.SAFE_NEW_ONLY
        } else {
            MemoryAutoSaveMode.REVIEW_ALL
        }
        val existingTexts = existingForExtraction.map { memory ->
            listOfNotNull(memory.title, memory.content).joinToString("\n")
        }
        val decisions = validation.accepted
            .filterNot { it.action == MemoryCandidateAction.IGNORE }
            .map { proposal ->
                val duplicate = duplicateDetector.assess(
                    candidate = listOf(proposal.title, proposal.content).joinToString("\n"),
                    existing = existingTexts,
                )
                MemoryCandidateDecision(
                    id = idGenerator(),
                    proposal = proposal,
                    disposition = candidatePolicy.decide(proposal, mode, duplicate),
                    duplicate = duplicate,
                    risks = candidatePolicy.reviewFlagsFor(proposal, duplicate),
                )
            }
        val commitResult = try {
            store.commit(
                MemoryProcessCommit(
                    scopeId = scopeId,
                    assistantId = bounded.first().assistantId,
                    conversationId = bounded.first().conversationId,
                    captures = bounded,
                    candidates = decisions,
                    nowMs = now,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            store.markFailed(
                captureIds,
                "memory_commit_failed",
                error.message,
                retryPolicy = MemoryFailureRetryPolicy.AUTOMATIC,
                nowMs = now,
            )
            return ProcessGroupOutcome(
                failedCaptures = bounded.size,
                automaticRetryFailedCaptures = bounded.size,
            )
        }
        return ProcessGroupOutcome(
            processedCaptures = bounded.size,
            autoApplied = commitResult.autoApplied,
            pendingReview = commitResult.pendingReview,
            superseded = commitResult.superseded,
            rejectedProposals = validation.rejected.size,
        )
    }

}

private data class ProcessGroupOutcome(
    val processedCaptures: Int = 0,
    val autoApplied: Int = 0,
    val pendingReview: Int = 0,
    val superseded: Int = 0,
    val rejectedProposals: Int = 0,
    val failedCaptures: Int = 0,
    val automaticRetryFailedCaptures: Int = 0,
)

/** Converts compact payload citation tokens back to durable local message ids before validation. */
private fun MemoryExtractionEnvelope.resolveEvidenceReferences(
    evidenceRefToMessageId: Map<String, String>,
): MemoryExtractionEnvelope {
    if (evidenceRefToMessageId.isEmpty()) return this
    fun resolve(ids: List<String>): List<String> = ids.map { reference ->
        evidenceRefToMessageId[reference] ?: reference
    }.distinct()
    return copy(
        proposals = proposals.map { proposal ->
            proposal.copy(evidenceMessageIds = resolve(proposal.evidenceMessageIds))
        },
        relations = relations.map { relation ->
            relation.copy(evidenceMessageIds = resolve(relation.evidenceMessageIds))
        },
    )
}

private fun MemoryFailureRetryPolicy.automaticRetryCaptureCount(captureCount: Int): Int =
    if (this == MemoryFailureRetryPolicy.AUTOMATIC) captureCount else 0

const val MIN_MEMORY_CONVERSATION_CONTEXT_TURNS = 3
const val MAX_MEMORY_CONVERSATION_CONTEXT_TURNS = 30
private const val MAX_MEMORY_TURNS_PER_EXTRACTION = MAX_MEMORY_CONVERSATION_CONTEXT_TURNS
private const val MEMORY_EXISTING_QUERY_CHARS = 2_000
private const val MAX_EXISTING_MEMORIES = 4
private const val MIN_MEMORY_IDLE_DELAY_MS = 60_000L
private const val MAX_MEMORY_IDLE_DELAY_MS = 24L * 60L * 60_000L

private fun captureSkipReason(turn: CompletedMemoryTurn): MemoryCaptureSkipReason? = when {
    !turn.memoryEnabled -> MemoryCaptureSkipReason.MEMORY_DISABLED
    turn.autoSaveMode == MemoryAutoSaveMode.OFF -> MemoryCaptureSkipReason.AUTO_SAVE_DISABLED
    turn.origin !in turn.allowedOrigins || turn.origin !in INTERACTIVE_CAPTURE_ORIGINS ->
        MemoryCaptureSkipReason.ORIGIN_NOT_ALLOWED
    turn.isHeadless -> MemoryCaptureSkipReason.HEADLESS
    turn.needsFinalAnswer -> MemoryCaptureSkipReason.NEEDS_FINAL_ANSWER
    turn.userText.isBlank() ||
        (turn.captureSource != MemoryCaptureSource.MANUAL_SELECTION && turn.assistantText.isBlank()) ->
        MemoryCaptureSkipReason.EMPTY_TURN
    else -> null
}

private val INTERACTIVE_CAPTURE_ORIGINS = setOf(
    MemoryCaptureOrigin.APP_UI,
    MemoryCaptureOrigin.SYSTEM_ASSISTANT,
    MemoryCaptureOrigin.TELEGRAM,
    MemoryCaptureOrigin.WEB_API,
)
