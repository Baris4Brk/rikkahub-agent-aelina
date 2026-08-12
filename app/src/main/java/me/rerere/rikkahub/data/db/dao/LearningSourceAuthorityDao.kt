package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity

/**
 * Narrow main-database source authority surface.
 *
 * Writers call these methods only inside the Conversation/Command owning Room transaction. No
 * method uses REPLACE: every head change is a revision-fenced CAS and tombstones cannot revive.
 */
@Dao
interface LearningSourceAuthorityDao {
    @Query(
        "SELECT * FROM learning_conversation_source_authority " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId LIMIT 1",
    )
    suspend fun findConversation(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
    ): LearningConversationSourceAuthorityEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversationInitialIgnore(
        source: LearningConversationSourceAuthorityEntity,
    ): Long

    @Query(
        "UPDATE learning_conversation_source_authority SET " +
            "assistant_id_snapshot = :assistantIdSnapshot, " +
            "previous_source_revision = source_revision, source_revision = :nextRevision, " +
            "source_state = :sourceState, change_kind = :changeKind, " +
            "branch_head_message_id = :branchHeadMessageId, " +
            "branch_head_message_revision = :branchHeadMessageRevision, " +
            "occurred_at_ms = :occurredAtMs, updated_at_ms = :updatedAtMs " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId AND source_revision = :expectedRevision " +
            "AND :nextRevision = :expectedRevision + 1 AND source_state != 'TOMBSTONED' " +
            "AND :sourceState IN ('ACTIVE', 'SUPERSEDED', 'TOMBSTONED') " +
            "AND :changeKind IN ('CREATED', 'UPDATED', 'BRANCH_SELECTED', " +
            "'BRANCH_SUPERSEDED', 'DELETED', 'CONVERSATION_DELETED') " +
            "AND ((:branchHeadMessageId IS NULL AND :branchHeadMessageRevision IS NULL) " +
            "OR (:branchHeadMessageId IS NOT NULL AND :branchHeadMessageRevision > 0)) " +
            "AND (:sourceState != 'TOMBSTONED' OR :branchHeadMessageId IS NULL) " +
            "AND :occurredAtMs >= 0 AND :updatedAtMs >= :occurredAtMs",
    )
    suspend fun updateConversationFenced(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        expectedRevision: Long,
        nextRevision: Long,
        assistantIdSnapshot: String,
        sourceState: String,
        changeKind: String,
        branchHeadMessageId: String?,
        branchHeadMessageRevision: Long?,
        occurredAtMs: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM learning_conversation_source_authority " +
            "WHERE conversation_id = :conversationId AND source_state != 'TOMBSTONED'",
    )
    /** Counts every scope which still needs a deletion tombstone; already tombstoned heads are safe. */
    suspend fun countConversationScopes(conversationId: String): Int

    @Query(
        "SELECT * FROM learning_conversation_source_authority " +
            "WHERE conversation_id = :conversationId AND source_state != 'TOMBSTONED' AND " +
            "(scope_kind > :afterScopeKind OR " +
            "(scope_kind = :afterScopeKind AND scope_id > :afterScopeId)) " +
            "ORDER BY scope_kind ASC, scope_id ASC LIMIT :limit",
    )
    suspend fun listConversationScopesAfter(
        conversationId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<LearningConversationSourceAuthorityEntity>

    @Query(
        "SELECT * FROM learning_message_source_authority " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND message_id = :messageId LIMIT 1",
    )
    suspend fun findMessage(
        scopeKind: String,
        scopeId: String,
        messageId: String,
    ): LearningMessageSourceAuthorityEntity?

    /** Read-only provenance lookup. Historical revisions are intentionally not reconstructed. */
    @Query(
        "SELECT * FROM learning_message_source_authority " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND message_id = :messageId AND source_revision = :sourceRevision LIMIT 1",
    )
    suspend fun findMessageAtRevision(
        scopeKind: String,
        scopeId: String,
        messageId: String,
        sourceRevision: Long,
    ): LearningMessageSourceAuthorityEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageInitialIgnore(source: LearningMessageSourceAuthorityEntity): Long

    @Query(
        "UPDATE learning_message_source_authority SET " +
            "message_role = :messageRole, " +
            "previous_source_revision = source_revision, source_revision = :nextRevision, " +
            "source_state = :sourceState, change_kind = :changeKind, " +
            "payload_integrity_sha256 = :payloadIntegritySha256, " +
            "occurred_at_ms = :occurredAtMs, updated_at_ms = :updatedAtMs " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId AND message_id = :messageId " +
            "AND source_revision = :expectedRevision " +
            "AND :nextRevision = :expectedRevision + 1 AND source_state != 'TOMBSTONED' " +
            "AND :messageRole IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') " +
            "AND :sourceState IN ('ACTIVE', 'SUPERSEDED', 'TOMBSTONED') " +
            "AND :changeKind IN ('CREATED', 'UPDATED', 'BRANCH_SELECTED', " +
            "'BRANCH_SUPERSEDED', 'DELETED', 'CONVERSATION_DELETED') " +
            "AND (:sourceState = 'TOMBSTONED' OR " +
            "(:payloadIntegritySha256 IS NOT NULL AND length(:payloadIntegritySha256) = 64 " +
            "AND :payloadIntegritySha256 NOT GLOB '*[^0-9a-f]*')) " +
            "AND :occurredAtMs >= 0 AND :updatedAtMs >= :occurredAtMs",
    )
    suspend fun updateMessageFenced(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        messageId: String,
        expectedRevision: Long,
        nextRevision: Long,
        messageRole: String,
        sourceState: String,
        changeKind: String,
        payloadIntegritySha256: String?,
        occurredAtMs: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "SELECT * FROM learning_message_source_authority " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId AND message_id > :afterMessageId " +
            "ORDER BY message_id ASC LIMIT :limit",
    )
    suspend fun listMessagesForConversationAfter(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        afterMessageId: String,
        limit: Int,
    ): List<LearningMessageSourceAuthorityEntity>

    @Query(
        "SELECT COUNT(*) FROM learning_message_source_authority " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND conversation_id = :conversationId",
    )
    suspend fun countMessagesForConversation(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
    ): Int
}
