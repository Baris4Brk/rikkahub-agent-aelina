package me.rerere.rikkahub.data.authority.policy

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningPolicyGrantDao
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity

/** Small seam that keeps grant transition tests independent of an Android Room runtime. */
interface PolicyGrantTransactionStore {
    suspend fun <T> inTransaction(block: suspend PolicyGrantTransaction.() -> T): T
}

interface PolicyGrantTransaction {
    suspend fun findHead(grantId: String): LearningPolicyGrantEntity?
    suspend fun insertHead(entity: LearningPolicyGrantEntity)
    suspend fun updateHeadFenced(
        previous: LearningPolicyGrantEntity,
        next: LearningPolicyGrantEntity,
    ): Boolean
    suspend fun insertRevision(entity: LearningPolicyGrantRevisionEntity)
    suspend fun findRevision(grantId: String, stateVersion: Long): LearningPolicyGrantRevisionEntity?
}

class RoomPolicyGrantTransactionStore(
    private val database: AppDatabase,
    private val dao: LearningPolicyGrantDao = database.learningPolicyGrantDao(),
) : PolicyGrantTransactionStore, PolicyGrantTransaction {
    override suspend fun <T> inTransaction(
        block: suspend PolicyGrantTransaction.() -> T,
    ): T = database.withTransaction { block(this@RoomPolicyGrantTransactionStore) }

    override suspend fun findHead(grantId: String): LearningPolicyGrantEntity? =
        dao.findHead(grantId)

    override suspend fun insertHead(entity: LearningPolicyGrantEntity) = dao.insertHead(entity)

    override suspend fun updateHeadFenced(
        previous: LearningPolicyGrantEntity,
        next: LearningPolicyGrantEntity,
    ): Boolean {
        check(previous.grantId == next.grantId)
        check(previous.sourceStreamId == next.sourceStreamId)
        check(previous.scopeKind == next.scopeKind && previous.scopeId == next.scopeId)
        check(previous.consumingAssistantId == next.consumingAssistantId)
        check(previous.policyId == next.policyId)
        check(next.stateVersion == previous.stateVersion + 1L)
        val changed = when (previous.state to next.state) {
            "REVOKED" to "GRANTED" -> dao.grantFenced(
                grantId = previous.grantId,
                sourceStreamId = previous.sourceStreamId,
                scopeKind = previous.scopeKind,
                scopeId = previous.scopeId,
                consumingAssistantId = previous.consumingAssistantId,
                policyId = previous.policyId,
                expectedStateVersion = previous.stateVersion,
                nextStateVersion = next.stateVersion,
                policyRevision = next.policyRevision,
                artifactSha256 = next.artifactSha256,
                grantedAtMs = next.grantedAtMs,
                reasonCode = next.reasonCode,
                updatedAtMs = next.updatedAtMs,
            )
            "GRANTED" to "REVOKED" -> dao.revokeFenced(
                grantId = previous.grantId,
                sourceStreamId = previous.sourceStreamId,
                scopeKind = previous.scopeKind,
                scopeId = previous.scopeId,
                consumingAssistantId = previous.consumingAssistantId,
                policyId = previous.policyId,
                expectedStateVersion = previous.stateVersion,
                nextStateVersion = next.stateVersion,
                revokedAtMs = requireNotNull(next.revokedAtMs),
                reasonCode = next.reasonCode,
                updatedAtMs = next.updatedAtMs,
            )
            "GRANTED" to "GRANTED" -> dao.updateGrantedPolicyFenced(
                grantId = previous.grantId,
                sourceStreamId = previous.sourceStreamId,
                scopeKind = previous.scopeKind,
                scopeId = previous.scopeId,
                consumingAssistantId = previous.consumingAssistantId,
                policyId = previous.policyId,
                expectedStateVersion = previous.stateVersion,
                nextStateVersion = next.stateVersion,
                policyRevision = next.policyRevision,
                artifactSha256 = next.artifactSha256,
                reasonCode = next.reasonCode,
                updatedAtMs = next.updatedAtMs,
            )
            else -> error("Unsupported Policy grant transition")
        }
        return changed == 1
    }

    override suspend fun insertRevision(entity: LearningPolicyGrantRevisionEntity) =
        dao.insertRevision(entity)

    override suspend fun findRevision(
        grantId: String,
        stateVersion: Long,
    ): LearningPolicyGrantRevisionEntity? = dao.findRevision(grantId, stateVersion)
}
