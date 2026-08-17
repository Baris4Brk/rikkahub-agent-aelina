package me.rerere.rikkahub.data.authority.policy

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningPolicyGrantDao
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity
import me.rerere.rikkahub.learning.grant.MAX_POLICY_GRANT_AUTHORITY_RESULTS
import me.rerere.rikkahub.learning.grant.MAX_POLICY_GRANT_REBIND_PAGE_SIZE
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanCursor
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanPage
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanResult
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.isCanonicalPolicyGrantStreamId
import me.rerere.rikkahub.learning.model.LearningScope
import kotlin.uuid.Uuid

/** Content-free, bounded read side for generation-time exact grant checks. */
class RoomPolicyGrantAuthoritySource(
    private val store: PolicyGrantAuthorityReadStore,
) : PolicyGrantAuthoritySource {
    constructor(database: AppDatabase) : this(
        RoomPolicyGrantAuthorityReadStore(database.learningPolicyGrantDao()),
    )

    override suspend fun listExactGranted(
        scope: LearningScope,
        consumingAssistantId: Uuid,
        sourceStreamId: String,
        limit: Int,
    ): List<PolicyGrantAuthoritySnapshot> {
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        if (scope is LearningScope.Assistant) require(scope.assistantId == consumingAssistantId)
        require(sourceStreamId.isCanonicalPolicyGrantStreamId()) { "Invalid grant stream" }
        require(limit in 1..MAX_POLICY_GRANT_AUTHORITY_RESULTS)
        return try {
            val rows = mutableListOf<LearningPolicyGrantEntity>()
            var afterUpdatedAtMs = 0L
            var afterGrantId = ""
            while (rows.size <= limit) {
                val pageLimit = minOf(MAX_AUTHORITY_HEAD_SCAN, limit + 1 - rows.size)
                val page = store.listScopePage(
                    sourceStreamId = sourceStreamId,
                    scopeKind = scope.kind.name,
                    scopeId = scope.storageId,
                    consumingAssistantId = consumingAssistantId.toString(),
                    afterUpdatedAtMs = afterUpdatedAtMs,
                    afterGrantId = afterGrantId,
                    limit = pageLimit,
                )
                if (page.isEmpty()) break
                rows += page
                val last = page.last()
                afterUpdatedAtMs = last.updatedAtMs
                afterGrantId = last.grantId
                if (page.size < pageLimit) break
            }
            // A bounded result must never let early authority rows starve later relevant grants.
            if (rows.size > limit) return emptyList()
            buildList {
                for (row in rows) {
                    if (row.sourceStreamId != sourceStreamId ||
                        row.scopeKind != scope.kind.name || row.scopeId != scope.storageId ||
                        row.consumingAssistantId != consumingAssistantId.toString() ||
                        row.state != PolicyGrantAuthorityState.GRANTED.name
                    ) {
                        continue
                    }
                    val snapshot = row.toValidatedGrantSnapshotOrNull() ?: continue
                    val receipt = store.findRevision(row.grantId, row.stateVersion) ?: continue
                    if (!receipt.isExactRevisionOf(row)) continue
                    add(snapshot)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean {
        return try {
            if (snapshot.state != PolicyGrantAuthorityState.GRANTED) return false
            val current = store.findHead(snapshot.grantId) ?: return false
            val currentSnapshot = current.toValidatedGrantSnapshotOrNull() ?: return false
            if (currentSnapshot != snapshot) return false
            store.findRevision(current.grantId, current.stateVersion)
                ?.isExactRevisionOf(current) == true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun listCurrentPage(
        after: PolicyGrantAuthorityScanCursor?,
        limit: Int,
    ): PolicyGrantAuthorityScanResult {
        require(limit in 1..MAX_POLICY_GRANT_REBIND_PAGE_SIZE)
        val cursor = after ?: PolicyGrantAuthorityScanCursor.START
        return try {
            val rows = store.listCurrentPage(
                afterUpdatedAtMs = cursor.afterUpdatedAtEpochMs,
                afterGrantId = cursor.afterGrantId,
                limit = limit,
            )
            // A store that violates the DAO bound is treated as unavailable, never truncated.
            if (rows.size > limit) return PolicyGrantAuthorityScanResult.Unavailable
            if (!rows.isStrictGlobalPageAfter(cursor)) {
                return PolicyGrantAuthorityScanResult.Unavailable
            }
            val snapshots = mutableListOf<PolicyGrantAuthoritySnapshot>()
            var rejected = 0
            for (row in rows) {
                val snapshot = row.toValidatedGrantSnapshotOrNull()
                val receipt = store.findRevision(row.grantId, row.stateVersion)
                // Close the page-read race: a review may advance the head while its old revision
                // remains perfectly valid. Only a still-current head is eligible for projection.
                val current = store.findHead(row.grantId)
                if (snapshot == null || current != row ||
                    receipt?.isExactRevisionOf(row) != true
                ) {
                    rejected += 1
                } else {
                    snapshots += snapshot
                }
            }
            val endReached = rows.size < limit
            PolicyGrantAuthorityScanResult.Ready(
                PolicyGrantAuthorityScanPage(
                    snapshots = snapshots,
                    nextCursor = rows.lastOrNull()?.takeUnless { endReached }?.let { last ->
                        PolicyGrantAuthorityScanCursor(
                            afterUpdatedAtEpochMs = last.updatedAtMs,
                            afterGrantId = last.grantId,
                        )
                    },
                    scannedHeadCount = rows.size,
                    rejectedHeadCount = rejected,
                    endReached = endReached,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            PolicyGrantAuthorityScanResult.Unavailable
        }
    }
}

private fun List<LearningPolicyGrantEntity>.isStrictGlobalPageAfter(
    after: PolicyGrantAuthorityScanCursor,
): Boolean {
    var previousUpdatedAt = after.afterUpdatedAtEpochMs
    var previousGrantId = after.afterGrantId
    for (row in this) {
        val strictlyAfter = row.updatedAtMs > previousUpdatedAt ||
            (row.updatedAtMs == previousUpdatedAt && row.grantId > previousGrantId)
        if (!strictlyAfter) return false
        previousUpdatedAt = row.updatedAtMs
        previousGrantId = row.grantId
    }
    return true
}

interface PolicyGrantAuthorityReadStore {
    suspend fun listScopePage(
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity>

    suspend fun listCurrentPage(
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity>

    suspend fun findHead(grantId: String): LearningPolicyGrantEntity?
    suspend fun findRevision(grantId: String, stateVersion: Long): LearningPolicyGrantRevisionEntity?
}

class RoomPolicyGrantAuthorityReadStore(
    private val dao: LearningPolicyGrantDao,
) : PolicyGrantAuthorityReadStore {
    override suspend fun listScopePage(
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity> = dao.listScopePage(
        sourceStreamId,
        scopeKind,
        scopeId,
        consumingAssistantId,
        afterUpdatedAtMs,
        afterGrantId,
        limit,
    )

    override suspend fun listCurrentPage(
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity> = dao.listCurrentPage(
        afterUpdatedAtMs,
        afterGrantId,
        limit,
    )

    override suspend fun findHead(grantId: String): LearningPolicyGrantEntity? =
        dao.findHead(grantId)

    override suspend fun findRevision(
        grantId: String,
        stateVersion: Long,
    ): LearningPolicyGrantRevisionEntity? = dao.findRevision(grantId, stateVersion)
}

/** DAO pages are bounded at 200; the public source has a separate total-scan cap. */
private const val MAX_AUTHORITY_HEAD_SCAN = 200
