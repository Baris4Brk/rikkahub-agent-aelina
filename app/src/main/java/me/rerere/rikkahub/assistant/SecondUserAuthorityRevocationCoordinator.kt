package me.rerere.rikkahub.assistant

import android.util.Log
import me.rerere.rikkahub.data.capability.CapabilityGrantRepository
import me.rerere.rikkahub.data.execution.CancellationCoordinator
import me.rerere.rikkahub.data.execution.ExecutionRepository
import me.rerere.rikkahub.data.execution.ExecutionStateSource
import me.rerere.rikkahub.data.execution.PendingToolApprovalDao
import me.rerere.rikkahub.data.execution.SecondUserApprovalLifecycle
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.DurableCommandQueue
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.toolcatalog.ToolExperienceRepository
import me.rerere.rikkahub.toolcatalog.ToolShortcutRepository
import kotlin.uuid.Uuid

data class SecondUserRevocationSummary(
    val cancelledCommands: Int,
    val invalidatedApprovals: Int,
    val revokedGrants: Int,
    val cancellationAttempts: Int,
    val revokedPolicyGrants: Int = 0,
    val stalePolicies: Int = 0,
    val staleWorkflowCandidates: Int = 0,
    /** True means the durable authority record intentionally remains REVOKING. */
    val learningAuthorityRevocationPending: Boolean = false,
)

/**
 * Completes a persisted REVOKING state. Every operation is idempotent so a process death during
 * reassignment can only continue revocation; it cannot restore the previous authority epoch.
 */
class SecondUserAuthorityRevocationCoordinator(
    private val authority: SecondUserAuthorityService,
    private val queue: DurableCommandQueue,
    private val grants: CapabilityGrantRepository,
    private val approvalDao: PendingToolApprovalDao,
    private val approvalLifecycle: SecondUserApprovalLifecycle,
    private val conversations: ConversationRepository,
    private val executions: ExecutionRepository,
    private val cancellation: CancellationCoordinator,
    private val chatService: ChatService,
    private val toolExperiences: ToolExperienceRepository,
    private val toolShortcuts: ToolShortcutRepository,
    private val learningAuthorityRevocation: SecondUserLearningAuthorityRevocationSaga,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun resumeIfNeeded(): SecondUserRevocationSummary? {
        val config = authority.currentConfig()
            .takeIf { it.state == SecondUserAuthorityState.REVOKING }
            ?: return null
        val assistantId = config.assistantId ?: return finishInvalidRevocation()
        val conversationId = config.conversationId ?: return finishInvalidRevocation()
        val subjectId = SecondUserAdmissionSnapshot.subjectId(
            assistantId = assistantId,
            conversationId = conversationId,
            authorityEpoch = config.authorityEpoch,
        )
        // Before v39, the same local second-user role used this unversioned principal.  It is
        // never allowed to survive a revocation merely because it cannot match the new epoch.
        val legacySubjectId = "local_second_user:$assistantId:$conversationId"
        val revokedSubjects = setOf(subjectId, legacySubjectId)
        val learningFence = SecondUserLearningAuthorityRevocationFence(
            assistantId = assistantId,
            conversationId = conversationId,
            authorityEpoch = config.authorityEpoch,
            frozenNowMs = nowMs().coerceAtLeast(config.updatedAtMs).coerceAtLeast(0L),
        )

        // Stop is allowed while the authority is revoking. It is only a best-effort interruption;
        // managed children are independently driven through CancellationCoordinator below.
        runCatching { chatService.stopGeneration(conversationId) }
            .onFailure { Log.w(TAG, "Unable to stop revoked conversation generation", it) }

        var cancelledCommands = queue.cancelLegacyUnscopedForConversation(conversationId)
        var revokedGrants = 0
        for (oldSubject in revokedSubjects) {
            cancelledCommands += queue.cancelByAuthoritySubject(oldSubject)
            revokedGrants += grants.revokeSubject(oldSubject)
        }
        // Procedures are scoped to an authority epoch just like queue items and grants. Keep
        // their immutable history for the user, but make an old epoch ineligible for injection.
        toolExperiences.invalidateAuthoritySubjects(revokedSubjects)
        toolShortcuts.invalidateAuthoritySubjects(revokedSubjects)

        var invalidatedApprovals = 0
        approvalDao.getAllPending()
            .filter {
                it.conversationId == conversationId.toString() &&
                    (it.subjectId in revokedSubjects || it.subjectType == SubjectType.LOCAL_SECOND_USER.name)
            }
            .groupBy { it.conversationId }
            .forEach { (rawConversationId, projections) ->
                var conversation = rawConversationId.toUuidOrNull()?.let { conversations.getConversationById(it) }
                projections.forEach { projection ->
                    conversation = runCatching {
                        approvalLifecycle.invalidateProjection(
                            projection = projection,
                            conversation = conversation,
                            reasonCode = "second_user_authority_revoked",
                            orphaned = false,
                            source = ExecutionStateSource.RECOVERY,
                        )
                    }.onSuccess { invalidatedApprovals++ }
                        .onFailure { Log.w(TAG, "Unable to invalidate revoked approval", it) }
                        .getOrNull() ?: conversation
                }
            }

        var cancellationAttempts = 0
        (
            revokedSubjects.flatMap { oldSubject ->
                executions.getInFlightForSubject(conversationId.toString(), oldSubject)
            } + executions.getInFlightForConversationSubjectType(
                conversationId = conversationId.toString(),
                subjectType = SubjectType.LOCAL_SECOND_USER.name,
            )
        ).distinctBy { it.id }.forEach { record ->
            cancellationAttempts++
            runCatching { cancellation.cancelAndAwait(record.id) }
                .onFailure { Log.w(TAG, "Unable to cancel revoked execution ${record.id}", it) }
        }

        // AppDatabase grant revocation and LearningDatabase stale projections form a replayable
        // cross-database saga. Never clear the only durable epoch fence while either side is
        // unavailable: the next boot/user retry resumes the exact same old subject.
        val learningSummary = when (
            val learningResult = learningAuthorityRevocation.resume(learningFence)
        ) {
            is SecondUserLearningAuthorityRevocationResult.Completed -> learningResult.summary
            SecondUserLearningAuthorityRevocationResult.Pending -> {
                return SecondUserRevocationSummary(
                    cancelledCommands = cancelledCommands,
                    invalidatedApprovals = invalidatedApprovals,
                    revokedGrants = revokedGrants,
                    cancellationAttempts = cancellationAttempts,
                    learningAuthorityRevocationPending = true,
                )
            }
        }
        authority.completeUnassign()
        return SecondUserRevocationSummary(
            cancelledCommands = cancelledCommands,
            invalidatedApprovals = invalidatedApprovals,
            revokedGrants = revokedGrants,
            cancellationAttempts = cancellationAttempts,
            revokedPolicyGrants = learningSummary.revokedGrantHeads,
            stalePolicies = learningSummary.policiesMadeStale,
            staleWorkflowCandidates = learningSummary.workflowCandidatesMadeStale,
        )
    }

    private suspend fun finishInvalidRevocation(): SecondUserRevocationSummary {
        authority.completeUnassign()
        return SecondUserRevocationSummary(0, 0, 0, 0)
    }

    private fun String.toUuidOrNull(): Uuid? = runCatching(Uuid::parse).getOrNull()

    private companion object {
        const val TAG = "SecondUserRevocation"
    }
}
