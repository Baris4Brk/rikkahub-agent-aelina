package me.rerere.rikkahub.data.authority.policy

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationPage
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationPageRequest
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationPageResult
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationCursor
import me.rerere.rikkahub.assistant.SecondUserPolicyGrantRevocationPort
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason

/**
 * Exact AppDatabase authority step for a persisted second-user REVOKING saga.
 *
 * A page uses `grant_id`, the only immutable ordering key available on the authority head.  Head
 * CAS and its append-only revision share one Room transaction.  Already-revoked rows remain in
 * the page so a crash after this commit but before LearningDatabase invalidation is replayable.
 */
class RoomSecondUserPolicyGrantRevocationPort(
    private val database: AppDatabase,
) : SecondUserPolicyGrantRevocationPort {
    override suspend fun revokeExactPage(
        request: SecondUserPolicyGrantRevocationPageRequest,
    ): SecondUserPolicyGrantRevocationPageResult = try {
        database.withTransaction {
            val dao = database.learningPolicyGrantDao()
            val rows = dao.listSecondUserAuthorityRevocationPage(
                authoritySubjectId = request.authoritySubjectId,
                consumingAssistantId = request.fence.assistantId.toString(),
                afterGrantId = request.cursor.afterGrantId,
                limit = request.limit,
            )
            if (rows.size > request.limit || !rows.isStrictPageFor(request)) {
                throw SecondUserPolicyGrantRevocationInvariantException()
            }

            var revoked = 0
            val postTransaction = ArrayList<LearningPolicyGrantEntity>(rows.size)
            for (row in rows) {
                val exactCurrentRevision = dao.findRevision(row.grantId, row.stateVersion)
                if (exactCurrentRevision != row.toRevisionEntity()) {
                    throw SecondUserPolicyGrantRevocationInvariantException()
                }
                val next = when (row.state) {
                    PolicyGrantAuthorityState.REVOKED.name -> row
                    PolicyGrantAuthorityState.GRANTED.name -> {
                        if (row.stateVersion == Long.MAX_VALUE) {
                            throw SecondUserPolicyGrantRevocationInvariantException()
                        }
                        val revokedAtMs = maxOf(
                            request.fence.frozenNowMs,
                            row.updatedAtMs,
                            row.grantedAtMs,
                        )
                        val changed = dao.revokeSecondUserAuthorityFenced(
                            grantId = row.grantId,
                            authoritySubjectId = request.authoritySubjectId,
                            consumingAssistantId = request.fence.assistantId.toString(),
                            expectedStateVersion = row.stateVersion,
                            nextStateVersion = row.stateVersion + 1L,
                            revokedAtMs = revokedAtMs,
                        )
                        if (changed != 1) {
                            throw SecondUserPolicyGrantRevocationInvariantException()
                        }
                        row.copy(
                            actor = AUTHORITY_REVOCATION_ACTOR,
                            state = PolicyGrantAuthorityState.REVOKED.name,
                            stateVersion = row.stateVersion + 1L,
                            revokedAtMs = revokedAtMs,
                            reasonCode = PolicyGrantReason.SECOND_USER_AUTHORITY_REVOKED.name,
                            updatedAtMs = revokedAtMs,
                        ).also { nextRow ->
                            dao.insertRevision(nextRow.toRevisionEntity())
                            revoked += 1
                        }
                    }
                    else -> throw SecondUserPolicyGrantRevocationInvariantException()
                }
                if (dao.findHead(next.grantId) != next ||
                    dao.findRevision(next.grantId, next.stateVersion) != next.toRevisionEntity()
                ) {
                    throw SecondUserPolicyGrantRevocationInvariantException()
                }
                postTransaction += next
            }

            val receipts = postTransaction.map { row ->
                row.toValidatedGrantSnapshotOrNull()
                    ?: throw SecondUserPolicyGrantRevocationInvariantException()
            }
            SecondUserPolicyGrantRevocationPageResult.Ready(
                SecondUserPolicyGrantRevocationPage(
                    receipts = receipts,
                    nextCursor = rows.lastOrNull()
                        ?.takeIf { rows.size == request.limit }
                        ?.let { SecondUserPolicyGrantRevocationCursor(it.grantId) },
                    revokedInTransaction = revoked,
                ),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        SecondUserPolicyGrantRevocationPageResult.Unavailable
    }
}

private fun List<LearningPolicyGrantEntity>.isStrictPageFor(
    request: SecondUserPolicyGrantRevocationPageRequest,
): Boolean {
    var previousGrantId = request.cursor.afterGrantId
    for (row in this) {
        if (row.grantId <= previousGrantId ||
            row.scopeKind != "AUTHORITY_SUBJECT" ||
            row.scopeId != request.authoritySubjectId ||
            row.consumingAssistantId != request.fence.assistantId.toString()
        ) {
            return false
        }
        previousGrantId = row.grantId
    }
    return true
}

private class SecondUserPolicyGrantRevocationInvariantException : IllegalStateException()

