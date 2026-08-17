package me.rerere.rikkahub.learning.review

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.authority.policy.toValidatedGrantSnapshotOrNull
import me.rerere.rikkahub.data.db.dao.LearningPolicyGrantDao
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.learning.grant.PolicyGrantCoordinatedReviewResult
import me.rerere.rikkahub.learning.grant.PolicyGrantFence
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewCommand
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewCoordinator
import me.rerere.rikkahub.learning.grant.PolicyGrantService
import me.rerere.rikkahub.learning.grant.PolicyGrantReviewResult
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseFailureCode
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseService
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseUnavailableException
import kotlin.uuid.Uuid

class ProductionLearningPolicyReviewRepository(
    private val runtime: PolicyReviewRuntimePort,
    private val grantDao: LearningPolicyGrantDao,
    private val grantCoordinator: PolicyGrantReviewCoordinator,
    private val grantService: PolicyGrantService,
    private val eraseService: LearningDerivedEraseService,
    private val positiveMutations: LearningPositiveMutationGate =
        DisabledLearningPositiveMutationGate,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : LearningPolicyReviewRepository {
    override suspend fun list(
        consumingAssistantId: Uuid,
        limit: Int,
    ): PolicyReviewReadResult<List<ReviewedPolicyListItem>> = when (
        val read = runtime.listForReview(consumingAssistantId, limit)
    ) {
        is PolicyReviewReadResult.Ready -> try {
            PolicyReviewReadResult.Ready(
                read.value.map { policy ->
                    ReviewedPolicyListItem(
                        policy = policy,
                        grant = readGrant(policy.fence, consumingAssistantId),
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PolicyReviewReadResult.Unavailable(PolicyReviewUnavailableReason.STORAGE_FAILURE)
        }
        PolicyReviewReadResult.NotFound -> PolicyReviewReadResult.NotFound
        is PolicyReviewReadResult.Unavailable -> read
    }

    override suspend fun detail(
        consumingAssistantId: Uuid,
        policyId: String,
    ): PolicyReviewReadResult<ReviewedPolicyDetail> = when (
        val read = runtime.readForReview(consumingAssistantId, policyId)
    ) {
        is PolicyReviewReadResult.Ready -> try {
            PolicyReviewReadResult.Ready(
                ReviewedPolicyDetail(
                    policy = read.value,
                    grant = readGrant(read.value.item.fence, consumingAssistantId),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PolicyReviewReadResult.Unavailable(PolicyReviewUnavailableReason.STORAGE_FAILURE)
        }
        PolicyReviewReadResult.NotFound -> PolicyReviewReadResult.NotFound
        is PolicyReviewReadResult.Unavailable -> read
    }

    override suspend fun approve(
        command: PolicyReviewActionCommand,
    ): PolicyReviewActionResult {
        positiveMutationUnavailable(
            LearningPositiveMutation.POLICY_APPROVE_OR_RESUME,
        )?.let { return it }
        val current = currentDetail(command) ?: return PolicyReviewActionResult.Conflict
        if (current.policy.item.status !in setOf(
                LearningPolicyStatus.SHADOW,
                LearningPolicyStatus.PROBATION,
                LearningPolicyStatus.ACTIVE,
                LearningPolicyStatus.SUSPENDED,
            )
        ) {
            return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.ACTION_NOT_ALLOWED,
            )
        }
        val streamId = command.fence.sourceStreamId
            ?: return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.STREAM_NOT_READY,
            )
        if (current.grant.stateVersion != command.expectedGrantStateVersion) {
            return PolicyReviewActionResult.Conflict
        }
        if (
            current.policy.item.status == LearningPolicyStatus.SUSPENDED &&
            current.grant.state == PolicyReviewGrantState.EXACT_GRANTED
        ) {
            positiveMutationUnavailable(
                LearningPositiveMutation.POLICY_APPROVE_OR_RESUME,
            )?.let { return it }
            return runtime.mutateForReview(
                PolicyReviewLifecycleCommand(
                    fence = command.fence,
                    action = PolicyReviewLifecycleAction.RESUME,
                    selectedRevision = command.fence.stateVersion,
                    frozenNowMs = frozenNow(),
                ),
            ).toActionResult()
        }
        if (current.policy.item.status == LearningPolicyStatus.SUSPENDED) {
            return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.ACTION_NOT_ALLOWED,
            )
        }
        val fence: PolicyGrantFence
        val reason: PolicyGrantReason
        when (current.grant.state) {
            PolicyReviewGrantState.NONE,
            PolicyReviewGrantState.REVOKED,
            -> {
                fence = PolicyGrantFence.GRANT
                reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE
            }
            PolicyReviewGrantState.STALE_GRANTED -> {
                fence = PolicyGrantFence.UPDATE_EXACT_POLICY
                reason = PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE
            }
            PolicyReviewGrantState.EXACT_GRANTED -> return PolicyReviewActionResult.Duplicate
            PolicyReviewGrantState.STREAM_UNAVAILABLE -> return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.STREAM_NOT_READY,
            )
        }
        positiveMutationUnavailable(
            LearningPositiveMutation.POLICY_APPROVE_OR_RESUME,
        )?.let { return it }
        return coordinate(
            PolicyGrantReviewCommand(
                fence = fence,
                sourceStreamId = streamId,
                scope = command.fence.scope,
                consumingAssistantId = command.consumingAssistantId,
                policyId = command.fence.policyId,
                contentRevision = command.fence.contentRevision,
                artifactSha256 = command.fence.artifactSha256,
                expectedGrantStateVersion = command.expectedGrantStateVersion,
                frozenNowEpochMs = frozenNow(),
                reason = reason,
            ),
        )
    }

    override suspend fun revoke(
        command: PolicyReviewActionCommand,
    ): PolicyReviewActionResult {
        val current = currentDetail(command) ?: return PolicyReviewActionResult.Conflict
        if (current.grant.state != PolicyReviewGrantState.EXACT_GRANTED ||
            current.grant.stateVersion != command.expectedGrantStateVersion
        ) {
            return PolicyReviewActionResult.Conflict
        }
        val streamId = command.fence.sourceStreamId
            ?: return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.STREAM_NOT_READY,
            )
        return coordinate(
            PolicyGrantReviewCommand(
                fence = PolicyGrantFence.REVOKE,
                sourceStreamId = streamId,
                scope = command.fence.scope,
                consumingAssistantId = command.consumingAssistantId,
                policyId = command.fence.policyId,
                contentRevision = command.fence.contentRevision,
                artifactSha256 = command.fence.artifactSha256,
                expectedGrantStateVersion = command.expectedGrantStateVersion,
                frozenNowEpochMs = frozenNow(),
                reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
            ),
        )
    }

    /** Technical scope-wide suspension is distinct from revoking this consuming Assistant. */
    override suspend fun suspendPolicy(
        command: PolicyReviewActionCommand,
    ): PolicyReviewActionResult {
        val current = currentDetail(command) ?: return PolicyReviewActionResult.Conflict
        if (
            current.policy.item.status != LearningPolicyStatus.ACTIVE ||
            current.grant.stateVersion != command.expectedGrantStateVersion
        ) {
            return PolicyReviewActionResult.Conflict
        }
        return runtime.mutateForReview(
            PolicyReviewLifecycleCommand(
                fence = command.fence,
                action = PolicyReviewLifecycleAction.SUSPEND,
                selectedRevision = command.fence.stateVersion,
                frozenNowMs = frozenNow(),
            ),
        ).toActionResult()
    }

    override suspend fun archive(
        command: PolicyReviewActionCommand,
    ): PolicyReviewActionResult {
        val current = currentDetail(command) ?: return PolicyReviewActionResult.Conflict
        if (current.grant.state in setOf(
                PolicyReviewGrantState.EXACT_GRANTED,
                PolicyReviewGrantState.STALE_GRANTED,
            )
        ) {
            return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.GRANT_MUST_BE_REVOKED,
            )
        }
        val streamId = command.fence.sourceStreamId
            ?: return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.STREAM_NOT_READY,
            )
        val otherExactGrants = try {
            grantDao.countExactGrantedConsumers(
                sourceStreamId = streamId,
                scopeKind = command.fence.scope.kind.name,
                scopeId = command.fence.scope.storageId,
                policyId = command.fence.policyId,
                policyRevision = command.fence.contentRevision,
                artifactSha256 = command.fence.artifactSha256,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.STORAGE_FAILURE,
            )
        }
        if (otherExactGrants != 0L) {
            return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.GRANT_MUST_BE_REVOKED,
            )
        }
        if (current.policy.item.status == LearningPolicyStatus.ARCHIVED) {
            return PolicyReviewActionResult.Duplicate
        }
        return runtime.mutateForReview(
            PolicyReviewLifecycleCommand(
                fence = command.fence,
                action = PolicyReviewLifecycleAction.ARCHIVE,
                selectedRevision = command.fence.stateVersion,
                frozenNowMs = frozenNow(),
            ),
        ).toActionResult()
    }

    override suspend fun restoreRevision(
        command: PolicyReviewActionCommand,
        selectedRevision: Long,
    ): PolicyReviewActionResult {
        positiveMutationUnavailable(
            LearningPositiveMutation.POLICY_RESTORE_ARCHIVED_REVISION,
        )?.let { return it }
        val current = currentDetail(command) ?: return PolicyReviewActionResult.Conflict
        if (current.policy.item.status != LearningPolicyStatus.ARCHIVED) {
            return PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.ACTION_NOT_ALLOWED,
            )
        }
        positiveMutationUnavailable(
            LearningPositiveMutation.POLICY_RESTORE_ARCHIVED_REVISION,
        )?.let { return it }
        return runtime.mutateForReview(
            PolicyReviewLifecycleCommand(
                fence = command.fence,
                action = PolicyReviewLifecycleAction.RESTORE_ARCHIVED_REVISION,
                selectedRevision = selectedRevision,
                frozenNowMs = frozenNow(),
            ),
        ).toActionResult()
    }

    override suspend fun issueEraseChallenge(
        scope: me.rerere.rikkahub.learning.model.LearningScope,
    ): PolicyReviewReadResult<PolicyReviewEraseChallenge> = try {
        PolicyReviewReadResult.Ready(
            PolicyReviewEraseChallenge(
                scope = scope,
                authorityToken = eraseService.issueConfirmation(scope),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PolicyReviewReadResult.Unavailable(PolicyReviewUnavailableReason.RUNTIME_NOT_READY)
    }

    override suspend fun erase(
        challenge: PolicyReviewEraseChallenge,
    ): PolicyReviewEraseResult = try {
        if (!revokeAllScopeGrantsBeforeErase(challenge.scope)) {
            return PolicyReviewEraseResult.Unavailable(
                PolicyReviewUnavailableReason.STORAGE_FAILURE,
            )
        }
        PolicyReviewEraseResult.Erased(
            eraseService.eraseConfirmed(challenge.scope, challenge.authorityToken),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: LearningDerivedEraseUnavailableException) {
        when (failure.failureCode) {
            LearningDerivedEraseFailureCode.CONFIRMATION_INVALID -> PolicyReviewEraseResult.Conflict
            LearningDerivedEraseFailureCode.EPHEMERAL_CLEAR_FAILED ->
                PolicyReviewEraseResult.Unavailable(PolicyReviewUnavailableReason.STORAGE_FAILURE)
            LearningDerivedEraseFailureCode.WRONG_PROCESS -> PolicyReviewEraseResult.Unavailable(
                PolicyReviewUnavailableReason.WRONG_PROCESS,
            )
            LearningDerivedEraseFailureCode.RESTORE_IN_PROGRESS -> PolicyReviewEraseResult.Unavailable(
                PolicyReviewUnavailableReason.RESTORE_IN_PROGRESS,
            )
            LearningDerivedEraseFailureCode.DATABASE_OPEN_FAILED,
            LearningDerivedEraseFailureCode.DATABASE_OPERATION_FAILED,
            -> PolicyReviewEraseResult.Unavailable(PolicyReviewUnavailableReason.STORAGE_FAILURE)
        }
    } catch (_: Exception) {
        PolicyReviewEraseResult.Unavailable(PolicyReviewUnavailableReason.STORAGE_FAILURE)
    }

    /** AppDatabase authority is revoked first; a crash can leave derived rows, never live grants. */
    private suspend fun revokeAllScopeGrantsBeforeErase(
        scope: me.rerere.rikkahub.learning.model.LearningScope,
    ): Boolean {
        var afterUpdatedAt = 0L
        var afterGrantId = ""
        var scanned = 0
        val frozenNow = frozenNow()
        while (true) {
            val page = grantDao.listGrantedScopePage(
                scopeKind = scope.kind.name,
                scopeId = scope.storageId,
                afterUpdatedAtMs = afterUpdatedAt,
                afterGrantId = afterGrantId,
                limit = MAX_SCOPE_GRANT_ERASE_PAGE,
            )
            if (page.isEmpty()) return true
            scanned += page.size
            if (scanned > MAX_SCOPE_GRANT_ERASE_TOTAL) return false
            for (head in page) {
                val snapshot = head.toValidatedGrantSnapshotOrNull() ?: return false
                if (snapshot.scope != scope || snapshot.state.name != "GRANTED") return false
                when (grantService.review(
                    PolicyGrantReviewCommand(
                        fence = PolicyGrantFence.REVOKE,
                        sourceStreamId = snapshot.sourceStreamId,
                        scope = snapshot.scope,
                        consumingAssistantId = snapshot.consumingAssistantId,
                        policyId = snapshot.policyId,
                        contentRevision = snapshot.contentRevision,
                        artifactSha256 = snapshot.artifactSha256,
                        expectedGrantStateVersion = snapshot.stateVersion,
                        frozenNowEpochMs = maxOf(frozenNow, snapshot.updatedAtEpochMs),
                        reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
                    ),
                )) {
                    is PolicyGrantReviewResult.Applied,
                    is PolicyGrantReviewResult.Duplicate,
                    -> Unit
                    is PolicyGrantReviewResult.Conflict -> return false
                }
            }
            if (page.size < MAX_SCOPE_GRANT_ERASE_PAGE) return true
            val last = page.last()
            afterUpdatedAt = last.updatedAtMs
            afterGrantId = last.grantId
        }
    }

    override suspend fun exportRedacted(
        consumingAssistantId: Uuid,
        policyId: String,
    ): PolicyReviewExportResult = runtime.exportRedactedReviewReport(
        consumingAssistantId = consumingAssistantId,
        policyId = policyId,
    )

    private suspend fun currentDetail(
        command: PolicyReviewActionCommand,
    ): ReviewedPolicyDetail? = when (
        val read = detail(command.consumingAssistantId, command.fence.policyId)
    ) {
        is PolicyReviewReadResult.Ready -> read.value.takeIf {
            it.policy.item.fence == command.fence
        }
        PolicyReviewReadResult.NotFound,
        is PolicyReviewReadResult.Unavailable,
        -> null
    }

    private suspend fun readGrant(
        fence: PolicyReviewFence,
        consumingAssistantId: Uuid,
    ): PolicyReviewGrantView {
        val streamId = fence.sourceStreamId ?: return PolicyReviewGrantView(
            PolicyReviewGrantState.STREAM_UNAVAILABLE,
            0L,
        )
        val grantId = policyGrantId(
            sourceStreamId = streamId,
            scope = fence.scope,
            consumingAssistantId = consumingAssistantId,
            policyId = fence.policyId,
        )
        val head = grantDao.findHead(grantId) ?: return PolicyReviewGrantView(
            PolicyReviewGrantState.NONE,
            0L,
        )
        return head.toReviewGrantView(fence, consumingAssistantId)
    }

    private suspend fun coordinate(
        command: PolicyGrantReviewCommand,
    ): PolicyReviewActionResult = try {
        when (val result = grantCoordinator.review(command)) {
            is PolicyGrantCoordinatedReviewResult.Completed -> {
                if (result.authorityWasDuplicate && result.lifecycleWasDuplicate) {
                    PolicyReviewActionResult.Duplicate
                } else {
                    PolicyReviewActionResult.Applied
                }
            }
            is PolicyGrantCoordinatedReviewResult.AuthorityCommittedDerivedPending ->
                PolicyReviewActionResult.AuthorityCommittedDerivedPending
            is PolicyGrantCoordinatedReviewResult.AuthorityRejected ->
                PolicyReviewActionResult.Conflict
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PolicyReviewActionResult.Unavailable(PolicyReviewUnavailableReason.STORAGE_FAILURE)
    }

    private fun frozenNow(): Long = clockMs().coerceAtLeast(0L)

    private fun positiveMutationUnavailable(
        mutation: LearningPositiveMutation,
    ): PolicyReviewActionResult.Unavailable? = if (positiveMutations.allows(mutation)) {
        null
    } else {
        PolicyReviewActionResult.Unavailable(PolicyReviewUnavailableReason.FEATURE_DISABLED)
    }
}

private fun LearningPolicyGrantEntity.toReviewGrantView(
    fence: PolicyReviewFence,
    consumingAssistantId: Uuid,
): PolicyReviewGrantView {
    val snapshot = toValidatedGrantSnapshotOrNull()
        ?: return PolicyReviewGrantView(PolicyReviewGrantState.STALE_GRANTED, stateVersion)
    if (snapshot.sourceStreamId != fence.sourceStreamId ||
        snapshot.scope != fence.scope ||
        snapshot.consumingAssistantId != consumingAssistantId ||
        snapshot.policyId != fence.policyId
    ) {
        return PolicyReviewGrantView(PolicyReviewGrantState.STALE_GRANTED, stateVersion)
    }
    val state = when (snapshot.state.name) {
        "REVOKED" -> PolicyReviewGrantState.REVOKED
        "GRANTED" -> if (
            snapshot.contentRevision == fence.contentRevision &&
            snapshot.artifactSha256 == fence.artifactSha256
        ) {
            PolicyReviewGrantState.EXACT_GRANTED
        } else {
            PolicyReviewGrantState.STALE_GRANTED
        }
        else -> PolicyReviewGrantState.STALE_GRANTED
    }
    return PolicyReviewGrantView(state, snapshot.stateVersion)
}

private fun PolicyReviewRuntimeMutationResult.toActionResult(): PolicyReviewActionResult = when (this) {
    is PolicyReviewRuntimeMutationResult.Applied -> PolicyReviewActionResult.Applied
    is PolicyReviewRuntimeMutationResult.Duplicate -> PolicyReviewActionResult.Duplicate
    PolicyReviewRuntimeMutationResult.Conflict -> PolicyReviewActionResult.Conflict
    is PolicyReviewRuntimeMutationResult.Unavailable -> PolicyReviewActionResult.Unavailable(reason)
}

private const val MAX_SCOPE_GRANT_ERASE_PAGE = 200
private const val MAX_SCOPE_GRANT_ERASE_TOTAL = 2_000
