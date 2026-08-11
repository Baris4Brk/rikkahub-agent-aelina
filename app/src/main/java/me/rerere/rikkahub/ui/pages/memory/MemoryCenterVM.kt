package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryCaptureStatusCounts
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.DEFAULT_MEMORY_PROMPT_MAX_CHARS
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.memory.MemoryAutoSaveMode
import me.rerere.rikkahub.memory.MemoryCaptureOrigin
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import me.rerere.rikkahub.memory.MemoryReviewCommand
import me.rerere.rikkahub.memory.MemoryReviewResult
import me.rerere.rikkahub.memory.MemoryV2Coordinator
import me.rerere.rikkahub.memory.MemoryWorkRequest
import me.rerere.rikkahub.memory.MemoryWorkScheduler
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryWriteInput
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.memory.MemoryCandidateAction
import me.rerere.rikkahub.memory.MemoryCandidatePolicy
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryProposal
import me.rerere.rikkahub.memory.resolveMemoryExtractionModel
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryCenterVM(
    id: String,
    private val settingsStore: SettingsStore,
    private val memoryDao: MemoryDAO,
    private val memoryV2Dao: MemoryV2Dao,
    private val memoryRepository: MemoryRepository,
    private val mutationCoordinator: MemoryMutationCoordinator,
    private val coordinator: MemoryV2Coordinator,
    private val scheduler: MemoryWorkScheduler,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val candidatePolicy = MemoryCandidatePolicy()
    private val assistantId = Uuid.parse(id)
    val assistant = settingsStore.settingsFlow.map { settings ->
        settings.getAssistantById(assistantId) ?: Assistant(id = assistantId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Assistant(id = assistantId))
    val assistants = settingsStore.settingsFlow.map { it.assistants }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            listOf(Assistant(id = assistantId)),
        )

    val viewGlobal = MutableStateFlow(
        settingsStore.settingsFlow.value.getAssistantById(assistantId)?.useGlobalMemory == true,
    )
    val libraryFilter = MutableStateFlow(MemoryLibraryFilter())
    private val scopeId = combine(assistant, viewGlobal) { assistant, global ->
        if (global) MemoryRepository.GLOBAL_MEMORY_ID else assistant.id.toString()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), assistantId.toString())

    val library = combine(scopeId, libraryFilter) { scope, filter -> scope to filter }
        .flatMapLatest { (scope, filter) ->
            Pager(PagingConfig(pageSize = 30, prefetchDistance = 10)) {
                memoryDao.pagingLibrary(
                    scopeId = scope,
                    includeArchived = filter.includeArchived,
                    nowMs = System.currentTimeMillis(),
                    query = filter.query.trim(),
                    kind = filter.kind?.name,
                    sourceType = filter.sourceType,
                    tag = filter.tag.trim(),
                    sort = filter.sort.name,
                )
            }.flow
        }.cachedIn(viewModelScope)

    val candidates = scopeId.flatMapLatest { scope ->
        Pager(PagingConfig(pageSize = 20, prefetchDistance = 5)) {
            memoryV2Dao.pagingPendingCandidates(scope)
        }.flow
    }.cachedIn(viewModelScope)

    val stats = scopeId.flatMapLatest { scope ->
        val memoryCounts = combine(
            memoryDao.observeActiveCount(scope, System.currentTimeMillis()),
            memoryDao.observeArchivedCount(scope),
        ) { active, archived -> active to archived }
        val queueCounts = combine(
            memoryV2Dao.observePendingCandidateCount(scope),
            memoryV2Dao.observeCaptureStatusCounts(scope),
        ) { review, captures -> review to captures }
        combine(
            memoryCounts,
            queueCounts,
            memoryV2Dao.observeLastProcessedAt(scope),
        ) { memories, queues, last ->
            memoryCenterStats(
                active = memories.first,
                archived = memories.second,
                pendingReview = queues.first,
                captures = queues.second,
                lastProcessedAtMs = last,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryCenterStats())

    val latestFailure = scopeId
        .flatMapLatest { scope -> memoryV2Dao.observeLatestFailure(scope) }
        .map { failure ->
            failure?.let { formatMemoryFailureDetail(it.errorCode, it.errorMessage) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val extractionModel = settingsStore.settingsFlow.map { settings ->
        val model = settings.resolveMemoryExtractionModel()
        val provider = model?.findProvider(settings.providers)
        MemoryExtractionModelUiState(
            modelName = model?.displayName?.ifBlank { model.modelId }.orEmpty(),
            providerName = provider?.name.orEmpty(),
            usingFastModel = settings.memoryExtractionModelId == null,
            available = model != null && provider != null && provider.enabled,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MemoryExtractionModelUiState("", "", true),
    )

    val modelOptions = settingsStore.settingsFlow.map { settings ->
        settings.providers.filter { it.enabled }.flatMap { provider ->
            provider.models.map { model ->
                MemoryModelOption(
                    id = model.id,
                    name = model.displayName.ifBlank { model.modelId },
                    providerName = provider.name,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recallTestState = MutableStateFlow<MemoryRecallTestState>(MemoryRecallTestState.Idle)
    val lastActionMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val configured = settingsStore.settingsFlow.first { settings -> !settings.init }
                .getAssistantById(assistantId)
            if (configured?.useGlobalMemory == true) viewGlobal.value = true
        }
    }

    fun setViewGlobal(global: Boolean) {
        viewGlobal.value = global
    }

    fun updateFilter(transform: (MemoryLibraryFilter) -> MemoryLibraryFilter) {
        libraryFilter.value = transform(libraryFilter.value)
    }

    fun updateAssistant(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { current ->
                        if (current.id == assistantId) transform(current) else current
                    },
                )
            }
        }
    }

    fun setAutoSaveMode(mode: MemoryAutoSaveMode) {
        updateAssistant { it.copy(memoryAutoSaveMode = mode) }
        if (mode == MemoryAutoSaveMode.OFF) {
            val current = assistant.value
            val activeScope = if (current.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                current.id.toString()
            }
            viewModelScope.launch { scheduler.cancel(activeScope) }
        }
    }

    /**
     * Persists the user-facing batching controls together and immediately reapplies the schedule to
     * any already queued captures in the Assistant's active memory scope.
     */
    fun setScheduleTuning(
        idleMinutes: Int,
        immediateThreshold: Int,
        conversationContextTurns: Int,
    ) {
        val normalizedMinutes = idleMinutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)
        val normalizedThreshold = immediateThreshold.coerceIn(MIN_IMMEDIATE_THRESHOLD, MAX_IMMEDIATE_THRESHOLD)
        val normalizedContextTurns = conversationContextTurns.coerceIn(
            MIN_CONVERSATION_CONTEXT_TURNS,
            MAX_CONVERSATION_CONTEXT_TURNS,
        )
        updateAssistant {
            it.copy(
                memoryIdleDelayMinutes = normalizedMinutes,
                memoryImmediateCaptureThreshold = normalizedThreshold,
                memoryConversationContextTurns = normalizedContextTurns,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val currentAssistant = assistant.value
            val scope = if (currentAssistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                currentAssistant.id.toString()
            }
            val pending = memoryV2Dao.countPendingCaptures(scope)
            if (pending > 0) {
                val delayMs = if (pending >= normalizedThreshold) {
                    0L
                } else {
                    normalizedMinutes * 60_000L
                }
                scheduler.schedule(MemoryWorkRequest(scope, delayMs))
            }
        }
    }

    fun setNarrativeNames(userName: String, companionName: String) {
        updateAssistant {
            it.copy(
                memoryNarrativeUserName = userName.trim().take(MAX_NARRATIVE_NAME_CHARS),
                memoryNarrativeCompanionName = companionName.trim().take(MAX_NARRATIVE_NAME_CHARS),
            )
        }
    }

    fun setOrigin(origin: MemoryCaptureOrigin, enabled: Boolean) {
        if (origin !in USER_CONFIGURABLE_ORIGINS) return
        updateAssistant { assistant ->
            assistant.copy(
                memoryCaptureOrigins = if (enabled) {
                    assistant.memoryCaptureOrigins + origin
                } else {
                    assistant.memoryCaptureOrigins - origin
                },
            )
        }
    }

    fun setExtractionModel(modelId: Uuid?) {
        viewModelScope.launch {
            settingsStore.update { it.copy(memoryExtractionModelId = modelId) }
        }
    }

    fun processNow(retryFailed: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val scope = scopeId.value
            if (retryFailed) memoryV2Dao.retryScope(scope, System.currentTimeMillis())
            scheduler.schedule(MemoryWorkRequest(scope, 0L))
            lastActionMessage.value = "scheduled"
        }
    }

    fun review(command: MemoryReviewCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            lastActionMessage.value = when (coordinator.review(command)) {
                is MemoryReviewResult.Applied -> "accepted"
                MemoryReviewResult.Rejected -> "rejected"
                MemoryReviewResult.Conflict -> "conflict"
                MemoryReviewResult.AlreadyResolved -> "already_resolved"
                MemoryReviewResult.NotFound -> "not_found"
                is MemoryReviewResult.Failed -> "failed"
            }
        }
    }

    fun acceptCandidate(
        candidate: MemoryCandidateEntity,
        editedTitle: String? = null,
        editedContent: String? = null,
    ) {
        val proposal = candidate.toProposal().let { original ->
            if (editedTitle == null && editedContent == null) original
            else original.copy(
                title = editedTitle ?: original.title,
                content = editedContent ?: original.content,
            )
        }
        review(
            MemoryReviewCommand.Accept(
                candidateId = candidate.id,
                expectedScopeId = candidate.scopeId,
                editedProposal = proposal,
            ),
        )
    }

    fun rejectCandidate(candidate: MemoryCandidateEntity) {
        review(
            MemoryReviewCommand.Reject(
                candidateId = candidate.id,
                expectedScopeId = candidate.scopeId,
            ),
        )
    }

    /**
     * Applies only candidates that still meet the same clean CREATE policy used by automatic
     * write-back. The database review path rechecks revisions and duplicate conflicts per row.
     */
    fun acceptSafeNewCandidates() {
        reviewPendingCandidates { candidate ->
            candidate.takeIf { it.status == "PENDING_REVIEW" }
                ?.takeIf { candidate.hasNoReviewFlags() }
                ?.takeIf { candidatePolicy.isSafeNewCreate(candidate.toProposal()) }
                ?.let {
                    MemoryReviewCommand.Accept(
                        candidateId = it.id,
                        expectedScopeId = it.scopeId,
                    )
                }
        }
    }

    /** Rejects pending candidates only; conflicts remain visible for an explicit user decision. */
    fun rejectAllPendingCandidates() {
        reviewPendingCandidates { candidate ->
            candidate.takeIf { it.status == "PENDING_REVIEW" }
                ?.let {
                    MemoryReviewCommand.Reject(
                        candidateId = it.id,
                        expectedScopeId = it.scopeId,
                    )
                }
        }
    }

    private fun reviewPendingCandidates(
        commandFor: (MemoryCandidateEntity) -> MemoryReviewCommand?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var changed = 0
            var conflict = false
            val pending = memoryV2Dao.observePendingCandidates(scopeId.value).first()
            pending.forEach { candidate ->
                val command = commandFor(candidate) ?: return@forEach
                when (coordinator.review(command)) {
                    is MemoryReviewResult.Applied,
                    MemoryReviewResult.Rejected,
                    -> changed++

                    MemoryReviewResult.Conflict -> conflict = true
                    MemoryReviewResult.AlreadyResolved,
                    MemoryReviewResult.NotFound,
                    is MemoryReviewResult.Failed,
                    -> Unit
                }
            }
            lastActionMessage.value = when {
                changed > 0 -> "accepted"
                conflict -> "conflict"
                else -> "already_resolved"
            }
        }
    }

    suspend fun loadMemories(
        candidate: MemoryCandidateEntity,
        ids: List<Int>,
    ): List<MemoryEntity> =
        if (ids.isEmpty()) emptyList() else memoryDao.getMemoriesByIds(ids, candidate.scopeId)

    suspend fun resolveSource(candidate: MemoryCandidateEntity): MemorySourceLocation? {
        val conversationId = runCatching { Uuid.parse(candidate.sourceConversationId) }.getOrNull()
            ?: return null
        val conversation = conversationRepository.getConversationById(conversationId)
            ?: return null
        val evidence = runCatching {
            JsonInstant.decodeFromString<List<String>>(candidate.evidenceMessageIdsJson)
        }.getOrDefault(emptyList())
        val nodeId = evidence.asSequence()
            .mapNotNull { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            .mapNotNull { messageId -> conversation.getMessageNodeByMessageId(messageId)?.id }
            .firstOrNull()
        return MemorySourceLocation(conversationId, nodeId)
    }

    fun archive(memory: MemoryEntity) {
        mutate(
            MemoryMutationCommand.Archive(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun restore(memory: MemoryEntity) {
        mutate(
            MemoryMutationCommand.Restore(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun updateMemory(memory: MemoryEntity, input: MemoryWriteInput) {
        mutate(
            MemoryMutationCommand.Update(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedRevision = memory.revision,
                title = input.title,
                content = input.content,
                kind = input.kind,
                tags = input.tags,
                importance = input.importance,
                expiryUpdate = input.expiresAtMs?.let(
                    me.rerere.rikkahub.memory.MemoryExpiryUpdate::Set,
                ) ?: input.expiryUpdate,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun restoreRevision(memory: MemoryEntity, revision: Int) {
        mutate(
            MemoryMutationCommand.RestoreRevision(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedCurrentRevision = memory.revision,
                revision = revision,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
            ),
        )
    }

    fun revisions(memory: MemoryEntity) =
        memoryV2Dao.observeRevisions(memory.id, memory.assistantId)

    fun createMemory(input: MemoryWriteInput) {
        viewModelScope.launch(Dispatchers.IO) {
            val command = MemoryMutationCommand.Create(
                scopeId = scopeId.value,
                title = input.title,
                content = input.content,
                kind = input.kind ?: me.rerere.rikkahub.memory.MemoryKind.OTHER,
                tags = input.tags.orEmpty(),
                importance = input.importance ?: 0.5f,
                confidence = input.confidence ?: 1f,
                expiresAtMs = input.expiresAtMs,
                approvalSource = MemoryApprovalSource.MANUAL_UI,
                sourceType = "MANUAL_UI",
                originAssistantId = assistantId.toString(),
            )
            lastActionMessage.value = mutationCoordinator.mutate(command)
                .toMemoryMutationUiFeedback()
                .actionMessageCode
        }
    }

    fun mutate(command: MemoryMutationCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            lastActionMessage.value = mutationCoordinator.mutate(command)
                .toMemoryMutationUiFeedback()
                .actionMessageCode
        }
    }

    fun runRecallTest(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        recallTestState.value = MemoryRecallTestState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                memoryRepository.queryDetailed(
                    assistantId = assistantId,
                    query = normalized,
                    includeGlobal = viewGlobal.value,
                    limit = 8,
                )
            }.onSuccess { results ->
                recallTestState.value = MemoryRecallTestState.Ready(
                    query = normalized,
                    results = results,
                    usedCharacters = results.sumOf { it.content.length },
                    characterBudget = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
                )
            }.onFailure { error ->
                recallTestState.value = MemoryRecallTestState.Failed(error.message.orEmpty())
            }
        }
    }

    companion object {
        const val MIN_IDLE_MINUTES = 1
        const val MAX_IDLE_MINUTES = 1_440
        const val MIN_IMMEDIATE_THRESHOLD = 1
        const val MAX_IMMEDIATE_THRESHOLD = 50
        const val MIN_CONVERSATION_CONTEXT_TURNS = 3
        const val MAX_CONVERSATION_CONTEXT_TURNS = 30
        const val MAX_NARRATIVE_NAME_CHARS = 80

        val USER_CONFIGURABLE_ORIGINS = setOf(
            MemoryCaptureOrigin.APP_UI,
            MemoryCaptureOrigin.SYSTEM_ASSISTANT,
            MemoryCaptureOrigin.QUICK_CAPTURE,
            MemoryCaptureOrigin.TELEGRAM,
            MemoryCaptureOrigin.WEB_API,
        )
    }
}

internal fun memoryCenterStats(
    active: Int,
    archived: Int,
    pendingReview: Int,
    captures: MemoryCaptureStatusCounts,
    lastProcessedAtMs: Long?,
) = MemoryCenterStats(
    active = active,
    archived = archived,
    pendingReview = pendingReview,
    pendingCaptures = captures.pendingCaptures,
    processingCaptures = captures.processingCaptures,
    processedCaptures = captures.processedCaptures,
    noLongTermSignalCaptures = captures.noLongTermSignalCaptures,
    failedCaptures = captures.failedCaptures,
    pausedCaptures = captures.pausedCaptures,
    discardedCaptures = captures.discardedCaptures,
    lastProcessedAtMs = lastProcessedAtMs,
)

private fun MemoryCandidateEntity.toProposal() = MemoryProposal(
    action = runCatching { MemoryCandidateAction.valueOf(action) }
        .getOrDefault(MemoryCandidateAction.IGNORE),
    targetIds = runCatching { JsonInstant.decodeFromString<List<Int>>(targetMemoryIdsJson) }
        .getOrDefault(emptyList()),
    expectedRevisions = runCatching {
        JsonInstant.decodeFromString<List<Int>>(expectedRevisionsJson)
    }.getOrDefault(emptyList()),
    title = title,
    content = content,
    kind = runCatching { MemoryKind.valueOf(memoryKind) }.getOrDefault(MemoryKind.OTHER),
    tags = runCatching { JsonInstant.decodeFromString<List<String>>(tagsJson) }
        .getOrDefault(emptyList()),
    importance = importance,
    confidence = confidence,
    expiresAtMs = expiresAtMs,
    evidenceMessageIds = runCatching {
        JsonInstant.decodeFromString<List<String>>(evidenceMessageIdsJson)
    }.getOrDefault(emptyList()),
    reason = reason,
)

/** Unknown or malformed persisted flags fail closed so bulk acceptance cannot bypass review. */
private fun MemoryCandidateEntity.hasNoReviewFlags(): Boolean = runCatching {
    JsonInstant.decodeFromString<List<String>>(riskFlagsJson).isEmpty()
}.getOrDefault(false)
