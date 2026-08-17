package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningEpisodeDao {
    @Query("SELECT COUNT(*) FROM learning_episodes")
    suspend fun countEpisodes(): Long

    @Query("SELECT COUNT(*) FROM learning_episode_lessons WHERE state = 'VALID'")
    suspend fun countValidLessons(): Long

    @Query(
        "SELECT COUNT(*) FROM learning_reward_windows " +
            "WHERE authority_outcome IN ('SUCCESS', 'FAILURE')",
    )
    suspend fun countKnownAuthorityRewardWindows(): Long

    @Query("SELECT MAX(replay_generation) FROM learning_episodes")
    suspend fun maxEpisodeReplayGeneration(): Long?

    @Query("SELECT MAX(replay_generation) FROM learning_source_validity")
    suspend fun maxSourceValidityReplayGeneration(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodeIgnore(entity: LearningEpisodeEntity): Long

    @Query("SELECT * FROM learning_episodes WHERE id = :episodeId LIMIT 1")
    suspend fun findEpisode(episodeId: String): LearningEpisodeEntity?

    @Query(
        "DELETE FROM learning_episodes WHERE id = :id AND stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND lineage_id = :lineageId " +
            "AND branch_anchor_message_id = :branchAnchorMessageId AND status = 'OPEN' " +
            "AND revision = :expectedRevision " +
            "AND NOT EXISTS (SELECT 1 FROM learning_trace_features t WHERE t.episode_id = :id) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_episode_lessons l WHERE l.episode_id = :id) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_reward_windows r WHERE r.episode_id = :id) " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence p WHERE p.episode_id = :id) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_policy_exposures x WHERE x.episode_id = :id)",
    )
    suspend fun deleteExactUnusedOpenEpisode(
        id: String,
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        lineageId: String,
        branchAnchorMessageId: String,
        expectedRevision: Long,
    ): Int

    @Query(
        "SELECT * FROM learning_episodes WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND lineage_id = :lineageId " +
            "AND branch_anchor_message_id = :branchAnchorMessageId LIMIT 1",
    )
    suspend fun findEpisodeByBoundary(
        streamId: String,
        replayGeneration: Long,
        lineageId: String,
        branchAnchorMessageId: String,
    ): LearningEpisodeEntity?

    @Query(
        "SELECT e.* FROM learning_episodes e WHERE e.stream_id = :streamId " +
            "AND e.replay_generation = :replayGeneration " +
            "AND e.scope_kind = :scopeKind AND e.scope_id = :scopeId " +
            "AND e.conversation_id = :conversationId " +
            "AND (e.root_command_id = :commandId OR e.final_command_id = :commandId) " +
            "ORDER BY e.updated_at_ms DESC, e.id ASC LIMIT 2",
    )
    suspend fun findEpisodesByCommandAuthority(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        commandId: String,
    ): List<LearningEpisodeEntity>

    @Query(
        "SELECT * FROM learning_episodes WHERE status != 'OPEN' AND " +
            "(updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND id > :afterEpisodeId)) " +
            "ORDER BY updated_at_ms ASC, id ASC LIMIT :limit",
    )
    suspend fun listTerminalEpisodePage(
        afterUpdatedAtMs: Long,
        afterEpisodeId: String,
        limit: Int,
    ): List<LearningEpisodeEntity>

    @Query(
        "SELECT DISTINCT e.* FROM learning_episodes e " +
            "JOIN learning_episode_lessons l ON l.episode_id = e.id AND l.state = 'VALID' " +
            "JOIN learning_reward_windows r ON r.episode_id = e.id AND r.state = 'CLOSED' " +
            "WHERE e.scope_kind = :scopeKind AND e.scope_id = :scopeId " +
            "AND e.finalized_at_ms IS NOT NULL AND e.finalized_at_ms <= :frozenAtMs " +
            "AND l.updated_at_ms <= :frozenAtMs AND r.updated_at_ms <= :frozenAtMs " +
            "AND r.authority_outcome IN ('SUCCESS', 'FAILURE') " +
            "AND e.task_signature = :taskSignature " +
            "AND EXISTS (SELECT 1 FROM learning_trace_features t WHERE t.episode_id = e.id " +
            "AND t.source_type = 'CONVERSATION_MESSAGE') " +
            "AND NOT EXISTS (SELECT 1 FROM learning_trace_features t " +
            "LEFT JOIN learning_source_validity s ON s.stream_id = e.stream_id " +
            "AND s.replay_generation = e.replay_generation " +
            "AND s.scope_kind = e.scope_kind AND s.scope_id = e.scope_id " +
            "AND s.source_type = t.source_type AND s.source_id = t.source_id " +
            "AND s.source_revision = t.source_revision WHERE t.episode_id = e.id " +
            "AND t.source_type = 'CONVERSATION_MESSAGE' " +
            "AND (t.source_revision IS NULL OR s.source_id IS NULL OR s.state != 'VALID' " +
            "OR s.integrity_sha256 IS NULL)) " +
            "ORDER BY e.finalized_at_ms DESC, e.id DESC LIMIT :limit",
    )
    suspend fun listDistillationEvidenceEpisodes(
        scopeKind: String,
        scopeId: String,
        taskSignature: String,
        frozenAtMs: Long,
        limit: Int,
    ): List<LearningEpisodeEntity>

    @Query(
        "UPDATE learning_episodes SET conversation_revision = :conversationRevision, " +
            "final_command_id = :finalCommandId, final_command_revision = :finalCommandRevision, " +
            "result_assistant_message_id = :resultAssistantMessageId, " +
            "result_assistant_message_revision = :resultAssistantMessageRevision, " +
            "generation_run_id = :generationRunId, execution_id = :executionId, " +
            "task_signature = :taskSignature, " +
            "status = :newStatus, boundary_reason = :boundaryReason, " +
            "revision = revision + 1, finalized_at_ms = :finalizedAtMs, " +
            "updated_at_ms = :updatedAtMs WHERE id = :episodeId " +
            "AND revision = :expectedRevision AND status = :expectedStatus " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updateBoundaryIfCurrent(
        episodeId: String,
        expectedRevision: Long,
        expectedStatus: String,
        conversationRevision: Long?,
        finalCommandId: String?,
        finalCommandRevision: Long?,
        resultAssistantMessageId: String?,
        resultAssistantMessageRevision: Long?,
        generationRunId: String?,
        executionId: String?,
        taskSignature: String,
        newStatus: String,
        boundaryReason: String,
        finalizedAtMs: Long?,
        updatedAtMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTraceIgnore(entity: LearningTraceFeatureEntity): Long

    @Query(
        "SELECT * FROM learning_trace_features WHERE episode_id = :episodeId " +
        "AND sequence = :sequence AND source_ordinal = :sourceOrdinal LIMIT 1",
    )
    suspend fun findTrace(
        episodeId: String,
        sequence: Long,
        sourceOrdinal: Int,
    ): LearningTraceFeatureEntity?

    @Query(
        "SELECT * FROM learning_trace_features WHERE episode_id = :episodeId " +
            "ORDER BY sequence ASC LIMIT :limit",
    )
    suspend fun listTrace(episodeId: String, limit: Int): List<LearningTraceFeatureEntity>

    @Query(
        "SELECT CASE WHEN COUNT(*) > 0 AND COUNT(*) = SUM(CASE WHEN " +
            "t.source_revision IS NOT NULL AND s.source_id IS NOT NULL " +
            "AND s.state = 'VALID' AND s.integrity_sha256 IS NOT NULL THEN 1 ELSE 0 END) " +
            "THEN COUNT(*) ELSE 0 END FROM learning_trace_features t JOIN learning_episodes e " +
            "ON e.id = t.episode_id LEFT JOIN learning_source_validity s ON " +
            "s.stream_id = e.stream_id AND s.scope_kind = e.scope_kind " +
            "AND s.scope_id = e.scope_id AND s.source_type = t.source_type " +
            "AND s.source_id = t.source_id AND s.source_revision = t.source_revision " +
            "AND s.replay_generation = e.replay_generation WHERE t.episode_id = :episodeId " +
            "AND t.source_type = 'CONVERSATION_MESSAGE'",
    )
    suspend fun countValidStableTraceSources(episodeId: String): Long

    @Query(
        "SELECT COUNT(DISTINCT source_id) " +
            "FROM learning_trace_features WHERE episode_id = :episodeId " +
            "AND source_type = 'EXECUTION_EVENT'",
    )
    suspend fun countExecutionTraceSources(episodeId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLessonIgnore(entity: LearningEpisodeLessonEntity): Long

    @Query(
        "SELECT * FROM learning_episode_lessons WHERE episode_id = :episodeId " +
            "AND lesson_version = :lessonVersion LIMIT 1",
    )
    suspend fun findLesson(
        episodeId: String,
        lessonVersion: Int,
    ): LearningEpisodeLessonEntity?

    @Query(
        "UPDATE learning_episode_lessons SET state = 'STALE_SOURCE', " +
            "trigger_summary = '[SOURCE_ERASED]', " +
            "observation_summary = '[SOURCE_ERASED]', " +
            "lesson_summary = '[SOURCE_ERASED]', " +
            "boundary_summary = '[SOURCE_ERASED]', " +
            "artifact_sha256 = '4337a7cc59142919cbbb5af77323269e33cdc79e68e85aa289571c1af2136143', " +
            "updated_at_ms = MAX(updated_at_ms, :updatedAtMs) " +
            "WHERE rowid IN (SELECT l.rowid FROM learning_episode_lessons l " +
            "WHERE l.episode_id IN (SELECT DISTINCT t.episode_id FROM learning_trace_features t " +
            "JOIN learning_episodes e ON e.id = t.episode_id " +
            "WHERE e.stream_id = :streamId AND e.replay_generation = :replayGeneration " +
            "AND e.scope_kind = :scopeKind AND e.scope_id = :scopeId " +
            "AND t.source_type = :sourceType AND t.source_id = :sourceId " +
            "AND t.source_revision = :sourceRevision) AND l.state = 'VALID' " +
            "ORDER BY l.episode_id ASC, l.lesson_version ASC LIMIT :limit) AND state = 'VALID'",
    )
    suspend fun markLessonsStaleForSource(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        sourceType: String,
        sourceId: String,
        sourceRevision: Long,
        updatedAtMs: Long,
        limit: Int,
    ): Int

    @Query(
        "UPDATE learning_trace_features SET state_summary = NULL, observation_summary = NULL " +
            "WHERE rowid IN (SELECT t.rowid FROM learning_trace_features t " +
            "WHERE t.episode_id IN (SELECT id FROM learning_episodes " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId) " +
            "AND t.source_type = :sourceType AND t.source_id = :sourceId " +
            "AND t.source_revision = :sourceRevision " +
            "AND (t.state_summary IS NOT NULL OR t.observation_summary IS NOT NULL) " +
            "ORDER BY t.episode_id ASC, t.sequence ASC, t.source_ordinal ASC LIMIT :limit)",
    )
    suspend fun clearTraceSummariesForSource(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        sourceType: String,
        sourceId: String,
        sourceRevision: Long,
        limit: Int,
    ): Int

    @Query(
        "UPDATE learning_trace_features SET state_summary = NULL, observation_summary = NULL " +
            "WHERE rowid IN (SELECT t.rowid FROM learning_trace_features t " +
            "JOIN learning_episodes e ON e.id = t.episode_id " +
            "LEFT JOIN learning_source_validity s ON s.stream_id = e.stream_id " +
            "AND s.replay_generation = e.replay_generation " +
            "AND s.scope_kind = e.scope_kind AND s.scope_id = e.scope_id " +
            "AND s.source_type = t.source_type AND s.source_id = t.source_id " +
            "AND s.source_revision = t.source_revision " +
            "WHERE (t.state_summary IS NOT NULL OR t.observation_summary IS NOT NULL) " +
            "AND (t.source_revision IS NULL OR s.source_id IS NULL OR s.state != 'VALID' " +
            "OR s.integrity_sha256 IS NULL) ORDER BY e.id ASC, t.sequence ASC, " +
            "t.source_ordinal ASC LIMIT :limit)",
    )
    suspend fun clearTraceSummariesWithInvalidSource(limit: Int): Int

    @Query(
        "UPDATE learning_episode_lessons SET state = 'STALE_SOURCE', " +
            "trigger_summary = '[SOURCE_ERASED]', " +
            "observation_summary = '[SOURCE_ERASED]', " +
            "lesson_summary = '[SOURCE_ERASED]', " +
            "boundary_summary = '[SOURCE_ERASED]', " +
            "artifact_sha256 = '4337a7cc59142919cbbb5af77323269e33cdc79e68e85aa289571c1af2136143', " +
            "updated_at_ms = MAX(updated_at_ms, :updatedAtMs) " +
            "WHERE rowid IN (SELECT l.rowid FROM learning_episode_lessons l " +
            "JOIN learning_episodes e ON e.id = l.episode_id " +
            "WHERE l.state = 'VALID' AND EXISTS (SELECT 1 FROM learning_trace_features t " +
            "LEFT JOIN learning_source_validity s ON s.stream_id = e.stream_id " +
            "AND s.replay_generation = e.replay_generation " +
            "AND s.scope_kind = e.scope_kind AND s.scope_id = e.scope_id " +
            "AND s.source_type = t.source_type AND s.source_id = t.source_id " +
            "AND s.source_revision = t.source_revision WHERE t.episode_id = e.id " +
            "AND t.source_type = 'CONVERSATION_MESSAGE' " +
            "AND (t.source_revision IS NULL OR s.source_id IS NULL OR s.state != 'VALID' " +
            "OR s.integrity_sha256 IS NULL)) ORDER BY e.id ASC LIMIT :limit)",
    )
    suspend fun markLessonsStaleWithInvalidSource(updatedAtMs: Long, limit: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRewardWindowIgnore(entity: LearningRewardWindowEntity): Long

    @Query("SELECT * FROM learning_reward_windows WHERE episode_id = :episodeId LIMIT 1")
    suspend fun findRewardWindowByEpisode(episodeId: String): LearningRewardWindowEntity?

    @Query("SELECT * FROM learning_reward_windows WHERE id = :id LIMIT 1")
    suspend fun findRewardWindow(id: String): LearningRewardWindowEntity?

    @Query(
        "UPDATE learning_reward_windows SET state = :newState, goal_knowledge = :goalKnowledge, " +
            "goal_value = :goalValue, goal_unknown_reason = :goalUnknownReason, " +
            "goal_evidence_sha256 = :goalEvidenceSha256, process_knowledge = :processKnowledge, " +
            "process_value = :processValue, process_unknown_reason = :processUnknownReason, " +
            "process_evidence_sha256 = :processEvidenceSha256, user_knowledge = :userKnowledge, " +
            "user_value = :userValue, user_unknown_reason = :userUnknownReason, " +
            "user_evidence_sha256 = :userEvidenceSha256, weak_label = :weakLabel, " +
            "closed_at_ms = :closedAtMs, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND state = 'OPEN' AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun closeRewardWindowIfOpen(
        id: String,
        newState: String,
        goalKnowledge: String,
        goalValue: Double?,
        goalUnknownReason: String?,
        goalEvidenceSha256: String?,
        processKnowledge: String,
        processValue: Double?,
        processUnknownReason: String?,
        processEvidenceSha256: String?,
        userKnowledge: String,
        userValue: Double?,
        userUnknownReason: String?,
        userEvidenceSha256: String?,
        weakLabel: Double?,
        closedAtMs: Long,
        updatedAtMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceValidityIgnore(entity: LearningSourceValidityEntity): Long

    @Query(
        "SELECT * FROM learning_source_validity WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId AND source_type = :sourceType " +
            "AND source_id = :sourceId AND source_revision = :sourceRevision LIMIT 1",
    )
    suspend fun findSourceValidity(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        sourceType: String,
        sourceId: String,
        sourceRevision: Long,
    ): LearningSourceValidityEntity?

    @Query(
        "UPDATE learning_source_validity SET previous_source_revision = :previousSourceRevision, " +
            "state = :newState, integrity_sha256 = :integritySha256, " +
            "invalidation_reason = :invalidationReason, authority_event_id = :authorityEventId, " +
            "occurred_at_ms = :occurredAtMs, updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND source_type = :sourceType AND source_id = :sourceId " +
            "AND source_revision = :sourceRevision AND state = :expectedState " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updateSourceValidityIfCurrent(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        sourceType: String,
        sourceId: String,
        sourceRevision: Long,
        previousSourceRevision: Long?,
        expectedState: String,
        newState: String,
        integritySha256: String?,
        invalidationReason: String?,
        authorityEventId: String,
        occurredAtMs: Long,
        updatedAtMs: Long,
    ): Int

    /**
     * A current authority head proves that every lower revision is stale, including revisions
     * skipped while handoff was disabled. This bulk fence makes re-enable reconciliation safe
     * without inventing a historical authority journal.
     */
    @Query(
        "UPDATE learning_source_validity SET state = :newState, " +
            "invalidation_reason = :invalidationReason, authority_event_id = :authorityEventId, " +
            "occurred_at_ms = :occurredAtMs, updated_at_ms = :updatedAtMs " +
            "WHERE stream_id = :streamId AND replay_generation = :replayGeneration " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND source_type = :sourceType AND source_id = :sourceId " +
            "AND source_revision < :currentRevision AND state IN ('VALID', 'UNKNOWN') " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun invalidateAllEarlierSourceRevisions(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        sourceType: String,
        sourceId: String,
        currentRevision: Long,
        newState: String,
        invalidationReason: String,
        authorityEventId: String,
        occurredAtMs: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_episodes SET status = 'CENSORED', " +
            "boundary_reason = 'RETENTION_EXPIRED', revision = revision + 1, " +
            "finalized_at_ms = :nowMs, updated_at_ms = :nowMs WHERE id IN " +
            "(SELECT e.id FROM learning_episodes e WHERE e.status = 'OPEN' " +
            "AND e.started_at_ms < :cutoffMs AND NOT EXISTS (SELECT 1 FROM learning_jobs j " +
            "JOIN learning_inbox_events i ON i.stream_id = j.stream_id " +
            "AND i.event_id = j.source_event_id WHERE j.state IN ('PENDING', 'RETRY', 'RUNNING') " +
            "AND j.stream_id = e.stream_id AND j.replay_generation = e.replay_generation " +
            "AND j.scope_kind = e.scope_kind AND j.scope_id = e.scope_id " +
            "AND i.lineage_id = e.lineage_id " +
            "AND i.branch_anchor_message_id = e.branch_anchor_message_id) " +
            "ORDER BY e.started_at_ms ASC, e.id ASC LIMIT :limit)",
    )
    suspend fun censorExpiredOpenEpisodes(cutoffMs: Long, nowMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_trace_features WHERE rowid IN " +
            "(SELECT t.rowid FROM learning_trace_features t WHERE t.created_at_ms < :cutoffMs " +
            "AND " + NO_ACTIVE_DISTILL_JOB_PREDICATE + " " +
            "AND NOT EXISTS (SELECT 1 FROM learning_episode_lessons l " +
            "WHERE l.episode_id = t.episode_id AND l.state = 'VALID') " +
            "AND NOT EXISTS (SELECT 1 FROM learning_episodes ep " +
            "JOIN learning_jobs j ON j.stream_id = ep.stream_id " +
            "AND j.replay_generation = ep.replay_generation " +
            "AND j.scope_kind = ep.scope_kind AND j.scope_id = ep.scope_id " +
            "JOIN learning_inbox_events i ON i.stream_id = j.stream_id " +
            "AND i.event_id = j.source_event_id WHERE ep.id = t.episode_id " +
            "AND j.state IN ('PENDING', 'RETRY', 'RUNNING') AND i.lineage_id = ep.lineage_id " +
            "AND i.branch_anchor_message_id = ep.branch_anchor_message_id) " +
            "ORDER BY t.created_at_ms ASC, t.episode_id ASC, t.sequence ASC, " +
            "t.source_ordinal ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredUnpinnedTrace(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_reward_windows WHERE rowid IN " +
            "(SELECT r.rowid FROM learning_reward_windows r " +
            "JOIN learning_episodes e ON e.id = r.episode_id WHERE r.state != 'OPEN' " +
            "AND " + NO_ACTIVE_DISTILL_JOB_PREDICATE + " " +
            "AND r.closed_at_ms < :cutoffMs AND NOT EXISTS (SELECT 1 FROM learning_jobs j " +
            "JOIN learning_inbox_events i ON i.stream_id = j.stream_id " +
            "AND i.event_id = j.source_event_id WHERE j.state IN ('PENDING', 'RETRY', 'RUNNING') " +
            "AND j.stream_id = e.stream_id AND j.replay_generation = e.replay_generation " +
            "AND j.scope_kind = e.scope_kind AND j.scope_id = e.scope_id " +
            "AND i.lineage_id = e.lineage_id " +
            "AND i.branch_anchor_message_id = e.branch_anchor_message_id) " +
            "ORDER BY r.closed_at_ms ASC, r.id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredRewardWindows(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_episode_lessons WHERE rowid IN " +
            "(SELECT l.rowid FROM learning_episode_lessons l " +
            "JOIN learning_episodes ep ON ep.id = l.episode_id " +
            "WHERE l.updated_at_ms < :cutoffMs " +
            "AND " + NO_ACTIVE_DISTILL_JOB_PREDICATE + " " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence e WHERE e.episode_id = l.episode_id " +
            "AND e.lesson_version = l.lesson_version) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_jobs j " +
            "JOIN learning_inbox_events i ON i.stream_id = j.stream_id " +
            "AND i.event_id = j.source_event_id WHERE j.state IN ('PENDING', 'RETRY', 'RUNNING') " +
            "AND j.stream_id = ep.stream_id AND j.replay_generation = ep.replay_generation " +
            "AND j.scope_kind = ep.scope_kind AND j.scope_id = ep.scope_id " +
            "AND i.lineage_id = ep.lineage_id " +
            "AND i.branch_anchor_message_id = ep.branch_anchor_message_id) " +
            "ORDER BY l.updated_at_ms ASC, l.episode_id ASC, l.lesson_version ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredUnreferencedLessons(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_episodes WHERE id IN " +
            "(SELECT e.id FROM learning_episodes e WHERE e.status != 'OPEN' " +
            "AND e.finalized_at_ms < :cutoffMs " +
            "AND " + NO_ACTIVE_DISTILL_JOB_PREDICATE + " " +
            "AND NOT EXISTS (SELECT 1 FROM learning_episode_lessons l WHERE l.episode_id = e.id) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_reward_windows r WHERE r.episode_id = e.id) " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence p WHERE p.episode_id = e.id) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_policy_exposures x " +
            "WHERE x.episode_id = e.id) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_jobs j " +
            "JOIN learning_inbox_events i ON i.stream_id = j.stream_id " +
            "AND i.event_id = j.source_event_id WHERE j.state IN ('PENDING', 'RETRY', 'RUNNING') " +
            "AND j.stream_id = e.stream_id AND j.replay_generation = e.replay_generation " +
            "AND j.scope_kind = e.scope_kind AND j.scope_id = e.scope_id " +
            "AND i.lineage_id = e.lineage_id " +
            "AND i.branch_anchor_message_id = e.branch_anchor_message_id) " +
            "ORDER BY e.finalized_at_ms ASC, e.id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredUnreferencedEpisodes(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_source_validity WHERE rowid IN " +
            "(SELECT s.rowid FROM learning_source_validity s WHERE s.state != 'VALID' " +
            "AND s.updated_at_ms < :cutoffMs AND NOT EXISTS " +
            "(SELECT 1 FROM learning_trace_features t JOIN learning_episodes ep " +
            "ON ep.id = t.episode_id WHERE ep.stream_id = s.stream_id " +
            "AND ep.replay_generation = s.replay_generation AND ep.scope_kind = s.scope_kind " +
            "AND ep.scope_id = s.scope_id AND t.source_type = s.source_type " +
            "AND t.source_id = s.source_id AND t.source_revision = s.source_revision) " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence pe JOIN learning_episodes ep " +
            "ON ep.id = pe.episode_id WHERE ep.stream_id = s.stream_id " +
            "AND ep.replay_generation = s.replay_generation AND ep.scope_kind = s.scope_kind " +
            "AND ep.scope_id = s.scope_id AND pe.source_type = s.source_type " +
            "AND pe.source_id = s.source_id AND pe.source_revision = s.source_revision) " +
            "ORDER BY s.updated_at_ms ASC, s.source_id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredUnreferencedSourceValidity(cutoffMs: Long, limit: Int): Int

    @Query("DELETE FROM learning_reward_windows WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun deleteRewardWindowsByScope(scopeKind: String, scopeId: String): Int

    @Query("DELETE FROM learning_episode_lessons WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun deleteLessonsByScope(scopeKind: String, scopeId: String): Int

    @Query(
        "DELETE FROM learning_trace_features WHERE episode_id IN " +
            "(SELECT id FROM learning_episodes WHERE scope_kind = :scopeKind AND scope_id = :scopeId)",
    )
    suspend fun deleteTraceByScope(scopeKind: String, scopeId: String): Int

    @Query("DELETE FROM learning_episodes WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun deleteEpisodesByScope(scopeKind: String, scopeId: String): Int

    @Query("DELETE FROM learning_source_validity WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun deleteSourceValidityByScope(scopeKind: String, scopeId: String): Int

    @Query(
        "SELECT COUNT(*) FROM learning_source_validity WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND state != 'VALID' AND updated_at_ms >= :cutoffMs",
    )
    suspend fun countRetainedSourceAuditTombstonesByScope(
        scopeKind: String,
        scopeId: String,
        cutoffMs: Long,
    ): Int

    @Query(
        "DELETE FROM learning_source_validity WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND (state = 'VALID' OR updated_at_ms < :cutoffMs)",
    )
    suspend fun deleteErasableSourceValidityByScope(
        scopeKind: String,
        scopeId: String,
        cutoffMs: Long,
    ): Int

    @Query("DELETE FROM learning_reward_windows")
    suspend fun deleteAllRewardWindows(): Int

    @Query("DELETE FROM learning_episode_lessons")
    suspend fun deleteAllLessons(): Int

    @Query("DELETE FROM learning_trace_features")
    suspend fun deleteAllTrace(): Int

    @Query("DELETE FROM learning_episodes")
    suspend fun deleteAllEpisodes(): Int

    @Query("DELETE FROM learning_source_validity")
    suspend fun deleteAllSourceValidity(): Int
}

/** Conservative P1 pin: without durable job-evidence refs, active distillation pins all inputs. */
internal const val NO_ACTIVE_DISTILL_JOB_PREDICATE =
    "NOT EXISTS (SELECT 1 FROM learning_jobs distill_job " +
        "WHERE distill_job.job_type = 'DISTILL_POLICY_V1' " +
        "AND distill_job.state IN ('PENDING', 'RETRY', 'RUNNING'))"
