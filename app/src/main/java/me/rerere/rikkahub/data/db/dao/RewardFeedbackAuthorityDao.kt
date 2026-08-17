package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityRevisionEntity
import me.rerere.rikkahub.data.db.projection.RewardFeedbackTargetAuthorityProjection

@Dao
interface RewardFeedbackAuthorityDao {
    /** A result message is authoritative only when the command durably records a final save. */
    @Query(
        "SELECT id AS commandId, state, stateVersion, conversationId, authoritySubjectId, " +
            "assistantIdSnapshot, lineageId, branchAnchorMessageId, branchAnchorMessageRevision, " +
            "conversationSourceRevision, completionKind, resultAssistantMessageId, " +
            "resultAssistantMessageRevision FROM pending_chat_commands WHERE " +
            "resultAssistantMessageId = :targetMessageId " +
            "AND completionKind = 'GENERATION_FINAL_SAVED' " +
            "AND state IN ('COMPLETED', 'FAILED') " +
            "ORDER BY finishedAt DESC, id DESC LIMIT :limit",
    )
    suspend fun findTerminalCommandsForResult(
        targetMessageId: String,
        limit: Int,
    ): List<RewardFeedbackTargetAuthorityProjection>

    @Query(
        "SELECT * FROM learning_reward_feedback_authority WHERE feedback_id = :feedbackId LIMIT 1",
    )
    suspend fun findHead(feedbackId: String): RewardFeedbackAuthorityEntity?

    @Query(
        "SELECT * FROM learning_reward_feedback_authority WHERE " +
            "target_assistant_message_id = :targetMessageId AND source_state = 'ACTIVE' " +
            "ORDER BY feedback_id ASC LIMIT :limit",
    )
    suspend fun listActiveHeadsForTarget(
        targetMessageId: String,
        limit: Int,
    ): List<RewardFeedbackAuthorityEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeadIgnore(entity: RewardFeedbackAuthorityEntity): Long

    @Query(
        "UPDATE learning_reward_feedback_authority SET " +
            "conversation_id = :conversationId, " +
            "conversation_source_revision = :conversationSourceRevision, " +
            "command_id = :commandId, command_revision = :commandRevision, " +
            "lineage_id = :lineageId, " +
            "branch_anchor_message_id = :branchAnchorMessageId, " +
            "branch_anchor_message_revision = :branchAnchorMessageRevision, " +
            "signal_kind = :signalKind, value_milli = :valueMilli, " +
            "source_state = :sourceState, previous_source_revision = source_revision, " +
            "source_revision = :nextRevision, integrity_sha256 = :integritySha256, " +
            "updated_at_ms = :updatedAtMs " +
            "WHERE feedback_id = :feedbackId AND source_revision = :expectedRevision " +
            "AND scope_kind = :expectedScopeKind AND scope_id = :expectedScopeId " +
            "AND target_assistant_message_id = :expectedTargetMessageId " +
            "AND target_assistant_message_revision = :expectedTargetMessageRevision " +
            "AND dimension = :expectedDimension " +
            "AND source_state != 'TOMBSTONED' AND :nextRevision = :expectedRevision + 1 " +
            "AND :sourceState IN ('ACTIVE', 'TOMBSTONED') " +
            "AND ((:sourceState = 'ACTIVE' AND :valueMilli IS NOT NULL) OR " +
            "(:sourceState = 'TOMBSTONED' AND :valueMilli IS NULL))",
    )
    suspend fun updateHeadFenced(
        feedbackId: String,
        expectedRevision: Long,
        nextRevision: Long,
        expectedScopeKind: String,
        expectedScopeId: String,
        expectedTargetMessageId: String,
        expectedTargetMessageRevision: Long,
        expectedDimension: String,
        conversationId: String,
        conversationSourceRevision: Long,
        commandId: String,
        commandRevision: Long,
        lineageId: String,
        branchAnchorMessageId: String,
        branchAnchorMessageRevision: Long,
        signalKind: String,
        valueMilli: Int?,
        sourceState: String,
        integritySha256: String,
        updatedAtMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: RewardFeedbackAuthorityRevisionEntity)

    /** Stable keyset paging; no OFFSET gaps when a concurrent writer appends at the same time. */
    @Query(
        "SELECT * FROM learning_reward_feedback_revisions WHERE " +
            "updated_at_ms >= :fromInclusiveMs AND updated_at_ms < :toExclusiveMs AND " +
            "(updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND feedback_id > :afterFeedbackId) OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND feedback_id = :afterFeedbackId " +
            "AND source_revision > :afterSourceRevision)) " +
            "ORDER BY updated_at_ms ASC, feedback_id ASC, source_revision ASC LIMIT :limit",
    )
    suspend fun listRevisionPage(
        fromInclusiveMs: Long,
        toExclusiveMs: Long,
        afterUpdatedAtMs: Long,
        afterFeedbackId: String,
        afterSourceRevision: Long,
        limit: Int,
    ): List<RewardFeedbackAuthorityRevisionEntity>
}
