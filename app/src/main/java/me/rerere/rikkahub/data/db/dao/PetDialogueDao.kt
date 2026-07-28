package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PetDialogueRevisionEntity
import me.rerere.rikkahub.data.db.entity.PetDialogueSessionEntity
import me.rerere.rikkahub.data.db.entity.PetDialogueTurnEntity
import me.rerere.rikkahub.data.db.entity.PetHandoffRequestEntity

@Dao
interface PetDialogueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: PetDialogueSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTurn(turn: PetDialogueTurnEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHandoff(request: PetHandoffRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: PetDialogueRevisionEntity)

    @Query("SELECT * FROM pet_dialogue_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): PetDialogueSessionEntity?

    @Query(
        "SELECT * FROM pet_dialogue_sessions WHERE assistantId = :assistantId " +
            "AND privilegedConversationId = :conversationId AND status = 'ACTIVE' " +
            "ORDER BY createdAtMs DESC LIMIT 1",
    )
    suspend fun getActiveSession(assistantId: String, conversationId: String): PetDialogueSessionEntity?

    @Query(
        "SELECT * FROM pet_dialogue_sessions WHERE assistantId = :assistantId " +
            "AND privilegedConversationId = :conversationId AND status = 'ACTIVE' " +
            "ORDER BY createdAtMs DESC LIMIT 1",
    )
    fun observeActiveSession(assistantId: String, conversationId: String): Flow<PetDialogueSessionEntity?>

    @Query("SELECT * FROM pet_dialogue_turns WHERE sessionId = :sessionId ORDER BY sequence ASC")
    fun observeTurns(sessionId: String): Flow<List<PetDialogueTurnEntity>>

    @Query("SELECT * FROM pet_dialogue_turns WHERE sessionId = :sessionId ORDER BY sequence ASC")
    suspend fun getTurns(sessionId: String): List<PetDialogueTurnEntity>

    @Query("SELECT COUNT(*) FROM pet_dialogue_turns WHERE sessionId = :sessionId")
    suspend fun countTurns(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM pet_dialogue_sessions WHERE assistantId = :assistantId AND status = 'ACTIVE'")
    suspend fun countActiveSessions(assistantId: String): Int

    @Query(
        "SELECT * FROM pet_dialogue_sessions WHERE assistantId = :assistantId " +
            "AND status = 'ARCHIVED' ORDER BY archivedAtMs DESC LIMIT :limit",
    )
    fun observeArchives(assistantId: String, limit: Int): Flow<List<PetDialogueSessionEntity>>

    @Query(
        "SELECT * FROM pet_dialogue_sessions WHERE assistantId = :assistantId " +
            "AND status = 'ARCHIVED' ORDER BY archivedAtMs DESC LIMIT :limit",
    )
    suspend fun getArchives(assistantId: String, limit: Int): List<PetDialogueSessionEntity>

    @Query(
        "SELECT * FROM pet_dialogue_sessions WHERE status = 'ARCHIVED' " +
            "AND summaryState IN ('PENDING', 'FAILED') ORDER BY archivedAtMs ASC LIMIT :limit",
    )
    suspend fun getPendingSummaries(limit: Int): List<PetDialogueSessionEntity>

    @Query(
        "UPDATE pet_dialogue_sessions SET status = :nextStatus, activeOwnerKey = NULL, archiveReason = :archiveReason, " +
            "summaryState = :summaryState, archivedAtMs = :nowMs, updatedAtMs = :nowMs, " +
            "stateVersion = stateVersion + 1 WHERE sessionId = :sessionId " +
            "AND status = 'ACTIVE' AND stateVersion = :expectedVersion",
    )
    suspend fun archiveSession(
        sessionId: String,
        expectedVersion: Long,
        nextStatus: String,
        archiveReason: String,
        summaryState: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE pet_dialogue_sessions SET localDate = :localDate, zoneId = :zoneId, " +
            "updatedAtMs = :nowMs, stateVersion = stateVersion + 1 " +
            "WHERE sessionId = :sessionId AND status = 'ACTIVE' " +
            "AND stateVersion = :expectedVersion AND NOT EXISTS " +
            "(SELECT 1 FROM pet_dialogue_turns WHERE sessionId = :sessionId)",
    )
    suspend fun rollEmptySessionDate(
        sessionId: String,
        expectedVersion: Long,
        localDate: String,
        zoneId: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE pet_dialogue_sessions SET title = :title, summary = :summary, notes = :notes, " +
            "tagsJson = :tagsJson, summaryState = :summaryState, updatedAtMs = :nowMs, " +
            "stateVersion = stateVersion + 1 WHERE sessionId = :sessionId " +
            "AND stateVersion = :expectedVersion",
    )
    suspend fun updateMetadata(
        sessionId: String,
        expectedVersion: Long,
        title: String,
        summary: String,
        notes: String,
        tagsJson: String,
        summaryState: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE pet_dialogue_sessions SET status = :nextStatus, deletedAtMs = :deletedAtMs, " +
            "updatedAtMs = :nowMs, stateVersion = stateVersion + 1 " +
            "WHERE sessionId = :sessionId AND stateVersion = :expectedVersion",
    )
    suspend fun setStatus(
        sessionId: String,
        expectedVersion: Long,
        nextStatus: String,
        deletedAtMs: Long?,
        nowMs: Long,
    ): Int

    @Query("SELECT * FROM pet_dialogue_revisions WHERE sessionId = :sessionId ORDER BY revision DESC")
    fun observeRevisions(sessionId: String): Flow<List<PetDialogueRevisionEntity>>

    @Query("SELECT * FROM pet_dialogue_revisions WHERE sessionId = :sessionId ORDER BY revision DESC")
    suspend fun getRevisions(sessionId: String): List<PetDialogueRevisionEntity>

    @Query("SELECT * FROM pet_handoff_requests WHERE requestId = :requestId LIMIT 1")
    suspend fun getHandoff(requestId: String): PetHandoffRequestEntity?

    @Query(
        "SELECT * FROM pet_handoff_requests WHERE assistantId = :assistantId " +
            "AND status IN ('DRAFT', 'CONFIRMED', 'AUTO_SUBMITTED') ORDER BY createdAtMs DESC",
    )
    fun observePendingHandoffs(assistantId: String): Flow<List<PetHandoffRequestEntity>>

    @Query(
        "UPDATE pet_handoff_requests SET title = :title, request = :request, " +
            "stateVersion = stateVersion + 1 WHERE requestId = :requestId " +
            "AND status = 'DRAFT' AND stateVersion = :expectedVersion",
    )
    suspend fun updateHandoffDraft(
        requestId: String,
        expectedVersion: Long,
        title: String,
        request: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM pet_handoff_requests WHERE assistantId = :assistantId " +
            "AND mode = 'AUTO' AND submittedAtMs >= :sinceMs",
    )
    suspend fun countRecentAutoHandoffs(assistantId: String, sinceMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM pet_handoff_requests WHERE assistantId = :assistantId " +
        "AND mode = 'AUTO' AND status IN ('CONFIRMED', 'AUTO_SUBMITTED') " +
            "AND requestId != :excludingRequestId",
    )
    suspend fun countPendingAutoHandoffs(assistantId: String, excludingRequestId: String): Int

    @Query(
        "SELECT * FROM pet_handoff_requests WHERE status IN ('CONFIRMED', 'AUTO_SUBMITTED') " +
            "ORDER BY createdAtMs ASC",
    )
    suspend fun getRecoverableHandoffs(): List<PetHandoffRequestEntity>

    @Query(
        "UPDATE pet_handoff_requests SET status = :nextStatus, targetCommandId = :targetCommandId, " +
            "submittedAtMs = :submittedAtMs, resolvedAtMs = :resolvedAtMs, " +
            "stateVersion = stateVersion + 1 WHERE requestId = :requestId " +
            "AND stateVersion = :expectedVersion",
    )
    suspend fun updateHandoffStatus(
        requestId: String,
        expectedVersion: Long,
        nextStatus: String,
        targetCommandId: String?,
        submittedAtMs: Long?,
        resolvedAtMs: Long?,
    ): Int

    @Query(
        "UPDATE pet_handoff_requests SET status = 'EXPIRED', resolvedAtMs = :nowMs, " +
            "stateVersion = stateVersion + 1 WHERE status IN ('DRAFT', 'CONFIRMED') " +
            "AND expiresAtMs IS NOT NULL AND expiresAtMs <= :nowMs",
    )
    suspend fun expireHandoffs(nowMs: Long): Int

    @Query("DELETE FROM pet_dialogue_sessions WHERE status = 'SOFT_DELETED' AND deletedAtMs <= :cutoffMs")
    suspend fun purgeDeleted(cutoffMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM pet_dialogue_sessions s WHERE " +
            "(SELECT COUNT(*) FROM pet_dialogue_turns t WHERE t.sessionId = s.sessionId) > 20",
    )
    suspend fun countOverCapacitySessions(): Int

    @Query(
        "SELECT COUNT(*) FROM pet_handoff_requests WHERE status IN ('DRAFT', 'CONFIRMED') " +
            "AND expiresAtMs IS NOT NULL AND expiresAtMs <= :nowMs",
    )
    suspend fun countExpiredPendingHandoffs(nowMs: Long): Int
}
