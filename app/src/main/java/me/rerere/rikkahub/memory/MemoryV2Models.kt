package me.rerere.rikkahub.memory

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val MEMORY_IDLE_DELAY_MS = 10L * 60_000L
const val MEMORY_IMMEDIATE_CAPTURE_THRESHOLD = 5

@Serializable
enum class MemoryAutoSaveMode {
    OFF,
    REVIEW_ALL,
    SAFE_NEW_ONLY,
}

@Serializable
enum class MemoryCaptureOrigin {
    APP_UI,
    SYSTEM_ASSISTANT,
    TELEGRAM,
    WEB_API,
    SYSTEM_ASSISTANT_KEYGUARD,
    CRON,
    INTERNAL,
}

/** Separates automatic completed-turn capture from a user-selected manual capture. */
enum class MemoryCaptureSource {
    AUTOMATIC_TURN,
    MANUAL_SELECTION,
}

enum class MemoryCaptureState {
    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED,
    PAUSED,
    DISCARDED,
}

/**
 * Separates a failure that should spend the WorkManager retry budget from one that needs a user
 * configuration change first. Both automatic and manual-only failures remain visible as FAILED;
 * only [NONE] is intentionally discarded.
 */
enum class MemoryFailureRetryPolicy {
    AUTOMATIC,
    MANUAL_ONLY,
    NONE,
}

enum class MemoryCandidateStatus {
    PENDING_REVIEW,
    AUTO_APPLIED,
    ACCEPTED,
    REJECTED,
    CONFLICT,
    SUPERSEDED,
}

enum class MemoryLifecycleStatus {
    ACTIVE,
    ARCHIVED,
}

enum class MemoryApprovalSource {
    LEGACY,
    MANUAL_UI,
    MEMORY_TOOL,
    AUTO_SAFE,
    USER_REVIEWED,
}

enum class MemoryRevisionOperation {
    CREATE,
    UPDATE,
    MERGE,
    ARCHIVE,
    RESTORE,
}

data class CompletedMemoryTurn(
    val assistantId: Uuid,
    val scopeId: String,
    val conversationId: Uuid,
    val userMessageId: Uuid,
    val assistantMessageId: Uuid,
    val origin: MemoryCaptureOrigin,
    val userText: String,
    val assistantText: String,
    val memoryEnabled: Boolean,
    val autoSaveMode: MemoryAutoSaveMode,
    val allowedOrigins: Set<MemoryCaptureOrigin>,
    val isHeadless: Boolean,
    val needsFinalAnswer: Boolean,
    val captureSource: MemoryCaptureSource = MemoryCaptureSource.AUTOMATIC_TURN,
    val idleDelayMs: Long = MEMORY_IDLE_DELAY_MS,
    val immediateCaptureThreshold: Int = MEMORY_IMMEDIATE_CAPTURE_THRESHOLD,
    val conversationContextTurns: Int = MEMORY_DEFAULT_CONVERSATION_CONTEXT_TURNS,
    val narrativeEventsEnabled: Boolean = false,
    val insightsTheoriesEnabled: Boolean = false,
)

data class MemoryCaptureRecord(
    val id: String,
    val assistantId: String,
    val scopeId: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val origin: MemoryCaptureOrigin,
    val captureSource: MemoryCaptureSource = MemoryCaptureSource.AUTOMATIC_TURN,
    val autoSaveMode: MemoryAutoSaveMode,
    val userText: String,
    val assistantText: String,
    val createdAtMs: Long,
    val conversationContextTurns: Int = MEMORY_DEFAULT_CONVERSATION_CONTEXT_TURNS,
    val narrativeEventsEnabled: Boolean = false,
    val insightsTheoriesEnabled: Boolean = false,
)

sealed interface MemoryCaptureResult {
    data class Queued(
        val captureId: String,
        val pendingCount: Int,
        val delayMs: Long,
    ) : MemoryCaptureResult

    data class Duplicate(val captureId: String) : MemoryCaptureResult

    data class Skipped(val reason: MemoryCaptureSkipReason) : MemoryCaptureResult
}

enum class MemoryCaptureSkipReason {
    MEMORY_DISABLED,
    AUTO_SAVE_DISABLED,
    ORIGIN_NOT_ALLOWED,
    HEADLESS,
    NEEDS_FINAL_ANSWER,
    EMPTY_TURN,
}

enum class ManualMemorySelectionResult {
    QUEUED,
    MEMORY_DISABLED,
    NO_USER_TEXT,
    FAILED,
}

sealed interface MemoryCaptureInsertResult {
    data object Inserted : MemoryCaptureInsertResult
    data class Duplicate(val existingId: String) : MemoryCaptureInsertResult
}

data class MemoryWorkRequest(
    val scopeId: String,
    val delayMs: Long,
)

data class MemoryProcessRequest(
    val scopeId: String,
    val workerId: String,
    val leaseDurationMs: Long = MEMORY_PROCESS_LEASE_MS,
)

sealed interface MemoryProcessResult {
    data object NothingToDo : MemoryProcessResult

    data class Paused(val reason: String) : MemoryProcessResult

    data class Completed(
        val processedCaptures: Int,
        val autoApplied: Int,
        val pendingReview: Int,
        val superseded: Int,
        val rejectedProposals: Int,
        val failedCaptures: Int,
        /** Failed captures which should cause the current WorkManager request to retry. */
        val automaticRetryFailedCaptures: Int = 0,
    ) : MemoryProcessResult

    data class Failed(
        val code: String,
        val retryable: Boolean,
    ) : MemoryProcessResult
}

data class MemoryClaimRequest(
    val scopeId: String,
    val workerId: String,
    val nowMs: Long,
    val leaseUntilMs: Long,
    /** Enough room for one coherent thirty-turn conversation without splitting its memory. */
    val maxCaptures: Int = MAX_MEMORY_CONVERSATION_CONTEXT_TURNS,
    val maxConversationGroups: Int = 3,
    /** A connected idle conversation is composed into one compact extraction request. */
    val maxTurnsPerConversation: Int = MAX_MEMORY_CONVERSATION_CONTEXT_TURNS,
)

data class ExistingMemoryRecord(
    val id: Int,
    val scopeId: String,
    val title: String?,
    val content: String,
    val revision: Int,
    val kind: MemoryKind,
    val tags: List<String> = emptyList(),
)

@Serializable
data class MemoryRecordSnapshot(
    val id: Int,
    val scopeId: String,
    val title: String?,
    val content: String,
    val kind: String,
    val tags: List<String>,
    val importance: Float,
    val confidence: Float,
    val expiresAtMs: Long?,
    val lifecycleStatus: String,
    val approvalSource: String,
    val revision: Int,
    val updatedAtMs: Long,
    val originAssistantId: String? = null,
    val attribution: String = MemoryAttribution.UNKNOWN.name,
    val truthStatus: String = MemoryTruthStatus.CONFIRMED.name,
    val occurredAtMs: Long? = null,
    val participants: List<String> = emptyList(),
    val outcome: String? = null,
)

data class MemoryWriteInput(
    val title: String? = null,
    val content: String,
    val kind: MemoryKind? = null,
    val tags: List<String>? = null,
    val importance: Float? = null,
    val confidence: Float? = null,
    val expiresAtMs: Long? = null,
)

data class MemoryQueryRecord(
    val id: Int,
    val title: String?,
    val content: String,
    val kind: MemoryKind,
    val tags: List<String>,
    val sourceType: String,
    val updatedAtMs: Long,
    val importance: Float,
    val score: Double,
    val matchedTerms: List<String>,
    val reason: String,
    /**
     * Keeps a Global record tied to the names configured for the conversation in which it was
     * created. This is presentation metadata only; it must not affect query scope or ranking.
     */
    val originAssistantId: String? = null,
)

data class MemoryExtractionTurn(
    val userMessageId: String,
    val assistantMessageId: String,
    val userText: String,
    val assistantText: String,
    /** Short, model-visible citation token; raw message ids are kept out of compact payloads. */
    val evidenceRef: String? = null,
)

data class MemoryExtractionRequest(
    val scopeId: String,
    val assistantId: String,
    val conversationId: String,
    val turns: List<MemoryExtractionTurn>,
    val existingMemories: List<ExistingMemoryRecord>,
    val narrativeEventsEnabled: Boolean = false,
    val insightsTheoriesEnabled: Boolean = false,
    /** Maps a model-visible evidence token back to its durable source message id. */
    val evidenceRefToMessageId: Map<String, String> = emptyMap(),
    val isConversationContextCompacted: Boolean = false,
    /** Display names for readable output; stable role tokens remain reserved for protocol fields. */
    val narrativeIdentity: MemoryNarrativeIdentity = MemoryNarrativeIdentity(
        selfName = DEFAULT_MEMORY_NARRATIVE_SELF_NAME,
        companionName = DEFAULT_MEMORY_NARRATIVE_COMPANION_NAME,
    ),
)

sealed interface MemoryExtractorResult {
    data class Success(val raw: String) : MemoryExtractorResult

    data class Failure(
        val code: String,
        val message: String? = null,
        val retryPolicy: MemoryFailureRetryPolicy = MemoryFailureRetryPolicy.AUTOMATIC,
    ) : MemoryExtractorResult
}

fun interface MemoryExtractor {
    suspend fun extract(request: MemoryExtractionRequest): MemoryExtractorResult
}

data class MemoryCandidateDecision(
    val id: String,
    val proposal: MemoryProposal,
    val disposition: MemoryCandidateDisposition,
    val duplicate: MemoryDuplicateAssessment,
    val risks: Set<MemoryRiskFlag>,
)

data class MemoryProcessCommit(
    val scopeId: String,
    val assistantId: String,
    val conversationId: String,
    val captures: List<MemoryCaptureRecord>,
    val candidates: List<MemoryCandidateDecision>,
    val nowMs: Long,
)

data class MemoryCommitResult(
    val autoApplied: Int,
    val pendingReview: Int,
    val superseded: Int,
)

sealed interface MemoryReviewCommand {
    data class Accept(
        val candidateId: String,
        val editedProposal: MemoryProposal? = null,
    ) : MemoryReviewCommand

    data class Reject(val candidateId: String) : MemoryReviewCommand
}

sealed interface MemoryReviewResult {
    data class Applied(val memoryId: Int) : MemoryReviewResult
    data object Rejected : MemoryReviewResult
    data object Conflict : MemoryReviewResult
    data object AlreadyResolved : MemoryReviewResult
    data object NotFound : MemoryReviewResult
    data class Failed(val code: String) : MemoryReviewResult
}

sealed interface MemoryMutationCommand {
    data class Create(
        val scopeId: String,
        val title: String? = null,
        val content: String,
        val kind: MemoryKind = MemoryKind.OTHER,
        val tags: List<String> = emptyList(),
        val importance: Float = 0.5f,
        val confidence: Float = 1f,
        val expiresAtMs: Long? = null,
        val approvalSource: MemoryApprovalSource,
        val sourceType: String,
        val sourceConversationId: String? = null,
        val sourceMessageIds: List<String> = emptyList(),
        /** Origin stays attached when this command writes into the shared global scope. */
        val originAssistantId: String? = null,
    ) : MemoryMutationCommand

    data class Update(
        val memoryId: Int,
        val expectedRevision: Int? = null,
        val title: String? = null,
        val content: String,
        val kind: MemoryKind? = null,
        val tags: List<String>? = null,
        val importance: Float? = null,
        val expiresAtMs: Long? = null,
        val approvalSource: MemoryApprovalSource,
    ) : MemoryMutationCommand

    data class Archive(
        val memoryId: Int,
        val approvalSource: MemoryApprovalSource,
    ) : MemoryMutationCommand

    data class Restore(
        val memoryId: Int,
        val approvalSource: MemoryApprovalSource,
    ) : MemoryMutationCommand

    data class RestoreRevision(
        val memoryId: Int,
        val revision: Int,
        val approvalSource: MemoryApprovalSource,
    ) : MemoryMutationCommand
}

sealed interface MemoryMutationResult {
    data class Applied(val memoryId: Int, val revision: Int) : MemoryMutationResult
    data object NotFound : MemoryMutationResult
    data object Conflict : MemoryMutationResult
    data class Rejected(val code: String) : MemoryMutationResult
}

const val MEMORY_PROCESS_LEASE_MS = 15L * 60_000L
const val MEMORY_DEFAULT_CONVERSATION_CONTEXT_TURNS = 12
