package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.isSafeLearningCode
import me.rerere.rikkahub.learning.model.isSafeLearningIdentifier
import kotlin.uuid.Uuid

/**
 * A privacy-minimal copy of an authoritative main-database outbox event.
 *
 * This table intentionally has no payload/blob/json column. Unknown event codes are retained in
 * [eventTypeCode] and [eventSchemaVersion], but the consumer must not create work for them.
 * [decodeState] is the persisted result produced by [interpretationVersion]; entity construction
 * deliberately does not recompute it with the currently installed code.
 */
@Entity(
    tableName = "learning_inbox_events",
    primaryKeys = ["stream_id", "event_id"],
    indices = [
        Index(value = ["stream_id", "outbox_seq"], unique = true),
        Index(value = ["stream_id", "decode_state", "outbox_seq"]),
        Index(value = ["interpretation_version", "stream_id", "outbox_seq"]),
        Index(value = ["source_type", "source_id"]),
    ],
)
data class LearningInboxEventEntity(
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "outbox_seq")
    val outboxSeq: Long,
    @ColumnInfo(name = "event_type_code")
    val eventTypeCode: String,
    @ColumnInfo(name = "event_schema_version")
    val eventSchemaVersion: Int,
    @ColumnInfo(name = "terminal_state")
    val terminalState: String?,
    @ColumnInfo(name = "decode_state")
    val decodeState: String,
    @ColumnInfo(name = "interpretation_version")
    val interpretationVersion: Int,
    @ColumnInfo(name = "source_type")
    val sourceType: String?,
    @ColumnInfo(name = "source_id")
    val sourceId: String?,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long?,
    @ColumnInfo(name = "previous_source_revision")
    val previousSourceRevision: Long? = null,
    @ColumnInfo(name = "source_state")
    val sourceState: String? = null,
    @ColumnInfo(name = "missing_revision_reason")
    val missingRevisionReason: String?,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String?,
    @ColumnInfo(name = "scope_id")
    val scopeId: String?,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "conversation_source_revision")
    val conversationSourceRevision: Long? = null,
    @ColumnInfo(name = "command_id")
    val commandId: String?,
    @ColumnInfo(name = "lineage_id")
    val lineageId: String?,
    @ColumnInfo(name = "parent_command_id")
    val parentCommandId: String?,
    @ColumnInfo(name = "branch_anchor_message_id")
    val branchAnchorMessageId: String?,
    @ColumnInfo(name = "branch_anchor_message_revision")
    val branchAnchorMessageRevision: Long? = null,
    @ColumnInfo(name = "completion_kind")
    val completionKind: String? = null,
    @ColumnInfo(name = "generation_run_id")
    val generationRunId: String?,
    @ColumnInfo(name = "execution_id")
    val executionId: String?,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String?,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_schema_fingerprint")
    val toolSchemaFingerprint: String? = null,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    @ColumnInfo(name = "message_revision")
    val messageRevision: Long? = null,
    @ColumnInfo(name = "reward_dimension")
    val rewardDimension: String? = null,
    @ColumnInfo(name = "reward_signal_kind")
    val rewardSignalKind: String? = null,
    @ColumnInfo(name = "reward_value_milli")
    val rewardValueMilli: Int? = null,
    @ColumnInfo(name = "execution_verification_state")
    val executionVerificationState: String? = null,
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "ingested_at_ms")
    val ingestedAtMs: Long,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
) {
    init {
        require(runCatching { Uuid.parse(streamId) }.isSuccess) { "Invalid learning stream ID" }
        require(isSafeLearningIdentifier(eventId, MAX_EVENT_ID_CHARS)) {
            "Invalid learning event ID"
        }
        require(outboxSeq > 0L) { "Learning inbox sequence must be positive" }
        require(eventTypeCode.isSafeLearningCode()) { "Invalid learning inbox event code" }
        require(eventSchemaVersion > 0) { "Invalid learning inbox event schema version" }
        require(LearningEventDecodeState.entries.any { it.name == decodeState }) {
            "Invalid learning inbox decode state"
        }
        require(interpretationVersion > 0) { "Invalid learning event interpretation version" }
        listOfNotNull(
            terminalState,
            sourceType,
            sourceState,
            missingRevisionReason,
            scopeKind,
            completionKind,
            rewardDimension,
            rewardSignalKind,
            executionVerificationState,
        ).forEach { code ->
            require(code.isSafeLearningCode()) { "Invalid learning inbox code" }
        }
        listOfNotNull(
            sourceId,
            scopeId,
            conversationId,
            commandId,
            lineageId,
            parentCommandId,
            branchAnchorMessageId,
            generationRunId,
            executionId,
            toolCallId,
            toolName,
            toolSchemaFingerprint,
            messageId,
        ).forEach { id ->
            require(isSafeLearningIdentifier(id, MAX_REFERENCE_ID_CHARS)) {
                "Invalid learning inbox reference ID"
            }
        }

        val hasSource = sourceId != null
        require((sourceType != null) == hasSource && (occurredAtMs != null) == hasSource) {
            "Incomplete learning inbox source"
        }
        require((scopeKind != null) == hasSource && (scopeId != null) == hasSource) {
            "Incomplete learning inbox scope"
        }
        if (hasSource) {
            require(
                LearningScope.parseOrNull(requireNotNull(scopeKind), requireNotNull(scopeId)) != null
            ) { "Invalid learning inbox scope" }
            require((sourceRevision == null) == (missingRevisionReason != null)) {
                "A missing source revision requires exactly one reason"
            }
        } else {
            require(sourceRevision == null && missingRevisionReason == null) {
                "A source-less event cannot have revision metadata"
            }
        }
        require(sourceRevision == null || sourceRevision >= 0L) { "Negative source revision" }
        require(previousSourceRevision == null || previousSourceRevision > 0L) {
            "Invalid previous source revision"
        }
        require(
            previousSourceRevision == null ||
                (sourceRevision != null && previousSourceRevision < sourceRevision)
        ) { "Learning source revision did not advance" }
        listOfNotNull(
            conversationSourceRevision,
            branchAnchorMessageRevision,
            messageRevision,
        ).forEach { require(it > 0L) { "Invalid Learning authority revision" } }
        require((toolName == null) == (toolSchemaFingerprint == null)) {
            "Learning tool identity requires a name/fingerprint pair"
        }
        require(toolSchemaFingerprint == null || toolSchemaFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid tool schema fingerprint"
        }
        require((messageId == null) == (messageRevision == null)) {
            "Learning message identity requires an exact revision"
        }
        require((rewardDimension == null) == (rewardSignalKind == null)) {
            "Learning reward authority requires a dimension/kind pair"
        }
        rewardDimension?.let { dimension ->
            require(LearningRewardDimension.entries.any { it.name == dimension }) {
                "Invalid Learning reward dimension"
            }
        }
        rewardSignalKind?.let { kind ->
            require(LearningRewardSignalKind.entries.any { it.name == kind }) {
                "Invalid Learning reward signal kind"
            }
        }
        require(rewardValueMilli == null || rewardValueMilli in -1_000..1_000) {
            "Learning reward milli-value is outside its bound"
        }
        require(rewardValueMilli == null || rewardDimension != null) {
            "Learning reward value has no authority dimension"
        }
        executionVerificationState?.let { state ->
            require(LearningExecutionVerificationState.entries.any { it.name == state }) {
                "Invalid Learning execution verification state"
            }
        }
        require(occurredAtMs == null || occurredAtMs >= 0L) { "Negative occurrence time" }
        require(createdAtMs >= 0L) { "Negative event creation time" }
        require(occurredAtMs == null || occurredAtMs <= createdAtMs) {
            "Learning event occurs after its outbox creation time"
        }
        require(ingestedAtMs >= createdAtMs) { "Ingestion precedes outbox creation" }
        require(replayGeneration >= 0L) { "Negative replay generation" }
    }

    override fun toString(): String =
        "LearningInboxEventEntity(seq=$outboxSeq, schema=$eventSchemaVersion, decode=$decodeState, " +
            "interpretation=$interpretationVersion, source=${sourceId != null}, " +
            "scope=${scopeKind != null}, raw-codes-and-ids=<redacted>)"
}

private const val MAX_EVENT_ID_CHARS = 160
private const val MAX_REFERENCE_ID_CHARS = 256

enum class LearningExecutionVerificationState {
    NOT_APPLICABLE,
    UNVERIFIED,
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
}
