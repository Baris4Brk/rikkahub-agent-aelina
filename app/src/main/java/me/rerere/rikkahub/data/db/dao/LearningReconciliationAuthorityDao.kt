package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import me.rerere.rikkahub.data.db.projection.LearningCommandTerminalAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningConversationSourceAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningExecutionTerminalAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningMessageSourceAuthorityProjection

/**
 * Narrow, read-only authority surface used by Learning bootstrap reconciliation.
 *
 * Both queries are stable keyset scans. Callers cap [limit] at 64 and hold the primary database
 * transaction while validating the outbox lineage and repairing the corresponding events.
 */
@Dao
interface LearningReconciliationAuthorityDao {
    @Query(
        """
        SELECT
            c.id AS command_id,
            c.state AS command_state,
            c.stateVersion AS command_state_version,
            c.conversationId AS command_conversation_id,
            c.conversationSourceRevision AS command_conversation_source_revision,
            c.authoritySubjectId AS command_authority_subject_id,
            c.assistantIdSnapshot AS command_assistant_id_snapshot,
            c.lineageId AS command_lineage_id,
            c.parentCommandId AS command_parent_id,
            c.branchAnchorMessageId AS command_branch_anchor_message_id,
            c.branchAnchorMessageRevision AS command_branch_anchor_message_revision,
            c.completionKind AS command_completion_kind,
            c.resultAssistantMessageId AS command_result_assistant_message_id,
            c.resultAssistantMessageRevision AS command_result_assistant_message_revision,
            c.finishedAt AS command_finished_at_ms
        FROM pending_chat_commands AS c
        WHERE c.state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'MANUAL_CONFIRMATION')
          AND c.finishedAt IS NOT NULL
          AND c.finishedAt >= :windowStartMs
          AND c.finishedAt <= :windowEndMs
          AND (
              :afterFinishedAtMs IS NULL
              OR c.finishedAt > :afterFinishedAtMs
              OR (c.finishedAt = :afterFinishedAtMs AND c.id > :afterId)
          )
        ORDER BY c.finishedAt ASC, c.id ASC
        LIMIT :limit
        """,
    )
    suspend fun listTerminalCommandsAfter(
        windowStartMs: Long,
        windowEndMs: Long,
        afterFinishedAtMs: Long?,
        afterId: String?,
        limit: Int,
    ): List<LearningCommandTerminalAuthorityProjection>

    @Query(
        """
        SELECT
            r.id AS execution_id,
            r.trace_id AS execution_trace_id,
            r.command_id AS execution_command_id,
            r.conversation_id AS execution_conversation_id,
            r.learning_scope_kind AS execution_learning_scope_kind,
            r.learning_scope_id AS execution_learning_scope_id,
            r.tool_call_id AS execution_tool_call_id,
            r.tool_name AS execution_tool_name,
            r.tool_schema_fingerprint AS execution_tool_schema_fingerprint,
            r.owning_assistant_message_id AS execution_owning_assistant_message_id,
            r.owning_assistant_message_revision AS execution_owning_assistant_message_revision,
            r.status AS execution_status,
            r.state_version AS execution_state_version,
            r.verification_state AS execution_verification_state,
            r.finished_at_ms AS execution_finished_at_ms,
            r.updated_at_ms AS execution_updated_at_ms,
            e.event_id AS terminal_event_id,
            e.execution_id AS terminal_event_execution_id,
            e.sequence AS terminal_event_sequence,
            e.previous_status AS terminal_event_previous_status,
            e.next_status AS terminal_event_next_status,
            e.next_verification AS terminal_event_next_verification,
            e.created_at_ms AS terminal_event_created_at_ms
        FROM execution_records AS r
        INNER JOIN execution_events AS e
          ON e.execution_id = r.id
         AND e.sequence = r.state_version
        WHERE r.status IN ('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown')
          AND r.finished_at_ms IS NOT NULL
          AND r.finished_at_ms >= :windowStartMs
          AND r.finished_at_ms <= :windowEndMs
          AND (
              :afterFinishedAtMs IS NULL
              OR r.finished_at_ms > :afterFinishedAtMs
              OR (r.finished_at_ms = :afterFinishedAtMs AND r.id > :afterId)
          )
        ORDER BY r.finished_at_ms ASC, r.id ASC
        LIMIT :limit
        """,
    )
    suspend fun listTerminalExecutionsAfter(
        windowStartMs: Long,
        windowEndMs: Long,
        afterFinishedAtMs: Long?,
        afterId: String?,
        limit: Int,
    ): List<LearningExecutionTerminalAuthorityProjection>

    /** Current heads only: revision N is sufficient authority to invalidate every revision < N. */
    @Query(
        """
        SELECT
            s.scope_kind AS source_scope_kind,
            s.scope_id AS source_scope_id,
            s.conversation_id AS source_conversation_id,
            s.source_revision AS source_revision,
            s.previous_source_revision AS source_previous_revision,
            s.source_state AS source_state,
            s.change_kind AS source_change_kind,
            s.occurred_at_ms AS source_occurred_at_ms,
            s.updated_at_ms AS source_updated_at_ms
        FROM learning_conversation_source_authority AS s
        WHERE s.updated_at_ms >= :windowStartMs
          AND s.updated_at_ms <= :windowEndMs
          AND (s.source_revision > 1 OR s.source_state != 'ACTIVE')
          AND (
              s.updated_at_ms > :afterUpdatedAtMs
              OR (s.updated_at_ms = :afterUpdatedAtMs AND s.conversation_id > :afterConversationId)
              OR (s.updated_at_ms = :afterUpdatedAtMs AND s.conversation_id = :afterConversationId
                  AND s.scope_kind > :afterScopeKind)
              OR (s.updated_at_ms = :afterUpdatedAtMs AND s.conversation_id = :afterConversationId
                  AND s.scope_kind = :afterScopeKind AND s.scope_id > :afterScopeId)
          )
        ORDER BY s.updated_at_ms ASC, s.conversation_id ASC, s.scope_kind ASC, s.scope_id ASC
        LIMIT :limit
        """,
    )
    suspend fun listConversationSourceHeadsAfter(
        windowStartMs: Long,
        windowEndMs: Long,
        afterUpdatedAtMs: Long,
        afterConversationId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<LearningConversationSourceAuthorityProjection>

    /**
     * The join supplies a current, same-scope Conversation authority revision without exposing
     * message payloads. A Conversation mutated after the frozen scan clock is excluded so the
     * scanner never binds a future head to an older frozen snapshot.
     */
    @Query(
        """
        SELECT
            m.scope_kind AS source_scope_kind,
            m.scope_id AS source_scope_id,
            m.conversation_id AS source_conversation_id,
            m.message_id AS source_message_id,
            m.source_revision AS source_revision,
            m.previous_source_revision AS source_previous_revision,
            m.source_state AS source_state,
            m.change_kind AS source_change_kind,
            c.source_revision AS source_conversation_revision,
            m.occurred_at_ms AS source_occurred_at_ms,
            m.updated_at_ms AS source_updated_at_ms
        FROM learning_message_source_authority AS m
        INNER JOIN learning_conversation_source_authority AS c
          ON c.scope_kind = m.scope_kind
         AND c.scope_id = m.scope_id
         AND c.conversation_id = m.conversation_id
        WHERE m.updated_at_ms >= :windowStartMs
          AND m.updated_at_ms <= :windowEndMs
          AND c.updated_at_ms <= :windowEndMs
          AND (m.source_revision > 1 OR m.source_state != 'ACTIVE')
          AND (
              m.updated_at_ms > :afterUpdatedAtMs
              OR (m.updated_at_ms = :afterUpdatedAtMs AND m.conversation_id > :afterConversationId)
              OR (m.updated_at_ms = :afterUpdatedAtMs AND m.conversation_id = :afterConversationId
                  AND m.message_id > :afterMessageId)
              OR (m.updated_at_ms = :afterUpdatedAtMs AND m.conversation_id = :afterConversationId
                  AND m.message_id = :afterMessageId AND m.scope_kind > :afterScopeKind)
              OR (m.updated_at_ms = :afterUpdatedAtMs AND m.conversation_id = :afterConversationId
                  AND m.message_id = :afterMessageId AND m.scope_kind = :afterScopeKind
                  AND m.scope_id > :afterScopeId)
          )
        ORDER BY m.updated_at_ms ASC, m.conversation_id ASC, m.message_id ASC,
            m.scope_kind ASC, m.scope_id ASC
        LIMIT :limit
        """,
    )
    suspend fun listMessageSourceHeadsAfter(
        windowStartMs: Long,
        windowEndMs: Long,
        afterUpdatedAtMs: Long,
        afterConversationId: String,
        afterMessageId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<LearningMessageSourceAuthorityProjection>
}
