package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

/** One durable user-led task boundary. It contains references and derived metadata, never a chat. */
@Entity(
    tableName = "learning_episodes",
    indices = [
        Index(value = ["stream_id", "replay_generation", "lineage_id", "branch_anchor_message_id"], unique = true),
        Index(value = ["scope_kind", "scope_id", "status", "updated_at_ms"]),
        Index(value = ["scope_kind", "scope_id", "task_signature", "status"]),
        Index(value = ["conversation_id", "branch_anchor_message_id"]),
        Index(value = ["root_command_id", "root_command_revision"]),
    ],
)
data class LearningEpisodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "conversation_revision")
    val conversationRevision: Long?,
    @ColumnInfo(name = "root_command_id")
    val rootCommandId: String,
    @ColumnInfo(name = "root_command_revision")
    val rootCommandRevision: Long,
    @ColumnInfo(name = "final_command_id")
    val finalCommandId: String?,
    @ColumnInfo(name = "final_command_revision")
    val finalCommandRevision: Long?,
    @ColumnInfo(name = "lineage_id")
    val lineageId: String,
    @ColumnInfo(name = "branch_anchor_message_id")
    val branchAnchorMessageId: String,
    @ColumnInfo(name = "branch_anchor_message_revision")
    val branchAnchorMessageRevision: Long,
    @ColumnInfo(name = "result_assistant_message_id")
    val resultAssistantMessageId: String?,
    @ColumnInfo(name = "result_assistant_message_revision")
    val resultAssistantMessageRevision: Long?,
    @ColumnInfo(name = "generation_run_id")
    val generationRunId: String?,
    @ColumnInfo(name = "execution_id")
    val executionId: String?,
    @ColumnInfo(name = "task_signature")
    val taskSignature: String,
    val status: String,
    @ColumnInfo(name = "boundary_reason")
    val boundaryReason: String,
    val revision: Long,
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,
    @ColumnInfo(name = "finalized_at_ms")
    val finalizedAtMs: Long?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        requireLearningStorageId(id, "episode ID")
        require(runCatching { Uuid.parse(streamId) }.isSuccess) { "Invalid Learning stream ID" }
        require(replayGeneration >= 0L) { "Negative replay generation" }
        requireLearningScope(scopeKind, scopeId)
        listOf(conversationId, rootCommandId, lineageId, branchAnchorMessageId).forEach {
            requireLearningStorageId(it, "episode authority reference")
        }
        listOfNotNull(finalCommandId, resultAssistantMessageId, generationRunId, executionId).forEach {
            requireLearningStorageId(it, "episode optional authority reference")
        }
        require(rootCommandRevision > 0L) { "Invalid root command revision" }
        require(branchAnchorMessageRevision > 0L) { "Invalid branch anchor revision" }
        require(conversationRevision == null || conversationRevision > 0L) {
            "Invalid conversation revision"
        }
        require((finalCommandId == null) == (finalCommandRevision == null)) {
            "Incomplete final command reference"
        }
        require(finalCommandRevision == null || finalCommandRevision > 0L) {
            "Invalid final command revision"
        }
        require((resultAssistantMessageId == null) == (resultAssistantMessageRevision == null)) {
            "Incomplete result message reference"
        }
        require(resultAssistantMessageRevision == null || resultAssistantMessageRevision > 0L) {
            "Invalid result message revision"
        }
        requireLearningIdentity(taskSignature, "task signature")
        require(StoredLearningEpisodeStatus.entries.any { it.name == status }) { "Invalid episode status" }
        require(LearningEpisodeBoundaryReason.entries.any { it.name == boundaryReason }) {
            "Invalid episode boundary reason"
        }
        require(revision > 0L) { "Invalid episode revision" }
        require(startedAtMs >= 0L && createdAtMs >= 0L && updatedAtMs >= createdAtMs) {
            "Invalid episode clock"
        }
        require(startedAtMs >= createdAtMs) { "Episode starts before creation" }
        require(finalizedAtMs == null || finalizedAtMs >= startedAtMs) {
            "Episode finalization precedes start"
        }
        require((status == StoredLearningEpisodeStatus.OPEN.name) == (finalizedAtMs == null)) {
            "Episode terminal state and finalization disagree"
        }
    }

    override fun toString(): String =
        "LearningEpisodeEntity(status=$status, revision=$revision, scope=$scopeKind, ids=<redacted>)"
}
