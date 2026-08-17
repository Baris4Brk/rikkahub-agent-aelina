package me.rerere.rikkahub.data.authority.policy

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantConflict
import me.rerere.rikkahub.learning.grant.PolicyGrantFence
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewCommand
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewResult
import me.rerere.rikkahub.learning.grant.PolicyGrantService
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry

/**
 * AppDatabase authority writer for contextual-advice grants.
 *
 * Every successful transition changes its head and appends the exact immutable revision in the
 * same Room transaction. It accepts no Policy body and intentionally has no post-commit callback.
 */
class RoomPolicyGrantService(
    private val store: PolicyGrantTransactionStore,
) : PolicyGrantService {
    constructor(database: AppDatabase) : this(RoomPolicyGrantTransactionStore(database))

    override suspend fun review(command: PolicyGrantReviewCommand): PolicyGrantReviewResult = try {
        if (!command.hasLiveReservedSecondUserAuthority()) {
            PolicyGrantReviewResult.Conflict(
                PolicyGrantConflict.AUTHORITY_SUBJECT_INACTIVE,
            )
        } else {
            store.inTransaction { applyReview(command) }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        PolicyGrantReviewResult.Conflict(PolicyGrantConflict.STORAGE_FAILURE)
    }

    private suspend fun PolicyGrantTransaction.applyReview(
        command: PolicyGrantReviewCommand,
    ): PolicyGrantReviewResult {
        val grantId = policyGrantId(
            command.sourceStreamId,
            command.scope,
            command.consumingAssistantId,
            command.policyId,
        )
        val current = findHead(grantId)
        if (current == null) return insertFirstGrant(command, grantId)

        val currentSnapshot = current.toValidatedGrantSnapshotOrNull()
            ?: return conflict(PolicyGrantConflict.IDENTITY_MISMATCH, current.stateVersion)
        if (!current.hasBaseIdentity(command, grantId)) {
            return conflict(PolicyGrantConflict.IDENTITY_MISMATCH, current.stateVersion)
        }
        if (!hasExactAuditRevision(current)) {
            return conflict(PolicyGrantConflict.AUDIT_REVISION_MISSING, current.stateVersion)
        }
        if (command.frozenNowEpochMs < current.updatedAtMs) {
            return conflict(PolicyGrantConflict.CLOCK_ROLLBACK, current.stateVersion)
        }

        val sameRevision = current.policyRevision == command.contentRevision
        val sameArtifact = current.artifactSha256 == command.artifactSha256
        if (sameRevision != sameArtifact) {
            return conflict(
                PolicyGrantConflict.POLICY_REVISION_IDENTITY_MISMATCH,
                current.stateVersion,
            )
        }

        val targetState = when (command.fence) {
            PolicyGrantFence.GRANT, PolicyGrantFence.UPDATE_EXACT_POLICY -> "GRANTED"
            PolicyGrantFence.REVOKE -> "REVOKED"
        }
        if (
            current.state == targetState && sameRevision && sameArtifact &&
            current.reasonCode == command.reason.name
        ) {
            return PolicyGrantReviewResult.Duplicate(currentSnapshot)
        }
        if (command.expectedGrantStateVersion != current.stateVersion) {
            return conflict(PolicyGrantConflict.STALE_STATE_VERSION, current.stateVersion)
        }
        if (current.stateVersion == Long.MAX_VALUE) {
            return conflict(PolicyGrantConflict.STATE_VERSION_OVERFLOW, current.stateVersion)
        }

        val next = when (command.fence) {
            PolicyGrantFence.GRANT -> {
                if (current.state != "REVOKED") {
                    return conflict(PolicyGrantConflict.HEAD_ALREADY_EXISTS, current.stateVersion)
                }
                current.copy(
                    policyRevision = command.contentRevision,
                    artifactSha256 = command.artifactSha256,
                    actor = FIXED_REVIEW_ACTOR,
                    state = "GRANTED",
                    stateVersion = current.stateVersion + 1L,
                    grantedAtMs = command.frozenNowEpochMs,
                    revokedAtMs = null,
                    reasonCode = command.reason.name,
                    updatedAtMs = command.frozenNowEpochMs,
                )
            }
            PolicyGrantFence.UPDATE_EXACT_POLICY -> {
                if (current.state != "GRANTED") {
                    return conflict(PolicyGrantConflict.INVALID_TRANSITION, current.stateVersion)
                }
                current.copy(
                    policyRevision = command.contentRevision,
                    artifactSha256 = command.artifactSha256,
                    actor = FIXED_REVIEW_ACTOR,
                    stateVersion = current.stateVersion + 1L,
                    reasonCode = command.reason.name,
                    updatedAtMs = command.frozenNowEpochMs,
                )
            }
            PolicyGrantFence.REVOKE -> {
                if (current.state != "GRANTED") {
                    return conflict(PolicyGrantConflict.INVALID_TRANSITION, current.stateVersion)
                }
                if (!sameRevision || !sameArtifact) {
                    return conflict(
                        PolicyGrantConflict.POLICY_REVISION_IDENTITY_MISMATCH,
                        current.stateVersion,
                    )
                }
                current.copy(
                    actor = FIXED_REVIEW_ACTOR,
                    state = "REVOKED",
                    stateVersion = current.stateVersion + 1L,
                    revokedAtMs = command.frozenNowEpochMs,
                    reasonCode = command.reason.name,
                    updatedAtMs = command.frozenNowEpochMs,
                )
            }
        }
        if (!updateHeadFenced(current, next)) {
            return conflict(PolicyGrantConflict.STALE_STATE_VERSION, current.stateVersion)
        }
        appendAndVerify(next)
        return PolicyGrantReviewResult.Applied(requireNotNull(next.toValidatedGrantSnapshotOrNull()))
    }

    private suspend fun PolicyGrantTransaction.insertFirstGrant(
        command: PolicyGrantReviewCommand,
        grantId: String,
    ): PolicyGrantReviewResult {
        if (command.fence != PolicyGrantFence.GRANT) {
            return conflict(PolicyGrantConflict.MISSING_HEAD)
        }
        if (command.expectedGrantStateVersion != ABSENT_STATE_VERSION) {
            return conflict(PolicyGrantConflict.MISSING_HEAD)
        }
        val first = LearningPolicyGrantEntity(
            grantId = grantId,
            sourceStreamId = command.sourceStreamId,
            policyId = command.policyId,
            policyRevision = command.contentRevision,
            artifactSha256 = command.artifactSha256,
            scopeKind = command.scope.kind.name,
            scopeId = command.scope.storageId,
            consumingAssistantId = command.consumingAssistantId.toString(),
            actor = FIXED_REVIEW_ACTOR,
            state = "GRANTED",
            stateVersion = 1L,
            grantedAtMs = command.frozenNowEpochMs,
            revokedAtMs = null,
            reasonCode = command.reason.name,
            createdAtMs = command.frozenNowEpochMs,
            updatedAtMs = command.frozenNowEpochMs,
        )
        insertHead(first)
        appendAndVerify(first)
        return PolicyGrantReviewResult.Applied(requireNotNull(first.toValidatedGrantSnapshotOrNull()))
    }

    private suspend fun PolicyGrantTransaction.hasExactAuditRevision(
        head: LearningPolicyGrantEntity,
    ): Boolean = findRevision(head.grantId, head.stateVersion) == head.toRevisionEntity()

    /** Throws to force rollback if either half of a head+journal write is not exact. */
    private suspend fun PolicyGrantTransaction.appendAndVerify(next: LearningPolicyGrantEntity) {
        val revision = next.toRevisionEntity()
        insertRevision(revision)
        if (findHead(next.grantId) != next ||
            findRevision(next.grantId, next.stateVersion) != revision
        ) {
            throw PolicyGrantStorageInvariantException()
        }
    }
}

internal fun LearningPolicyGrantEntity.toValidatedGrantSnapshotOrNull():
    PolicyGrantAuthoritySnapshot? {
    return try {
        if (actor != FIXED_REVIEW_ACTOR && actor != AUTHORITY_REVOCATION_ACTOR) return null
        val parsedScope = LearningScope.parseOrNull(scopeKind, scopeId) ?: return null
        val parsedState = PolicyGrantAuthorityState.entries.firstOrNull { it.name == state }
            ?: return null
        val parsedReason = PolicyGrantReason.entries.firstOrNull { it.name == reasonCode }
            ?: return null
        PolicyGrantAuthoritySnapshot(
            grantId = grantId,
            sourceStreamId = sourceStreamId,
            scope = parsedScope,
            consumingAssistantId = kotlin.uuid.Uuid.parse(consumingAssistantId),
            policyId = policyId,
            contentRevision = policyRevision,
            artifactSha256 = artifactSha256,
            state = parsedState,
            stateVersion = stateVersion,
            grantedAtEpochMs = grantedAtMs,
            revokedAtEpochMs = revokedAtMs,
            reason = parsedReason,
            createdAtEpochMs = createdAtMs,
            updatedAtEpochMs = updatedAtMs,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun LearningPolicyGrantRevisionEntity.isExactRevisionOf(
    head: LearningPolicyGrantEntity,
): Boolean = this == head.toRevisionEntity()

private fun LearningPolicyGrantEntity.hasBaseIdentity(
    command: PolicyGrantReviewCommand,
    expectedGrantId: String,
): Boolean =
    grantId == expectedGrantId &&
        sourceStreamId == command.sourceStreamId &&
        policyId == command.policyId &&
        scopeKind == command.scope.kind.name &&
        scopeId == command.scope.storageId &&
        consumingAssistantId == command.consumingAssistantId.toString() &&
        actor == FIXED_REVIEW_ACTOR

/** Reserved second-user principals are admitted only while that exact epoch is active. */
private fun PolicyGrantReviewCommand.hasLiveReservedSecondUserAuthority(): Boolean {
    val subject = (scope as? LearningScope.AuthoritySubject)?.authoritySubjectId ?: return true
    if (subject.startsWith(LEGACY_SECOND_USER_SUBJECT_PREFIX)) return false
    if (!subject.startsWith(EPOCHED_SECOND_USER_SUBJECT_PREFIX)) return true
    val active = SecondUserAuthorityRegistry.current() ?: return false
    return active.subjectId == subject && active.assistantId == consumingAssistantId
}

private fun conflict(
    reason: PolicyGrantConflict,
    currentStateVersion: Long? = null,
): PolicyGrantReviewResult.Conflict = PolicyGrantReviewResult.Conflict(reason, currentStateVersion)

private class PolicyGrantStorageInvariantException : IllegalStateException()

internal const val FIXED_REVIEW_ACTOR = "USER_REVIEW"
internal const val AUTHORITY_REVOCATION_ACTOR = "AUTHORITY_REVOCATION"
private const val ABSENT_STATE_VERSION = 0L
private const val EPOCHED_SECOND_USER_SUBJECT_PREFIX = "local-second-user:v1:"
private const val LEGACY_SECOND_USER_SUBJECT_PREFIX = "local_second_user:"
