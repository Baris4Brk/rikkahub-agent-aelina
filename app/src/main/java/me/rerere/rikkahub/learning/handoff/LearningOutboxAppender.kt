package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningOutboxDao
import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventContract
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import kotlin.uuid.Uuid

data class LearningOutboxDraft(
    val streamId: Uuid,
    val eventCode: LearningEventCode,
    val source: LearningSourceRef?,
    val sourceTypeCode: String? = source?.sourceKind?.name,
    val correlation: LearningCorrelation,
    val terminalStateCode: String?,
    val createdAtMs: Long,
) {
    init {
        require(createdAtMs >= 0L) { "Negative outbox time" }
        require(eventCode.decodeState == LearningEventDecodeState.KNOWN) {
            "Producer cannot emit an unknown or incompatible learning event"
        }
        require(source == null || source.sourceKind != LearningSourceKind.UNKNOWN) {
            "Producer cannot emit an unknown source kind"
        }
        require(source == null || source.occurredAtMs <= createdAtMs) {
            "Learning event occurs after its outbox creation time"
        }
        require(correlation.previousSourceRevision == null || correlation.previousSourceRevision > 0L)
        require(
            correlation.previousSourceRevision == null ||
                (source?.sourceRevision != null && correlation.previousSourceRevision < source.sourceRevision),
        ) { "Previous source revision requires a newer current revision" }
        require(
            correlation.sourceStateCode == null ||
                correlation.sourceStateCode.matches(Regex("[A-Z][A-Z0-9_]{0,63}")),
        )
        if (eventCode.knownType == LearningEventType.SOURCE_INVALIDATED) {
            require(eventCode.schemaVersion >= 2)
            require(correlation.previousSourceRevision != null && correlation.sourceStateCode != null) {
                "Source invalidation requires a monotonic transition"
            }
        } else {
            require(correlation.previousSourceRevision == null && correlation.sourceStateCode == null) {
                "Only source invalidation carries a source transition"
            }
        }
        LearningEventContract.requireValid(
            streamId = streamId,
            eventCode = eventCode,
            source = source,
            sourceTypeCode = sourceTypeCode,
            missingRevisionReasonCode = source?.missingRevisionReason?.name,
            terminalStateCode = terminalStateCode,
            correlation = correlation,
        )
    }

    fun toEntity(): LearningOutboxEntity {
        val eventId = if (eventCode.knownType == LearningEventType.STREAM_INIT) {
            LEARNING_STREAM_INIT_EVENT_ID
        } else {
            val businessSource = requireNotNull(source)
            LearningCanonicalId.eventId(
                streamId = streamId,
                eventType = requireNotNull(eventCode.knownType) {
                    "Unknown event type cannot be emitted by this producer version"
                },
                eventSchemaVersion = eventCode.schemaVersion,
                sourceKindCode = requireNotNull(sourceTypeCode),
                sourceId = businessSource.sourceId,
                sourceRevision = businessSource.sourceRevision,
                terminalState = terminalStateCode,
                previousSourceRevision = correlation.previousSourceRevision,
                sourceStateCode = correlation.sourceStateCode,
                correlation = correlation,
            )
        }
        return LearningOutboxEntity(
            streamId = streamId.toString(),
            eventId = eventId,
            eventType = eventCode.rawCode,
            eventSchemaVersion = eventCode.schemaVersion,
            terminalState = terminalStateCode,
            sourceType = sourceTypeCode,
            sourceId = source?.sourceId,
            sourceRevision = source?.sourceRevision,
            previousSourceRevision = correlation.previousSourceRevision,
            sourceState = correlation.sourceStateCode,
            missingRevisionReason = source?.missingRevisionReason?.name,
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
            occurredAtMs = source?.occurredAtMs,
            createdAtMs = createdAtMs,
        )
    }

    override fun toString(): String =
        "LearningOutboxDraft(type=${eventCode.knownType}, schema=${eventCode.schemaVersion}, " +
            "source=${source != null}, scope=${source?.scope?.kind}, ids=<redacted>)"
}

sealed interface LearningOutboxAppendResult {
    data class Inserted(val sequence: Long) : LearningOutboxAppendResult

    data class Duplicate(val sequence: Long) : LearningOutboxAppendResult
}

/**
 * Appends a content-free event to the authority database.
 *
 * This deliberately never starts a transaction. The caller must already be inside the exact Room
 * transaction that commits the source authority mutation. The validated database lineage is
 * supplied to [draftFactory], so a business writer cannot guess, cache, or import a stale stream.
 */
class LearningOutboxAppender(
    private val database: AppDatabase,
    private val dao: LearningOutboxDao = database.learningOutboxDao(),
) {
    suspend fun appendInCurrentAuthorityTransaction(
        draftFactory: (streamId: Uuid) -> LearningOutboxDraft,
    ): LearningOutboxAppendResult {
        requireLearningOutboxAuthorityTransaction(database.inTransaction())
        val streamId = readHealthyLearningOutboxStream(dao)
        return appendValidatedBusinessDraft(dao, streamId, draftFactory)
    }
}

/** Reads the complete lineage proof needed by a writer while its authority transaction is held. */
internal suspend fun readHealthyLearningOutboxStream(dao: LearningOutboxDao): Uuid {
    val sentinels = try {
        dao.listStreamSentinels()
    } catch (_: IllegalArgumentException) {
        throw LearningOutboxHealthException(LearningOutboxHealthError.INVALID_STREAM_SENTINEL)
    }
    val stream = validateLearningOutboxSentinels(sentinels)
    val distinctStreams = try {
        dao.listDistinctStreamIds()
    } catch (_: IllegalArgumentException) {
        throw LearningOutboxHealthException(LearningOutboxHealthError.MIXED_STREAMS)
    }
    validateLearningOutboxStreams(stream, distinctStreams)
    return stream
}

internal fun requireLearningOutboxAuthorityTransaction(inTransaction: Boolean) {
    check(inTransaction) { "learning_outbox_authority_transaction_required" }
}

/** Internal protocol seam for deterministic JVM tests; production callers use the appender. */
internal suspend fun appendValidatedBusinessDraft(
    dao: LearningOutboxDao,
    streamId: Uuid,
    draftFactory: (streamId: Uuid) -> LearningOutboxDraft,
): LearningOutboxAppendResult {
    val draft = draftFactory(streamId)
    require(draft.eventCode.knownType != LearningEventType.STREAM_INIT) {
        "learning_outbox_business_writer_cannot_emit_stream_init"
    }
    require(draft.streamId == streamId && draft.source?.databaseStreamId == streamId) {
        "learning_outbox_draft_stream_mismatch"
    }
    val row = draft.toEntity()
    val insertedSeq = dao.insertIgnore(row)
    if (insertedSeq != -1L) return LearningOutboxAppendResult.Inserted(insertedSeq)
    val existing = dao.findByEventId(row.eventId)
        ?: throw LearningHandoffIdentityConflictException(
            "Outbox uniqueness conflict without the expected event",
        )
    if (!existing.hasSameOutboxIdentityAs(row)) {
        throw LearningHandoffIdentityConflictException(
            "Same outbox event ID has different authoritative fields",
        )
    }
    return LearningOutboxAppendResult.Duplicate(existing.seq)
}

internal fun LearningOutboxEntity.hasSameOutboxIdentityAs(other: LearningOutboxEntity): Boolean =
    copy(seq = 0L) == other.copy(seq = 0L)
