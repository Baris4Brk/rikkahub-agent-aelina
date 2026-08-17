package me.rerere.rikkahub.learning.workflow.review

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.promotion.LearnedWorkflowPromotionService
import me.rerere.rikkahub.learning.promotion.WorkflowPromotionFence
import me.rerere.rikkahub.learning.promotion.WorkflowPromotionResult
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import kotlin.uuid.Uuid

class ProductionWorkflowReviewRepository(
    private val runtime: WorkflowReviewRuntimePort,
    private val grantAuthority: PolicyGrantAuthoritySource,
    private val promotion: LearnedWorkflowPromotionService,
    private val workflows: WorkflowRepository,
    private val metadataSource: WorkflowReviewToolMetadataSource,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : WorkflowReviewRepository {
    override suspend fun list(
        consumingAssistantId: Uuid,
        limit: Int,
    ): WorkflowReviewReadResult<List<WorkflowReviewListItem>> =
        runtime.listWorkflowCandidates(consumingAssistantId, limit)

    override suspend fun detail(
        consumingAssistantId: Uuid,
        candidateId: String,
    ): WorkflowReviewReadResult<WorkflowReviewDetail> = when (
        val read = runtime.readWorkflowCandidate(consumingAssistantId, candidateId)
    ) {
        is WorkflowReviewReadResult.Ready -> try {
            val projected = read.value.withCurrentToolMetadata()
                ?: return WorkflowReviewReadResult.Unavailable(
                    WorkflowReviewUnavailableReason.VALIDATION_UNAVAILABLE,
                )
            WorkflowReviewReadResult.Ready(projected.withInstalledWorkflowVersion())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WorkflowReviewReadResult.Unavailable(WorkflowReviewUnavailableReason.STORAGE_FAILURE)
        }
        WorkflowReviewReadResult.NotFound -> WorkflowReviewReadResult.NotFound
        is WorkflowReviewReadResult.Unavailable -> read
    }

    override suspend fun promoteDisabled(
        command: PromoteWorkflowDisabledCommand,
    ): WorkflowReviewMutationResult = mutate(command.consumingAssistantId, command.fence) {
        detail, exactGrant ->
        promotion.promoteVerifiedDisabled(
            fence = detail.toPromotionFence(),
            exactGrant = exactGrant,
            nowMs = frozenNow(),
        )
    }

    override suspend fun enable(
        command: EnablePromotedWorkflowCommand,
    ): WorkflowReviewMutationResult {
        if (!command.explicitUserConfirmation) {
            return WorkflowReviewMutationResult.Rejected("EXPLICIT_CONFIRMATION_REQUIRED")
        }
        return mutate(command.consumingAssistantId, command.fence) { detail, exactGrant ->
            if (detail.installedWorkflowStateVersion != command.expectedWorkflowStateVersion) {
                return@mutate WorkflowPromotionResult.Rejected(
                    WorkflowPromotionResult.Reason.WORKFLOW_ENABLE_CONFLICT,
                )
            }
            promotion.enableAfterExplicitConfirmation(
                fence = detail.toPromotionFence(),
                exactGrant = exactGrant,
                expectedWorkflowStateVersion = command.expectedWorkflowStateVersion,
                userConfirmed = true,
                nowMs = frozenNow(),
            )
        }
    }

    private suspend fun mutate(
        consumingAssistantId: Uuid,
        fence: WorkflowReviewFence,
        action: suspend (
            WorkflowReviewDetail,
            PolicyGrantAuthoritySnapshot,
        ) -> WorkflowPromotionResult,
    ): WorkflowReviewMutationResult = try {
        val detail = when (val fresh = detail(consumingAssistantId, fence.candidateId)) {
            is WorkflowReviewReadResult.Ready -> fresh.value
            WorkflowReviewReadResult.NotFound -> return WorkflowReviewMutationResult.Conflict
            is WorkflowReviewReadResult.Unavailable -> {
                return WorkflowReviewMutationResult.Unavailable(fresh.reason)
            }
        }
        if (detail.item.fence != fence) return WorkflowReviewMutationResult.Conflict
        val grant = exactGrant(detail, consumingAssistantId)
            ?: return WorkflowReviewMutationResult.Unavailable(
                WorkflowReviewUnavailableReason.AUTHORITY_UNAVAILABLE,
            )
        action(detail, grant).toReviewMutation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        WorkflowReviewMutationResult.Unavailable(WorkflowReviewUnavailableReason.STORAGE_FAILURE)
    }

    private suspend fun exactGrant(
        detail: WorkflowReviewDetail,
        consumingAssistantId: Uuid,
    ): PolicyGrantAuthoritySnapshot? {
        val candidateScope = detail.authoritySubjectId?.let {
            me.rerere.rikkahub.learning.model.LearningScope.AuthoritySubject(it)
        } ?: me.rerere.rikkahub.learning.model.LearningScope.Assistant(consumingAssistantId)
        // Stream identity is deliberately not inferred by the UI. A bounded authority scan is
        // recovered from the digest-bound grant by paging current heads once.
        var cursor: me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanCursor? = null
        var scanned = 0
        while (scanned < MAX_AUTHORITY_SCAN) {
            val page = grantAuthority.listCurrentPage(cursor, AUTHORITY_PAGE_SIZE)
            val ready = page as? me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanResult.Ready
                ?: return null
            for (grant in ready.page.snapshots) {
                if (grant.scope == candidateScope &&
                    grant.consumingAssistantId == consumingAssistantId &&
                    grant.policyId == detail.item.sourcePolicyId &&
                    grant.contentRevision == detail.item.sourcePolicyRevision &&
                    grant.artifactSha256 == detail.sourcePolicyArtifactSha256 &&
                    WorkflowArtifactCanonicalizer.grantDigest(
                        grant.grantId,
                        grant.sourceStreamId,
                        grant.stateVersion,
                        grant.contentRevision,
                        grant.artifactSha256,
                    ) == detail.sourceGrantDigest && grantAuthority.revalidateExact(grant)
                ) return grant
            }
            scanned += ready.page.scannedHeadCount
            cursor = ready.page.nextCursor ?: return null
        }
        return null
    }

    private suspend fun WorkflowReviewDetail.withInstalledWorkflowVersion(): WorkflowReviewDetail {
        val loaded = workflows.getById("learned:${item.fence.candidateId}") ?: return copy(
            installedWorkflowStateVersion = null,
        )
        val row = loaded.entity
        val exact = !row.enabled && row.sourceCandidateId == item.fence.candidateId &&
            row.sourceArtifactHash == item.fence.artifactSha256 &&
            row.grantDigest == sourceGrantDigest && row.staleReason == null
        return copy(installedWorkflowStateVersion = row.stateVersion.takeIf { exact })
    }

    private suspend fun WorkflowReviewDetail.withCurrentToolMetadata(): WorkflowReviewDetail? {
        val catalog = metadataSource.current(assistantId, authoritySubjectId) ?: return null
        val enriched = actions.map { action ->
            val entry = catalog.entry(action.toolName) ?: return null
            if (entry.schemaFingerprint != action.schemaSha256) return null
            val risk = entry.risk?.name ?: return null
            val origins = entry.allowedOrigins.map { it.name }.sorted()
            if (me.rerere.rikkahub.data.ai.ToolCallOrigin.TrustedWorkflow.name !in origins) {
                return null
            }
            action.copy(
                risk = risk,
                origin = me.rerere.rikkahub.data.ai.ToolCallOrigin.TrustedWorkflow.name,
            )
        }
        return copy(actions = enriched)
    }

    private fun WorkflowReviewDetail.toPromotionFence(): WorkflowPromotionFence =
        WorkflowPromotionFence(
            candidateId = item.fence.candidateId,
            candidateVersion = item.fence.candidateVersion,
            artifactSha256 = item.fence.artifactSha256,
            sourceGrantDigest = sourceGrantDigest,
            toolSchemaFingerprintsWire =
                me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
                    .canonicalToolSchemas(actions.map { action ->
                        me.rerere.rikkahub.learning.workflow.model
                            .LearnedWorkflowToolSchemaFingerprint(
                                actionIndex = action.index,
                                toolName = action.toolName,
                                schemaFingerprint = action.schemaSha256,
                            )
                    }),
            verifierVersion = fakeReport?.verifierVersion.orEmpty(),
            assistantId = assistantId,
            authoritySubjectId = authoritySubjectId,
        )

    private fun frozenNow(): Long = clockMs().coerceAtLeast(0L)
}

private fun WorkflowPromotionResult.toReviewMutation(): WorkflowReviewMutationResult = when (this) {
    is WorkflowPromotionResult.PromotedDisabled ->
        WorkflowReviewMutationResult.PromotedDisabled(workflowId, replayed)
    is WorkflowPromotionResult.Enabled -> WorkflowReviewMutationResult.Enabled(workflowId)
    is WorkflowPromotionResult.Rejected -> when (reason) {
        WorkflowPromotionResult.Reason.CANDIDATE_CAS_CONFLICT,
        WorkflowPromotionResult.Reason.FENCE_MISMATCH,
        WorkflowPromotionResult.Reason.WORKFLOW_ENABLE_CONFLICT,
        -> WorkflowReviewMutationResult.Conflict
        else -> WorkflowReviewMutationResult.Rejected(reason.name)
    }
}

private const val AUTHORITY_PAGE_SIZE = 200
private const val MAX_AUTHORITY_SCAN = 2_000

fun interface WorkflowReviewToolMetadataSource {
    /** Rebuilds the exact current Assistant surface; null is fail-closed. */
    suspend fun current(assistantId: String, authoritySubjectId: String?): ToolCatalogSnapshot?
}
