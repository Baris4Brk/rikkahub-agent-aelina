package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningInboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: LearningInboxEventEntity): Long

    @Query(
        "SELECT * FROM learning_inbox_events " +
            "WHERE stream_id = :streamId AND event_id = :eventId LIMIT 1",
    )
    suspend fun find(streamId: String, eventId: String): LearningInboxEventEntity?

    @Query(
        "SELECT * FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND outbox_seq > :afterSeq ORDER BY outbox_seq ASC LIMIT :limit",
    )
    suspend fun listAfter(streamId: String, afterSeq: Long, limit: Int): List<LearningInboxEventEntity>

    @Query(
        "SELECT * FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND event_type_code = 'COMMAND_ADMITTED' " +
            "AND command_id = :lineageId AND lineage_id = :lineageId " +
            "AND source_revision IS NOT NULL AND event_schema_version = 2 " +
            "ORDER BY outbox_seq ASC LIMIT 2",
    )
    suspend fun findRootAdmissionCandidates(
        streamId: String,
        replayGeneration: Long,
        lineageId: String,
    ): List<LearningInboxEventEntity>

    @Query(
        "SELECT * FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND event_type_code = 'COMMAND_TERMINAL' " +
            "AND command_id = :commandId AND source_revision = :commandRevision " +
            "AND event_schema_version = 2 " +
            "ORDER BY outbox_seq ASC LIMIT 2",
    )
    suspend fun findTerminalCommandCandidates(
        streamId: String,
        replayGeneration: Long,
        commandId: String,
        commandRevision: Long,
    ): List<LearningInboxEventEntity>

    @Query(
        "SELECT * FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND event_type_code = 'EXECUTION_TERMINAL' " +
            "AND event_schema_version = 2 AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId " +
            "AND command_id IN (:rootCommandId, :finalCommandId) " +
            "AND tool_name IS NOT NULL AND tool_schema_fingerprint IS NOT NULL " +
            "ORDER BY outbox_seq ASC LIMIT :limit",
    )
    suspend fun listExecutionTerminalsForEpisode(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        rootCommandId: String,
        finalCommandId: String,
        limit: Int,
    ): List<LearningInboxEventEntity>

    @Query(
        "SELECT COUNT(*) FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND event_type_code = 'EXECUTION_TERMINAL' " +
            "AND event_schema_version = 2 AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId " +
            "AND command_id IN (:rootCommandId, :finalCommandId)",
    )
    suspend fun countExecutionTerminalsForEpisode(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        rootCommandId: String,
        finalCommandId: String,
    ): Long

    @Query(
        "SELECT MAX(outbox_seq) FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration",
    )
    suspend fun maxSequence(streamId: String, replayGeneration: Long): Long?

    @Query("SELECT MAX(replay_generation) FROM learning_inbox_events")
    suspend fun maxReplayGeneration(): Long?

    /**
     * Returns a bounded page whose derived interpretation predates [targetInterpretationVersion].
     * Raw event/source/correlation columns remain authoritative; decode_state is only a projection.
     */
    @Query(
        "SELECT * FROM learning_inbox_events WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND outbox_seq > :afterSeq " +
            "AND interpretation_version < :targetInterpretationVersion " +
            "ORDER BY outbox_seq ASC LIMIT 64",
    )
    suspend fun listNextInterpretationPage(
        streamId: String,
        replayGeneration: Long,
        afterSeq: Long,
        targetInterpretationVersion: Int,
    ): List<LearningInboxEventEntity>

    /**
     * CAS for a future versioned reinterpreter. A caller that promotes a row to KNOWN must execute
     * this CAS and its deduplicated job insert in one [LearningDatabase] transaction. Production
     * callers use LearningInboxReinterpreter; this primitive is exposed only for Room generation.
     */
    @Query(
        "UPDATE learning_inbox_events SET decode_state = :newDecodeState, " +
            "interpretation_version = :targetInterpretationVersion " +
            "WHERE stream_id = :streamId AND event_id = :eventId " +
            "AND replay_generation = :replayGeneration " +
            "AND interpretation_version = :expectedInterpretationVersion " +
            "AND decode_state = :expectedDecodeState " +
            "AND :targetInterpretationVersion > :expectedInterpretationVersion " +
            "AND :newDecodeState IN ('KNOWN', 'UNKNOWN_NO_JOB', 'INCOMPATIBLE_SCHEMA')",
    )
    suspend fun reinterpretIfCurrent(
        streamId: String,
        eventId: String,
        replayGeneration: Long,
        expectedInterpretationVersion: Int,
        expectedDecodeState: String,
        targetInterpretationVersion: Int,
        newDecodeState: String,
    ): Int

    @Query("DELETE FROM learning_inbox_events")
    suspend fun deleteAll(): Int

    @Query(
        "DELETE FROM learning_inbox_events WHERE scope_kind = :scopeKind AND scope_id = :scopeId",
    )
    suspend fun deleteByScope(scopeKind: String, scopeId: String): Int

    /**
     * Deletes only fully consumed, known, ordinary events without active derived work. Source
     * lifecycle rows, unknown schemas and STREAM_INIT remain as the minimal replay/audit floor.
     */
    @Query(
        "DELETE FROM learning_inbox_events WHERE rowid IN (" +
            "SELECT i.rowid FROM learning_inbox_events i " +
            "WHERE i.stream_id = :streamId AND i.replay_generation = :replayGeneration " +
            "AND i.outbox_seq <= :throughContiguousSeq AND i.ingested_at_ms < :ingestedBeforeMs " +
            "AND i.decode_state = 'KNOWN' AND i.event_type_code != 'STREAM_INIT' " +
            "AND i.source_state IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM learning_jobs j WHERE j.stream_id = i.stream_id " +
            "AND j.replay_generation = i.replay_generation " +
            "AND j.source_event_id = i.event_id " +
            "AND j.state IN ('PENDING','RETRY','RUNNING','DEAD_LETTER')) " +
            "ORDER BY i.outbox_seq ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredConsumedPage(
        streamId: String,
        replayGeneration: Long,
        throughContiguousSeq: Long,
        ingestedBeforeMs: Long,
        limit: Int,
    ): Int
}

@Dao
interface LearningCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(checkpoint: LearningStreamCheckpointEntity)

    @Query("SELECT * FROM learning_stream_checkpoints WHERE stream_id = :streamId LIMIT 1")
    suspend fun find(streamId: String): LearningStreamCheckpointEntity?

    @Query("SELECT * FROM learning_stream_checkpoints")
    suspend fun listAll(): List<LearningStreamCheckpointEntity>

    @Query(
        "SELECT reconciliation_cursor_v1_json FROM learning_stream_checkpoints " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration LIMIT 1",
    )
    suspend fun findReconciliationCursor(
        streamId: String,
        replayGeneration: Long,
    ): String?

    /**
     * Exact durable-page CAS. SQLite `IS` deliberately makes a null expected cursor comparable,
     * so first-page publication and process-death resume use the same fenced primitive.
     */
    @Query(
        "UPDATE learning_stream_checkpoints SET " +
            "reconciliation_cursor_v1_json = :newCursorJson, updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND reconciliation_cursor_v1_json IS :expectedCursorJson " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun compareAndSetReconciliationCursor(
        streamId: String,
        replayGeneration: Long,
        expectedCursorJson: String?,
        newCursorJson: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_stream_checkpoints SET " +
            "reconciliation_cursor_v1_json = NULL, updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND reconciliation_cursor_v1_json IS :expectedCursorJson " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun clearReconciliationCursor(
        streamId: String,
        replayGeneration: Long,
        expectedCursorJson: String,
        updatedAtMs: Long,
    ): Int

    @Query("SELECT MAX(replay_generation) FROM learning_stream_checkpoints")
    suspend fun maxReplayGeneration(): Long?

    @Query(
        "UPDATE learning_stream_checkpoints SET " +
            "last_contiguous_seq = :lastContiguousSeq, " +
            "last_seen_head_seq = :lastSeenHeadSeq, updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND last_contiguous_seq = :expectedPreviousSeq " +
            "AND bootstrap_state IN ('COMPLETE', 'RUNNING') " +
            "AND last_seen_head_seq <= :lastSeenHeadSeq " +
            "AND updated_at_ms <= :updatedAtMs " +
            "AND :lastContiguousSeq > :expectedPreviousSeq " +
            "AND :lastContiguousSeq <= :lastSeenHeadSeq",
    )
    suspend fun advanceContiguously(
        streamId: String,
        replayGeneration: Long,
        expectedPreviousSeq: Long,
        lastContiguousSeq: Long,
        lastSeenHeadSeq: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_stream_checkpoints SET bootstrap_state = 'RUNNING', " +
            "bootstrap_head_seq = COALESCE(bootstrap_head_seq, :bootstrapHeadSeq), " +
            "last_seen_head_seq = :observedHeadSeq, " +
            "updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND bootstrap_state IN ('REQUIRED', 'DEGRADED') " +
            "AND updated_at_ms <= :updatedAtMs " +
            "AND COALESCE(bootstrap_head_seq, :bootstrapHeadSeq) > 0 " +
            "AND last_contiguous_seq <= COALESCE(bootstrap_head_seq, :bootstrapHeadSeq) " +
            "AND :observedHeadSeq >= last_seen_head_seq " +
            "AND :observedHeadSeq >= COALESCE(bootstrap_head_seq, :bootstrapHeadSeq)",
    )
    suspend fun startBootstrap(
        streamId: String,
        replayGeneration: Long,
        bootstrapHeadSeq: Long,
        observedHeadSeq: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_stream_checkpoints SET bootstrap_state = 'COMPLETE', " +
            "last_contiguous_seq = :expectedBootstrapHeadSeq, " +
            "last_seen_head_seq = :observedHeadSeq, " +
            "coverage_start_ms = :coverageStartMs, " +
            "command_coverage_start_ms = :commandCoverageStartMs, " +
            "execution_coverage_start_ms = :executionCoverageStartMs, " +
            "source_authority_coverage_start_ms = :sourceAuthorityCoverageStartMs, " +
            "feedback_coverage_start_ms = :feedbackCoverageStartMs, " +
            "reconciliation_cursor_v1_json = NULL, " +
            "updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND bootstrap_state = 'RUNNING' AND bootstrap_head_seq = :expectedBootstrapHeadSeq " +
            "AND reconciliation_cursor_v1_json IS :expectedReconciliationCursorJson " +
            "AND last_contiguous_seq = :expectedBootstrapHeadSeq " +
            "AND :observedHeadSeq >= :expectedBootstrapHeadSeq " +
            "AND :observedHeadSeq >= last_seen_head_seq " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun completeBootstrap(
        streamId: String,
        replayGeneration: Long,
        expectedBootstrapHeadSeq: Long,
        observedHeadSeq: Long,
        coverageStartMs: Long?,
        commandCoverageStartMs: Long?,
        executionCoverageStartMs: Long?,
        sourceAuthorityCoverageStartMs: Long?,
        feedbackCoverageStartMs: Long?,
        expectedReconciliationCursorJson: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_stream_checkpoints SET bootstrap_state = 'DEGRADED', " +
            "updated_at_ms = MAX(updated_at_ms, :updatedAtMs) WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration " +
            "AND bootstrap_state IN ('REQUIRED', 'RUNNING')",
    )
    suspend fun markBootstrapDegraded(
        streamId: String,
        replayGeneration: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_stream_checkpoints SET bootstrap_state = 'DEGRADED', " +
            "updated_at_ms = MAX(updated_at_ms, :updatedAtMs) " +
            "WHERE bootstrap_state = 'RUNNING'",
    )
    suspend fun recoverInterruptedBootstrap(updatedAtMs: Long): Int

    @Query("DELETE FROM learning_stream_checkpoints")
    suspend fun deleteAll(): Int
}
