package me.rerere.rikkahub.data.authority.reward

import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityRevisionEntity

enum class RewardFeedbackVerdict {
    HELPFUL,
    NOT_HELPFUL,
    INCORRECT,
    GOAL_CONFIRMED,
}

enum class RewardDimension { GOAL, PROCESS, USER }

enum class RewardSignalKind { EXPLICIT_USER_FEEDBACK, EXPLICIT_USER_CORRECTION }

enum class RewardFeedbackSourceState { ACTIVE, TOMBSTONED }

data class RewardFeedbackMeaning(
    val dimension: RewardDimension,
    val signalKind: RewardSignalKind,
    val valueMilli: Int,
)

fun RewardFeedbackVerdict.toRewardMeaning(): RewardFeedbackMeaning = when (this) {
    RewardFeedbackVerdict.HELPFUL -> RewardFeedbackMeaning(
        RewardDimension.USER,
        RewardSignalKind.EXPLICIT_USER_FEEDBACK,
        1_000,
    )
    RewardFeedbackVerdict.NOT_HELPFUL -> RewardFeedbackMeaning(
        RewardDimension.USER,
        RewardSignalKind.EXPLICIT_USER_FEEDBACK,
        -1_000,
    )
    RewardFeedbackVerdict.INCORRECT -> RewardFeedbackMeaning(
        RewardDimension.GOAL,
        RewardSignalKind.EXPLICIT_USER_CORRECTION,
        -1_000,
    )
    RewardFeedbackVerdict.GOAL_CONFIRMED -> RewardFeedbackMeaning(
        RewardDimension.GOAL,
        RewardSignalKind.EXPLICIT_USER_FEEDBACK,
        1_000,
    )
}

/** Content-free snapshot passed to the main-database outbox adapter. */
data class RewardFeedbackAuthorityEvent(
    val feedbackId: String,
    val scopeKind: String,
    val scopeId: String,
    val conversationId: String,
    val conversationSourceRevision: Long,
    val commandId: String,
    val commandRevision: Long,
    val lineageId: String,
    val branchAnchorMessageId: String,
    val branchAnchorMessageRevision: Long,
    val targetAssistantMessageId: String,
    val targetAssistantMessageRevision: Long,
    val dimension: RewardDimension,
    val signalKind: RewardSignalKind,
    val valueMilli: Int?,
    val sourceState: RewardFeedbackSourceState,
    val sourceRevision: Long,
    val previousSourceRevision: Long?,
    val occurredAtMs: Long,
) {
    init {
        require(scopeKind == "ASSISTANT" || scopeKind == "AUTHORITY_SUBJECT")
        listOf(
            feedbackId,
            scopeId,
            conversationId,
            commandId,
            lineageId,
            branchAnchorMessageId,
            targetAssistantMessageId,
        ).forEach { id ->
            require(id.length in 1..256 && id.all { char ->
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
                    char == '-' || char == '_' || char == '.' || char == ':' || char == '@'
            })
        }
        listOf(
            conversationSourceRevision,
            commandRevision,
            branchAnchorMessageRevision,
            targetAssistantMessageRevision,
        ).forEach { require(it > 0L) }
        require(occurredAtMs >= 0L)
        require(sourceRevision > 0L)
        require(
            (sourceRevision == 1L && previousSourceRevision == null) ||
                (sourceRevision > 1L && previousSourceRevision == sourceRevision - 1L),
        )
        require(sourceState != RewardFeedbackSourceState.TOMBSTONED || sourceRevision > 1L)
        require(
            (sourceState == RewardFeedbackSourceState.ACTIVE &&
                (valueMilli == -1_000 || valueMilli == 1_000)) ||
                (sourceState == RewardFeedbackSourceState.TOMBSTONED && valueMilli == null),
        )
    }

    override fun toString(): String =
        "RewardFeedbackAuthorityEvent(dimension=$dimension, kind=$signalKind, " +
            "state=$sourceState, revision=$sourceRevision, ids=<redacted>)"
}

interface RewardFeedbackAuthorityEventPort {
    suspend fun appendInCurrentTransaction(event: RewardFeedbackAuthorityEvent): Boolean
    fun dispatchPostCommit(insertedOutbox: Boolean)
}

object DisabledRewardFeedbackAuthorityEventPort : RewardFeedbackAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: RewardFeedbackAuthorityEvent): Boolean = false
    override fun dispatchPostCommit(insertedOutbox: Boolean) = Unit
}

data class RewardFeedbackJournalCursor(
    val updatedAtMs: Long = 0L,
    val feedbackId: String = "",
    val sourceRevision: Long = 0L,
)

data class RewardFeedbackJournalPage(
    val events: List<RewardFeedbackAuthorityEvent>,
    val nextCursor: RewardFeedbackJournalCursor?,
)

interface RewardFeedbackAuthorityJournalSource {
    suspend fun listJournalPage(
        fromInclusiveMs: Long,
        toExclusiveMs: Long,
        after: RewardFeedbackJournalCursor = RewardFeedbackJournalCursor(),
        limit: Int,
    ): RewardFeedbackJournalPage
}

internal fun RewardFeedbackAuthorityRevisionEntity.toAuthorityEvent(): RewardFeedbackAuthorityEvent =
    RewardFeedbackAuthorityEvent(
        feedbackId = feedbackId,
        scopeKind = scopeKind,
        scopeId = scopeId,
        conversationId = conversationId,
        conversationSourceRevision = conversationSourceRevision,
        commandId = commandId,
        commandRevision = commandRevision,
        lineageId = lineageId,
        branchAnchorMessageId = branchAnchorMessageId,
        branchAnchorMessageRevision = branchAnchorMessageRevision,
        targetAssistantMessageId = targetAssistantMessageId,
        targetAssistantMessageRevision = targetAssistantMessageRevision,
        dimension = RewardDimension.valueOf(dimension),
        signalKind = RewardSignalKind.valueOf(signalKind),
        valueMilli = valueMilli,
        sourceState = RewardFeedbackSourceState.valueOf(sourceState),
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        occurredAtMs = updatedAtMs,
    )

sealed interface RewardFeedbackWriteResult {
    val feedbackId: String?

    data class Committed(
        override val feedbackId: String,
        val sourceRevision: Long,
        val insertedOutbox: Boolean,
    ) : RewardFeedbackWriteResult

    data class Duplicate(
        override val feedbackId: String,
        val sourceRevision: Long,
    ) : RewardFeedbackWriteResult

    data class Rejected(
        val reason: RewardFeedbackRejection,
    ) : RewardFeedbackWriteResult {
        override val feedbackId: String? = null
    }
}

enum class RewardFeedbackRejection {
    CAPTURE_NOT_AUTHORIZED,
    INVALID_TARGET,
    TARGET_NOT_UNIQUE,
    INCOMPLETE_COMMAND_AUTHORITY,
    SOURCE_NOT_ACTIVE_EXACT,
    SCOPE_MISMATCH,
    TOMBSTONED,
    CONCURRENT_UPDATE,
}

data class RewardFeedbackInvalidationResult(
    val examinedHeads: Int,
    val tombstonedHeads: Int,
    val insertedOutbox: Boolean,
)
