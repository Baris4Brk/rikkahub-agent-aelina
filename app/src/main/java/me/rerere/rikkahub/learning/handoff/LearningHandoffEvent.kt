package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.learning.model.CURRENT_LEARNING_EVENT_INTERPRETATION_VERSION
import me.rerere.rikkahub.learning.model.LEARNING_STREAM_INIT_EVENT_ID_V1
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventContract
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import kotlin.uuid.Uuid

const val LEARNING_STREAM_INIT_EVENT_ID: String = LEARNING_STREAM_INIT_EVENT_ID_V1

/** Typed, content-free transfer object between the main outbox and the derived database. */
data class LearningHandoffEvent(
    val streamId: Uuid,
    val eventId: String,
    val outboxSeq: Long,
    val eventCode: LearningEventCode,
    val source: LearningSourceRef?,
    val sourceTypeCode: String? = source?.sourceKind?.name,
    /** Preserves a future reason code even when this consumer can only type it as UNKNOWN. */
    val missingRevisionReasonCode: String? = source?.missingRevisionReason?.name,
    val terminalStateCode: String? = null,
    val correlation: LearningCorrelation,
    val rewardDimensionCode: String? = null,
    val rewardSignalKindCode: String? = null,
    val rewardValueMilli: Int? = null,
    val executionVerificationStateCode: String? = null,
    val createdAtMs: Long,
) {
    init {
        require(outboxSeq > 0L) { "Outbox sequence must be positive" }
        require(eventId.length in 1..160 && eventId.all(::isSafeEventIdChar)) {
            "Invalid learning event identifier"
        }
        require(createdAtMs >= 0L) { "Negative event creation time" }
        require(source == null || source.occurredAtMs <= createdAtMs) {
            "Learning event occurs after its outbox creation time"
        }
        LearningEventContract.requireValid(
            streamId = streamId,
            eventCode = eventCode,
            source = source,
            sourceTypeCode = sourceTypeCode,
            missingRevisionReasonCode = missingRevisionReasonCode,
            terminalStateCode = terminalStateCode,
            correlation = correlation,
            rewardDimensionCode = rewardDimensionCode,
            rewardSignalKindCode = rewardSignalKindCode,
            rewardValueMilli = rewardValueMilli,
            executionVerificationStateCode = executionVerificationStateCode,
        )

        val knownType = eventCode.knownType
        if (knownType == LearningEventType.STREAM_INIT) {
            require(eventId == LEARNING_STREAM_INIT_EVENT_ID) { "Invalid stream sentinel identity" }
        } else if (knownType != null && eventCode.decodeState == LearningEventDecodeState.KNOWN) {
            val businessSource = requireNotNull(source)
            val expectedId = LearningCanonicalId.eventId(
                streamId = streamId,
                eventType = knownType,
                eventSchemaVersion = eventCode.schemaVersion,
                sourceKindCode = requireNotNull(sourceTypeCode),
                sourceId = businessSource.sourceId,
                sourceRevision = businessSource.sourceRevision,
                terminalState = terminalStateCode,
                previousSourceRevision = correlation.previousSourceRevision,
                sourceStateCode = correlation.sourceStateCode,
                correlation = correlation,
                rewardDimensionCode = rewardDimensionCode,
                rewardSignalKindCode = rewardSignalKindCode,
                rewardValueMilli = rewardValueMilli,
                executionVerificationStateCode = executionVerificationStateCode,
            )
            require(eventId == expectedId) { "Learning event canonical identity mismatch" }
        }
    }

    fun toInboxEntity(ingestedAtMs: Long, replayGeneration: Long): LearningInboxEventEntity {
        require(ingestedAtMs >= 0L) { "Negative ingestion time" }
        require(ingestedAtMs >= createdAtMs) { "Ingestion precedes outbox creation" }
        require(replayGeneration >= 0L) { "Negative replay generation" }
        return LearningInboxEventEntity(
            streamId = streamId.toString(),
            eventId = eventId,
            outboxSeq = outboxSeq,
            eventTypeCode = eventCode.rawCode,
            eventSchemaVersion = eventCode.schemaVersion,
            terminalState = terminalStateCode,
            decodeState = eventCode.decodeState.name,
            interpretationVersion = CURRENT_LEARNING_EVENT_INTERPRETATION_VERSION,
            sourceType = sourceTypeCode,
            sourceId = source?.sourceId,
            sourceRevision = source?.sourceRevision,
            previousSourceRevision = correlation.previousSourceRevision,
            sourceState = correlation.sourceStateCode,
            missingRevisionReason = missingRevisionReasonCode,
            scopeKind = source?.scope?.kind?.name,
            scopeId = source?.scope?.storageId,
            conversationId = correlation.conversationId,
            conversationSourceRevision = correlation.conversationSourceRevision,
            commandId = correlation.commandId,
            lineageId = correlation.lineageId,
            parentCommandId = correlation.parentCommandId,
            branchAnchorMessageId = correlation.branchAnchorMessageId,
            branchAnchorMessageRevision = correlation.branchAnchorMessageRevision,
            completionKind = correlation.completionKindCode,
            generationRunId = correlation.generationRunId,
            executionId = correlation.executionId,
            toolCallId = correlation.toolCallId,
            toolName = correlation.toolName,
            toolSchemaFingerprint = correlation.toolSchemaFingerprint,
            messageId = correlation.messageId,
            messageRevision = correlation.messageRevision,
            rewardDimension = rewardDimensionCode,
            rewardSignalKind = rewardSignalKindCode,
            rewardValueMilli = rewardValueMilli,
            executionVerificationState = executionVerificationStateCode,
            occurredAtMs = source?.occurredAtMs,
            createdAtMs = createdAtMs,
            ingestedAtMs = ingestedAtMs,
            replayGeneration = replayGeneration,
        )
    }

    override fun toString(): String =
        "LearningHandoffEvent(seq=$outboxSeq, type=${eventCode.knownType}, " +
            "schema=${eventCode.schemaVersion}, decode=${eventCode.decodeState}, " +
            "source=${source != null}, scope=${source?.scope?.kind}, ids=<redacted>)"
}

internal fun LearningInboxEventEntity.hasSameAuthoritativeIdentityAs(
    other: LearningInboxEventEntity,
): Boolean =
    copy(
        decodeState = LearningEventDecodeState.UNKNOWN_NO_JOB.name,
        interpretationVersion = 1,
        ingestedAtMs = createdAtMs,
    ) == other.copy(
        decodeState = LearningEventDecodeState.UNKNOWN_NO_JOB.name,
        interpretationVersion = 1,
        ingestedAtMs = other.createdAtMs,
    )

internal fun LearningInboxEventEntity.isSafeToCreateJob(): Boolean =
    interpretationVersion == CURRENT_LEARNING_EVENT_INTERPRETATION_VERSION &&
        decodeState == LearningEventDecodeState.KNOWN.name &&
        runCatching { LearningEventCode(eventTypeCode, eventSchemaVersion).producesJob }
            .getOrDefault(false) &&
        scopeKind != null &&
        scopeId != null &&
        sourceType != null &&
        LearningSourceKind.entries.any { it != LearningSourceKind.UNKNOWN && it.name == sourceType } &&
        sourceId != null

private fun isSafeEventIdChar(char: Char): Boolean =
    char in 'a'..'z' ||
        char in 'A'..'Z' ||
        char in '0'..'9' ||
        char == '-' ||
        char == '_' ||
        char == ':' ||
        char == '.'
