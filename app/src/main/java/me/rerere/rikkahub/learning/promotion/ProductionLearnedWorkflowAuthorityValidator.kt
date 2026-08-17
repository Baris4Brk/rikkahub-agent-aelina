package me.rerere.rikkahub.learning.promotion

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanResult
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.workflow.execution.LearnedWorkflowAuthoritySnapshot
import me.rerere.rikkahub.workflow.execution.LearnedWorkflowAuthorityValidator
import me.rerere.rikkahub.learning.workflow.runtime.WorkflowRolloutGate
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin

/**
 * Execution-time P4 authority fence. The executable AppDatabase row is never sufficient by
 * itself: every fire resolves the exact derived candidate, the current durable grant head and the
 * current assistant/tool/schema/capability surface again. Any unavailable store is a denial.
 */
class ProductionLearnedWorkflowAuthorityValidator(
    private val candidates: WorkflowPromotionCandidateRuntime,
    private val grants: PolicyGrantAuthoritySource,
    private val revalidator: WorkflowPromotionRevalidator,
    private val rolloutGate: WorkflowRolloutGate,
    private val sourceAuthority: LearnedWorkflowSourceAuthorityPort,
) : LearnedWorkflowAuthorityValidator {
    override suspend fun isActive(snapshot: LearnedWorkflowAuthoritySnapshot): Boolean = try {
        if (!rolloutGate.promotionEnabled()) return false
        val candidate = candidates.find(snapshot.sourceCandidateId) ?: return false
        if (candidate.state != LearnedWorkflowCandidateState.PROMOTED_DISABLED ||
            candidate.artifactSha256 != snapshot.sourceArtifactHash ||
            candidate.sourceGrantDigest != snapshot.grantDigest ||
            candidate.assistantId != snapshot.authoringAssistantId ||
            candidate.verificationReport == null || candidate.verifiedAtMs == null
        ) return false
        if (!sourceAuthority.isCurrentFailClosed(candidate)) return false
        // Attest the executable definition itself. AppDatabase provenance projections are only
        // indexes: a row whose source hash claims the right candidate but whose action args,
        // trigger or limits drifted must never execute.
        if (!candidate.matchesInstalled(snapshot)) return false

        var cursor: me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanCursor? = null
        var inspected = 0
        while (inspected < MAX_EXECUTION_GRANT_HEADS) {
            val result = grants.listCurrentPage(cursor, EXECUTION_GRANT_PAGE_SIZE)
            val page = (result as? PolicyGrantAuthorityScanResult.Ready)?.page ?: return false
            val exact = page.snapshots.singleOrNull { grant ->
                grant.state == PolicyGrantAuthorityState.GRANTED &&
                    grant.scope == candidate.policyScope &&
                    grant.consumingAssistantId.toString() == candidate.assistantId &&
                    grant.policyId == candidate.sourcePolicyId &&
                    grant.contentRevision == candidate.sourcePolicyRevision &&
                    grant.artifactSha256 == candidate.sourcePolicyArtifactSha256 &&
                    WorkflowArtifactCanonicalizer.grantDigest(
                        grant.grantId,
                        grant.sourceStreamId,
                        grant.stateVersion,
                        grant.contentRevision,
                        grant.artifactSha256,
                    ) == candidate.sourceGrantDigest
            }
            if (exact != null) {
                // The production revalidator performs another exact grant read and reconstructs
                // the current local Tool catalog immediately before returning true.
                return revalidator.validate(candidate, exact)
            }
            inspected += page.scannedHeadCount
            cursor = page.nextCursor ?: return false
            if (page.scannedHeadCount == 0) return false
        }
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
}

internal fun LearnedWorkflowCandidate.matchesInstalled(
    snapshot: LearnedWorkflowAuthoritySnapshot,
): Boolean {
    val templateObject = runCatching {
        Json.parseToJsonElement(canonicalTemplateJson) as? JsonObject
    }.getOrNull() ?: return false
    if (WorkflowArtifactCanonicalizer.canonicalTemplate(templateObject) != canonicalTemplateJson) {
        return false
    }
    val recomputedArtifact = WorkflowArtifactCanonicalizer.artifactSha256(
        canonicalTemplateJson = canonicalTemplateJson,
        canonicalTypedSlots = WorkflowArtifactCanonicalizer.canonicalSlots(typedSlots),
        canonicalCapabilities = WorkflowArtifactCanonicalizer.canonicalCapabilities(
            capabilitySnapshot,
        ),
        canonicalToolSchemas = WorkflowArtifactCanonicalizer.canonicalToolSchemas(
            toolSchemaFingerprints,
        ),
        assistantId = assistantId,
        authoritySubjectId = authoritySubjectId,
        sourcePolicyId = sourcePolicyId,
        sourcePolicyRevision = sourcePolicyRevision,
        sourcePolicyArtifactSha256 = sourcePolicyArtifactSha256,
        sourceGrantDigest = sourceGrantDigest,
        compilerVersion = compilerVersion,
        templateVersion = templateVersion,
    )
    if (recomputedArtifact != artifactSha256) return false

    val storedTemplate = WorkflowJson.parseStoredWithCompatibility(canonicalTemplateJson)
        ?: return false
    if (storedTemplate.learnedScopeStorage != WorkflowJson.LearnedScopeStorage.PERSISTED ||
        storedTemplate.definition.authoritySubjectId != authoritySubjectId
    ) return false
    val template = storedTemplate.definition
    val expectedInstalled = template.copy(
        id = "learned:$id",
        enabled = true,
        origin = WorkflowOrigin.LEARNED,
        sourceCandidateId = id,
        sourceArtifactHash = artifactSha256,
        grantDigest = sourceGrantDigest,
        authoritySubjectId = authoritySubjectId,
    )
    return snapshot.installedDefinition == expectedInstalled
}

private const val EXECUTION_GRANT_PAGE_SIZE = 200
private const val MAX_EXECUTION_GRANT_HEADS = 2_000
