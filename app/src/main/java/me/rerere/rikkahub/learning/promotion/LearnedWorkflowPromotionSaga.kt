package me.rerere.rikkahub.learning.promotion

import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.storage.dao.LearnedWorkflowCandidateDao
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import me.rerere.rikkahub.learning.storage.entity.toDomainOrNull
import me.rerere.rikkahub.learning.storage.entity.toEntity
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowAuthorityResolver
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowFakeAdapterRegistry
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowValidationContext
import me.rerere.rikkahub.learning.workflow.WorkflowCandidateValidator
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

data class WorkflowPromotionFence(
    val candidateId: String,
    val candidateVersion: Long,
    val artifactSha256: String,
    val sourceGrantDigest: String,
    val toolSchemaFingerprintsWire: String,
    val verifierVersion: String,
    val assistantId: String,
    val authoritySubjectId: String?,
)

fun interface WorkflowPromotionRevalidator {
    /** Must re-read current schema, assistant/scope authority and exact AppDatabase grant. */
    suspend fun validate(
        candidate: LearnedWorkflowCandidate,
        exactGrant: PolicyGrantAuthoritySnapshot,
    ): Boolean
}

/** Production supplies a fresh, complete validation context for every mutation attempt. */
fun interface WorkflowPromotionValidationContextSource {
    suspend fun validateCurrent(
        candidate: LearnedWorkflowCandidate,
        exactGrant: PolicyGrantAuthoritySnapshot,
    ): Boolean
}

class ProductionWorkflowPromotionRevalidator(
    private val source: WorkflowPromotionValidationContextSource,
) : WorkflowPromotionRevalidator {
    override suspend fun validate(
        candidate: LearnedWorkflowCandidate,
        exactGrant: PolicyGrantAuthoritySnapshot,
    ): Boolean = source.validateCurrent(candidate, exactGrant)
}

fun interface WorkflowPromotionToolCatalogSource {
    suspend fun current(candidate: LearnedWorkflowCandidate): me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot?
}

/** Full local revalidation; the exact grant is re-read by the saga caller immediately beforehand. */
class CurrentWorkflowPromotionValidationContextSource(
    private val catalogSource: WorkflowPromotionToolCatalogSource,
    private val authorityResolver: LearnedWorkflowAuthorityResolver,
    private val fakeAdapters: LearnedWorkflowFakeAdapterRegistry,
    private val validator: WorkflowCandidateValidator = WorkflowCandidateValidator(),
) : WorkflowPromotionValidationContextSource {
    override suspend fun validateCurrent(
        candidate: LearnedWorkflowCandidate,
        exactGrant: PolicyGrantAuthoritySnapshot,
    ): Boolean {
        val catalog = catalogSource.current(candidate) ?: return false
        // Validator accepts only PROPOSED/VALIDATING because it normally governs validation.
        // Rechecking promotion uses the same immutable artifact under a validation-only view.
        val validationView = candidate.copy(state = LearnedWorkflowCandidateState.VALIDATING)
        return validator.validate(
            validationView,
            LearnedWorkflowValidationContext(
                requestAssistantId = candidate.assistantId,
                requestAuthoritySubjectId = candidate.authoritySubjectId,
                exactGrant = exactGrant,
                authorityResolver = authorityResolver,
                fakeAdapters = fakeAdapters,
                catalog = catalog,
            ),
        ).accepted
    }
}

/** Production current-state source. Every call rebuilds the assistant's live local tool surface. */
class ProductionWorkflowPromotionValidationContextSource(
    private val settingsStore: SettingsStore,
    private val localTools: LocalTools,
    private val grantAuthority: PolicyGrantAuthoritySource,
    private val sourceAuthority: LearnedWorkflowSourceAuthorityPort,
    private val validator: WorkflowCandidateValidator = WorkflowCandidateValidator(),
) : WorkflowPromotionValidationContextSource {
    override suspend fun validateCurrent(
        candidate: LearnedWorkflowCandidate,
        exactGrant: PolicyGrantAuthoritySnapshot,
    ): Boolean {
        if (!sourceAuthority.isCurrentFailClosed(candidate)) return false
        if (!grantAuthority.revalidateExact(exactGrant)) return false
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return false
        val assistant = settings.assistants.singleOrNull {
            it.id.toString() == candidate.assistantId
        } ?: return false
        val authorityExact = candidate.authoritySubjectId?.let { subjectId ->
            SecondUserAuthorityRegistry.current()?.let { active ->
                active.subjectId == subjectId && active.assistantId == assistant.id
            } == true
        } ?: true
        if (!authorityExact) return false
        val definitions = localTools.getTools(
            assistant.localTools,
            ToolInvocationContext(
                callerAssistantId = candidate.assistantId,
                callerWorkspaceId = assistant.workspaceId?.toString(),
                callOrigin = ToolCallOrigin.TrustedWorkflow,
                isHeadless = true,
            ),
        )
        val catalog = ToolCatalogSnapshot.fromDefinitions(definitions)
        val verifiedSchemas = candidate.toolSchemaFingerprints.associate {
            it.toolName to it.schemaFingerprint
        }
        val report = candidate.verificationReport ?: return false
        if (report.status != LearnedWorkflowVerificationStatus.PASSED ||
            report.verifierVersion != candidate.verifierVersion ||
            candidate.verifiedAtMs == null
        ) return false
        return validator.validate(
            candidate.copy(state = LearnedWorkflowCandidateState.VALIDATING),
            LearnedWorkflowValidationContext(
                requestAssistantId = candidate.assistantId,
                requestAuthoritySubjectId = candidate.authoritySubjectId,
                exactGrant = exactGrant,
                authorityResolver = LearnedWorkflowAuthorityResolver { assistantId, subjectId ->
                    assistantId == candidate.assistantId &&
                        subjectId == candidate.authoritySubjectId && authorityExact
                },
                // The immutable PASSED receipt proves fake/replay coverage for this exact
                // artifact; the live catalog below independently rechecks schema and InputSchema.
                fakeAdapters = LearnedWorkflowFakeAdapterRegistry { toolName, fingerprint ->
                    verifiedSchemas[toolName] == fingerprint
                },
                catalog = catalog,
            ),
        ).accepted
    }
}

interface WorkflowPromotionCandidateStore {
    suspend fun find(candidateId: String): LearnedWorkflowCandidate?
    suspend fun transitionExact(
        expected: LearnedWorkflowCandidate,
        nextState: LearnedWorkflowCandidateState,
        nowMs: Long,
    ): Boolean
}

/** Runtime facade boundary: DAO handles never escape the LearningDatabase session. */
interface WorkflowPromotionCandidateRuntime {
    suspend fun find(candidateId: String): LearnedWorkflowCandidate?
    suspend fun transitionExact(
        expected: LearnedWorkflowCandidate,
        nextState: LearnedWorkflowCandidateState,
        nowMs: Long,
    ): Boolean
}

interface PromotedWorkflowStore {
    suspend fun ensureDisabled(definition: WorkflowDefinition): PromotionWorkflowWrite
    suspend fun enableExact(
        workflowId: String,
        candidateId: String,
        artifactSha256: String,
        grantDigest: String,
        expectedStateVersion: Long,
        nowMs: Long,
    ): Boolean
}

enum class PromotionWorkflowWrite { INSERTED, ALREADY_EXACT, CONFLICT }

sealed interface WorkflowPromotionResult {
    data class PromotedDisabled(val workflowId: String, val replayed: Boolean) : WorkflowPromotionResult
    data class Enabled(val workflowId: String) : WorkflowPromotionResult
    data class Rejected(val reason: Reason) : WorkflowPromotionResult

    enum class Reason {
        ROLLOUT_DISABLED,
        CANDIDATE_MISSING, CANDIDATE_NOT_VERIFIED, FENCE_MISMATCH,
        VERIFICATION_NOT_PASSED, REVALIDATION_FAILED, CANDIDATE_CAS_CONFLICT,
        WORKFLOW_COLLISION, WORKFLOW_ENABLE_CONFLICT,
    }
}

/**
 * P4-005 two-database saga. No transaction spans the databases: PROMOTING is the durable intent,
 * and deterministic INSERT-ABORT plus exact duplicate recognition makes every crash point replayable.
 */
interface LearnedWorkflowPromotionService {
    suspend fun promoteVerifiedDisabled(
        fence: WorkflowPromotionFence,
        exactGrant: PolicyGrantAuthoritySnapshot,
        nowMs: Long,
    ): WorkflowPromotionResult

    suspend fun enableAfterExplicitConfirmation(
        fence: WorkflowPromotionFence,
        exactGrant: PolicyGrantAuthoritySnapshot,
        expectedWorkflowStateVersion: Long,
        userConfirmed: Boolean,
        nowMs: Long,
    ): WorkflowPromotionResult
}

class LearnedWorkflowPromotionSaga(
    private val candidates: WorkflowPromotionCandidateStore,
    private val workflows: PromotedWorkflowStore,
    private val revalidator: WorkflowPromotionRevalidator,
    /** Re-read at each cross-DB mutation boundary; callers must provide the live gate. */
    private val rolloutFence: () -> Boolean,
) : LearnedWorkflowPromotionService {
    override suspend fun promoteVerifiedDisabled(
        fence: WorkflowPromotionFence,
        exactGrant: PolicyGrantAuthoritySnapshot,
        nowMs: Long,
    ): WorkflowPromotionResult {
        var candidate = candidates.find(fence.candidateId)
            ?: return rejected(WorkflowPromotionResult.Reason.CANDIDATE_MISSING)
        if (!candidate.matches(fence)) return rejected(WorkflowPromotionResult.Reason.FENCE_MISMATCH)
        if (!candidate.hasPassedVerification()) {
            return rejected(WorkflowPromotionResult.Reason.VERIFICATION_NOT_PASSED)
        }
        if (candidate.state !in setOf(
                LearnedWorkflowCandidateState.VERIFIED,
                LearnedWorkflowCandidateState.PROMOTING,
                LearnedWorkflowCandidateState.PROMOTED_DISABLED,
            )
        ) return rejected(WorkflowPromotionResult.Reason.CANDIDATE_NOT_VERIFIED)
        if (!candidate.matches(exactGrant) || !revalidate(candidate, exactGrant)) {
            return rejected(WorkflowPromotionResult.Reason.REVALIDATION_FAILED)
        }
        var replayed = candidate.state != LearnedWorkflowCandidateState.VERIFIED
        if (candidate.state == LearnedWorkflowCandidateState.VERIFIED) {
            if (!rolloutFence()) return rejected(WorkflowPromotionResult.Reason.ROLLOUT_DISABLED)
            if (!candidates.transitionExact(candidate, LearnedWorkflowCandidateState.PROMOTING, nowMs)) {
                candidate = candidates.find(fence.candidateId)
                    ?: return rejected(WorkflowPromotionResult.Reason.CANDIDATE_CAS_CONFLICT)
                if (!candidate.matches(fence) || candidate.state !in setOf(
                        LearnedWorkflowCandidateState.PROMOTING,
                        LearnedWorkflowCandidateState.PROMOTED_DISABLED,
                    )
                ) {
                    return rejected(WorkflowPromotionResult.Reason.CANDIDATE_CAS_CONFLICT)
                }
                replayed = true
            } else {
                candidate = checkNotNull(candidates.find(fence.candidateId))
            }
        }
        val definition = candidate.toDisabledDefinition()
            ?: return rejected(WorkflowPromotionResult.Reason.FENCE_MISMATCH)
        if (!rolloutFence()) return rejected(WorkflowPromotionResult.Reason.ROLLOUT_DISABLED)
        when (workflows.ensureDisabled(definition)) {
            PromotionWorkflowWrite.CONFLICT ->
                return rejected(WorkflowPromotionResult.Reason.WORKFLOW_COLLISION)
            PromotionWorkflowWrite.ALREADY_EXACT -> replayed = true
            PromotionWorkflowWrite.INSERTED -> Unit
        }
        if (candidate.state == LearnedWorkflowCandidateState.PROMOTING) {
            if (!candidates.transitionExact(
                    candidate,
                    LearnedWorkflowCandidateState.PROMOTED_DISABLED,
                    nowMs,
                )
            ) {
                val current = candidates.find(fence.candidateId)
                if (current?.matches(fence) != true ||
                    current.state != LearnedWorkflowCandidateState.PROMOTED_DISABLED
                ) return rejected(WorkflowPromotionResult.Reason.CANDIDATE_CAS_CONFLICT)
                replayed = true
            }
        }
        return WorkflowPromotionResult.PromotedDisabled(definition.id, replayed)
    }

    /** A separate user action; promotion itself never creates an active workflow. */
    override suspend fun enableAfterExplicitConfirmation(
        fence: WorkflowPromotionFence,
        exactGrant: PolicyGrantAuthoritySnapshot,
        expectedWorkflowStateVersion: Long,
        userConfirmed: Boolean,
        nowMs: Long,
    ): WorkflowPromotionResult {
        if (!userConfirmed) return rejected(WorkflowPromotionResult.Reason.REVALIDATION_FAILED)
        val candidate = candidates.find(fence.candidateId)
            ?: return rejected(WorkflowPromotionResult.Reason.CANDIDATE_MISSING)
        if (!candidate.matches(fence) ||
            candidate.state != LearnedWorkflowCandidateState.PROMOTED_DISABLED ||
            !candidate.hasPassedVerification()
        ) return rejected(WorkflowPromotionResult.Reason.FENCE_MISMATCH)
        if (!candidate.matches(exactGrant) || !revalidate(candidate, exactGrant)) {
            return rejected(WorkflowPromotionResult.Reason.REVALIDATION_FAILED)
        }
        if (!rolloutFence()) return rejected(WorkflowPromotionResult.Reason.ROLLOUT_DISABLED)
        val workflowId = workflowId(candidate.id)
        return if (workflows.enableExact(
                workflowId, candidate.id, candidate.artifactSha256,
                candidate.sourceGrantDigest, expectedWorkflowStateVersion, nowMs,
            )
        ) WorkflowPromotionResult.Enabled(workflowId)
        else rejected(WorkflowPromotionResult.Reason.WORKFLOW_ENABLE_CONFLICT)
    }

    private suspend fun revalidate(
        candidate: LearnedWorkflowCandidate,
        exactGrant: PolicyGrantAuthoritySnapshot,
    ): Boolean = try {
        revalidator.validate(candidate, exactGrant)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }
}

class RoomWorkflowPromotionCandidateStore(
    private val dao: LearnedWorkflowCandidateDao,
) : WorkflowPromotionCandidateStore {
    override suspend fun find(candidateId: String): LearnedWorkflowCandidate? =
        dao.find(candidateId)?.toDomainOrNull()

    override suspend fun transitionExact(
        expected: LearnedWorkflowCandidate,
        nextState: LearnedWorkflowCandidateState,
        nowMs: Long,
    ): Boolean {
        val reason = when (nextState) {
            LearnedWorkflowCandidateState.PROMOTING ->
                LearnedWorkflowCandidateRevisionReason.PROMOTION_STARTED
            LearnedWorkflowCandidateState.PROMOTED_DISABLED ->
                LearnedWorkflowCandidateRevisionReason.PROMOTED_DISABLED
            else -> error("Unsupported promotion transition")
        }
        val next = expected.copy(
            state = nextState,
            stateVersion = expected.stateVersion + 1L,
            updatedAtMs = nowMs.coerceAtLeast(expected.updatedAtMs),
        )
        return dao.transitionFenced(
            expected.toEntity(), next.toEntity(), reason,
            LearnedWorkflowCandidateRevisionActor.PROMOTION_SERVICE,
        )
    }
}

class RuntimeWorkflowPromotionCandidateStore(
    private val runtime: WorkflowPromotionCandidateRuntime,
) : WorkflowPromotionCandidateStore {
    override suspend fun find(candidateId: String): LearnedWorkflowCandidate? =
        runtime.find(candidateId)

    override suspend fun transitionExact(
        expected: LearnedWorkflowCandidate,
        nextState: LearnedWorkflowCandidateState,
        nowMs: Long,
    ): Boolean = runtime.transitionExact(expected, nextState, nowMs)
}

class RepositoryPromotedWorkflowStore(
    private val repository: WorkflowRepository,
) : PromotedWorkflowStore {
    override suspend fun ensureDisabled(definition: WorkflowDefinition): PromotionWorkflowWrite =
        when (repository.ensureExactLearnedPromotionDisabled(definition)) {
            WorkflowRepository.LearnedPromotionWrite.INSERTED -> PromotionWorkflowWrite.INSERTED
            WorkflowRepository.LearnedPromotionWrite.ALREADY_EXACT -> PromotionWorkflowWrite.ALREADY_EXACT
            WorkflowRepository.LearnedPromotionWrite.CONFLICT -> PromotionWorkflowWrite.CONFLICT
        }

    override suspend fun enableExact(
        workflowId: String, candidateId: String, artifactSha256: String, grantDigest: String,
        expectedStateVersion: Long, nowMs: Long,
    ): Boolean = repository.enableExactLearnedPromotion(
        workflowId, candidateId, artifactSha256, grantDigest, expectedStateVersion, nowMs,
    )
}

private fun LearnedWorkflowCandidate.toDisabledDefinition(): WorkflowDefinition? {
    val stored = WorkflowJson.parseStoredWithCompatibility(canonicalTemplateJson) ?: return null
    return stored.definition.copy(
        // Template timestamps are part of the frozen artifact. Never replace them with retry time:
        // otherwise a crash after AppDB insert could not recognize the same deterministic row.
        id = workflowId(id), enabled = false,
        origin = WorkflowOrigin.LEARNED, sourceCandidateId = id,
        sourceArtifactHash = artifactSha256, grantDigest = sourceGrantDigest,
        authoritySubjectId = authoritySubjectId,
    )
}

private fun LearnedWorkflowCandidate.matches(fence: WorkflowPromotionFence): Boolean =
    id == fence.candidateId && candidateVersion == fence.candidateVersion &&
        artifactSha256 == fence.artifactSha256 && sourceGrantDigest == fence.sourceGrantDigest &&
        WorkflowArtifactCanonicalizer.canonicalToolSchemas(toolSchemaFingerprints) ==
            fence.toolSchemaFingerprintsWire && verifierVersion == fence.verifierVersion &&
        assistantId == fence.assistantId && authoritySubjectId == fence.authoritySubjectId

private fun LearnedWorkflowCandidate.hasPassedVerification(): Boolean =
    verificationReport?.status == LearnedWorkflowVerificationStatus.PASSED &&
        verificationReport.verifierVersion == verifierVersion && verifiedAtMs != null

private fun LearnedWorkflowCandidate.matches(grant: PolicyGrantAuthoritySnapshot): Boolean =
    grant.state == PolicyGrantAuthorityState.GRANTED && grant.scope == policyScope &&
        grant.consumingAssistantId.toString() == assistantId && grant.policyId == sourcePolicyId &&
        grant.contentRevision == sourcePolicyRevision &&
        grant.artifactSha256 == sourcePolicyArtifactSha256 &&
        WorkflowArtifactCanonicalizer.grantDigest(
            grant.grantId, grant.sourceStreamId, grant.stateVersion,
            grant.contentRevision, grant.artifactSha256,
        ) == sourceGrantDigest

private fun workflowId(candidateId: String): String = "learned:$candidateId"
private fun rejected(reason: WorkflowPromotionResult.Reason) = WorkflowPromotionResult.Rejected(reason)
