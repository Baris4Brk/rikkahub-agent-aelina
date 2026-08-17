package me.rerere.rikkahub.data.authority.reward

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningSourceAuthorityDao
import me.rerere.rikkahub.data.db.dao.RewardFeedbackAuthorityDao
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityRevisionEntity
import me.rerere.rikkahub.data.db.projection.RewardFeedbackTargetAuthorityProjection

interface RewardFeedbackAuthorityStore {
    suspend fun <T> inAuthorityTransaction(block: suspend RewardFeedbackAuthorityTransaction.() -> T): T

    /** Uses the caller's already-open AppDatabase transaction and never commits independently. */
    suspend fun <T> inCurrentAuthorityTransaction(
        block: suspend RewardFeedbackAuthorityTransaction.() -> T,
    ): T
}

interface RewardFeedbackAuthorityTransaction {
    suspend fun findTerminalCommands(
        targetMessageId: String,
        limit: Int,
    ): List<RewardFeedbackTargetAuthorityProjection>
    suspend fun findMessage(scopeKind: String, scopeId: String, messageId: String):
        LearningMessageSourceAuthorityEntity?
    suspend fun findHead(feedbackId: String): RewardFeedbackAuthorityEntity?
    suspend fun listActiveHeadsForTarget(
        targetMessageId: String,
        limit: Int,
    ): List<RewardFeedbackAuthorityEntity>
    suspend fun insertHeadIgnore(entity: RewardFeedbackAuthorityEntity): Boolean
    suspend fun updateHeadFenced(
        previous: RewardFeedbackAuthorityEntity,
        next: RewardFeedbackAuthorityEntity,
    ): Boolean
    suspend fun insertRevision(entity: RewardFeedbackAuthorityRevisionEntity)
}

class RoomRewardFeedbackAuthorityStore(
    private val database: AppDatabase,
    private val rewardDao: RewardFeedbackAuthorityDao = database.rewardFeedbackAuthorityDao(),
    private val sourceDao: LearningSourceAuthorityDao = database.learningSourceAuthorityDao(),
) : RewardFeedbackAuthorityStore, RewardFeedbackAuthorityTransaction {
    override suspend fun <T> inAuthorityTransaction(
        block: suspend RewardFeedbackAuthorityTransaction.() -> T,
    ): T = database.withTransaction { block(this@RoomRewardFeedbackAuthorityStore) }

    override suspend fun <T> inCurrentAuthorityTransaction(
        block: suspend RewardFeedbackAuthorityTransaction.() -> T,
    ): T {
        check(database.inTransaction()) { "Reward feedback invalidation requires outer transaction" }
        return block(this@RoomRewardFeedbackAuthorityStore)
    }

    override suspend fun findTerminalCommands(targetMessageId: String, limit: Int) =
        rewardDao.findTerminalCommandsForResult(targetMessageId, limit)

    override suspend fun findMessage(scopeKind: String, scopeId: String, messageId: String) =
        sourceDao.findMessage(scopeKind, scopeId, messageId)

    override suspend fun findHead(feedbackId: String) = rewardDao.findHead(feedbackId)

    override suspend fun listActiveHeadsForTarget(targetMessageId: String, limit: Int) =
        rewardDao.listActiveHeadsForTarget(targetMessageId, limit)

    override suspend fun insertHeadIgnore(entity: RewardFeedbackAuthorityEntity): Boolean =
        rewardDao.insertHeadIgnore(entity) != -1L

    override suspend fun updateHeadFenced(
        previous: RewardFeedbackAuthorityEntity,
        next: RewardFeedbackAuthorityEntity,
    ): Boolean = rewardDao.updateHeadFenced(
        feedbackId = previous.feedbackId,
        expectedRevision = previous.sourceRevision,
        nextRevision = next.sourceRevision,
        expectedScopeKind = previous.scopeKind,
        expectedScopeId = previous.scopeId,
        expectedTargetMessageId = previous.targetAssistantMessageId,
        expectedTargetMessageRevision = previous.targetAssistantMessageRevision,
        expectedDimension = previous.dimension,
        conversationId = next.conversationId,
        conversationSourceRevision = next.conversationSourceRevision,
        commandId = next.commandId,
        commandRevision = next.commandRevision,
        lineageId = next.lineageId,
        branchAnchorMessageId = next.branchAnchorMessageId,
        branchAnchorMessageRevision = next.branchAnchorMessageRevision,
        signalKind = next.signalKind,
        valueMilli = next.valueMilli,
        sourceState = next.sourceState,
        integritySha256 = next.integritySha256,
        updatedAtMs = next.updatedAtMs,
    ) == 1

    override suspend fun insertRevision(entity: RewardFeedbackAuthorityRevisionEntity) =
        rewardDao.insertRevision(entity)
}

class RoomRewardFeedbackAuthorityJournalSource(
    private val dao: RewardFeedbackAuthorityDao,
) : RewardFeedbackAuthorityJournalSource {
    override suspend fun listJournalPage(
        fromInclusiveMs: Long,
        toExclusiveMs: Long,
        after: RewardFeedbackJournalCursor,
        limit: Int,
    ): RewardFeedbackJournalPage {
        require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
        require(after.updatedAtMs >= 0L && after.sourceRevision >= 0L)
        require(
            (after.feedbackId.isEmpty() && after.sourceRevision == 0L) ||
                (after.feedbackId.isNotEmpty() && after.sourceRevision > 0L),
        ) { "Incomplete reward feedback journal cursor" }
        require(limit in 1..500)
        val rows = dao.listRevisionPage(
            fromInclusiveMs = fromInclusiveMs,
            toExclusiveMs = toExclusiveMs,
            afterUpdatedAtMs = after.updatedAtMs,
            afterFeedbackId = after.feedbackId,
            afterSourceRevision = after.sourceRevision,
            limit = limit,
        )
        val last = rows.lastOrNull()
        return RewardFeedbackJournalPage(
            events = rows.map(RewardFeedbackAuthorityRevisionEntity::toAuthorityEvent),
            nextCursor = last?.let {
                RewardFeedbackJournalCursor(it.updatedAtMs, it.feedbackId, it.sourceRevision)
            },
        )
    }
}
