package me.rerere.rikkahub.learning.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.RiskLevel
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidencePolarity
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.isCanonicalWorkflowSha256
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import kotlin.uuid.Uuid

sealed interface LearnedWorkflowCompileResult {
    data class Compiled(val candidate: LearnedWorkflowCandidate) : LearnedWorkflowCompileResult
    data class Rejected(val reason: LearnedWorkflowCompileRejection) : LearnedWorkflowCompileResult
}

enum class LearnedWorkflowCompileRejection {
    POLICY_NOT_EXACT_REVIEWED,
    POSITIVE_ANCHOR_MISSING,
    REQUIRED_POLICY_FIELD_MISSING,
    ACTION_COUNT_OUT_OF_BOUNDS,
    TOOL_NOT_CATALOGUED,
    TOOL_SCHEMA_UNAVAILABLE,
    FORBIDDEN_TOOL,
    SLOT_UNBOUND,
    TEMPLATE_TOO_LARGE,
    INVALID_PROPOSAL,
}

/** P4-002: a pure compiler from one exact reviewed Policy to a disabled manual template. */
object LearnedWorkflowCompiler {
    fun compile(
        proposal: LearnedPolicyProposal,
        catalog: ToolCatalogSnapshot,
    ): LearnedWorkflowCompileResult = try {
        if (proposal.exactGrant.policyId != proposal.policyId ||
            proposal.exactGrant.contentRevision != proposal.policyRevision ||
            proposal.exactGrant.artifactSha256 != proposal.policyArtifactSha256 ||
            proposal.exactGrant.consumingAssistantId.toString() != proposal.consumingAssistantId
        ) {
            return rejected(LearnedWorkflowCompileRejection.POLICY_NOT_EXACT_REVIEWED)
        }
        if (proposal.verification.isBlank() || proposal.boundary.isBlank() ||
            proposal.trigger.isBlank() || proposal.procedure.isBlank()
        ) {
            return rejected(LearnedWorkflowCompileRejection.REQUIRED_POLICY_FIELD_MISSING)
        }
        val positiveAnchors = proposal.evidence.filter {
            it.polarity == LearnedPolicyWorkflowEvidencePolarity.POSITIVE
        }
        if (positiveAnchors.isEmpty()) {
            return rejected(LearnedWorkflowCompileRejection.POSITIVE_ANCHOR_MISSING)
        }
        if (proposal.actions.size !in 1..LearnedWorkflowCandidate.MAX_ACTIONS) {
            return rejected(LearnedWorkflowCompileRejection.ACTION_COUNT_OUT_OF_BOUNDS)
        }
        if (proposal.typedSlots.any { !it.isBound }) {
            return rejected(LearnedWorkflowCompileRejection.SLOT_UNBOUND)
        }

        val actionSchemas = mutableListOf<LearnedWorkflowToolSchemaFingerprint>()
        val capabilities = sortedSetOf<String>()
        proposal.actions.forEachIndexed { index, action ->
            if (action.toolName.isForbiddenLearnedWorkflowTool()) {
                return rejected(LearnedWorkflowCompileRejection.FORBIDDEN_TOOL)
            }
            val entry = catalog.entry(action.toolName)
                ?: return rejected(LearnedWorkflowCompileRejection.TOOL_NOT_CATALOGUED)
            if (entry.externalUntrusted || !entry.currentlyInjectable) {
                return rejected(LearnedWorkflowCompileRejection.FORBIDDEN_TOOL)
            }
            val descriptor = CapabilityCatalog.byToolName(entry.toolName)
                ?: return rejected(LearnedWorkflowCompileRejection.TOOL_NOT_CATALOGUED)
            if (entry.risk !in setOf(RiskLevel.Low, RiskLevel.Medium) ||
                descriptor.riskLevel !in setOf(RiskLevel.Low, RiskLevel.Medium) ||
                ToolCallOrigin.TrustedWorkflow !in entry.allowedOrigins ||
                ToolCallOrigin.TrustedWorkflow !in descriptor.allowedOrigins
            ) {
                return rejected(LearnedWorkflowCompileRejection.FORBIDDEN_TOOL)
            }
            if (!entry.schemaFingerprint.isCanonicalWorkflowSha256()) {
                return rejected(LearnedWorkflowCompileRejection.TOOL_SCHEMA_UNAVAILABLE)
            }
            actionSchemas += LearnedWorkflowToolSchemaFingerprint(
                actionIndex = index,
                toolName = action.toolName,
                schemaFingerprint = entry.schemaFingerprint,
            )
            ToolCapabilityResolver.resolve(action.toolName, action.args).capabilities
                .mapTo(capabilities) { it.value }
        }
        if (capabilities.isEmpty()) {
            return rejected(LearnedWorkflowCompileRejection.TOOL_NOT_CATALOGUED)
        }
        val candidateId = WorkflowArtifactCanonicalizer.candidateId(
            proposal.policyId,
            proposal.policyRevision,
            proposal.consumingAssistantId,
        )
        val template = buildJsonObject {
            put("id", JsonPrimitive(candidateId))
            put("name", JsonPrimitive(proposal.name.trim()))
            proposal.description?.trim()?.takeIf(String::isNotBlank)?.let {
                put("description", JsonPrimitive(it))
            }
            put("enabled", JsonPrimitive(false))
            put("trigger", buildJsonObject { put("type", JsonPrimitive("manual")) })
            put("conditions", JsonArray(emptyList()))
            put("actions", buildJsonArray {
                proposal.actions.forEachIndexed { index, action ->
                    add(buildJsonObject {
                        put("tool", JsonPrimitive(action.toolName))
                        put("args", action.args)
                        put("timeout_seconds", JsonPrimitive(action.timeoutSeconds))
                        put(
                            "tool_schema_fingerprint",
                            JsonPrimitive(actionSchemas[index].schemaFingerprint),
                        )
                    })
                }
            })
            put("cooldown_seconds", JsonPrimitive(0))
            put("max_runs_per_day", JsonPrimitive(1))
            put("created_at_ms", JsonPrimitive(proposal.frozenNowMs))
            put("updated_at_ms", JsonPrimitive(proposal.frozenNowMs))
            put("authoring_assistant_id", JsonPrimitive(proposal.consumingAssistantId))
            put("capability_snapshot", JsonArray(capabilities.map { JsonPrimitive(it) }))
            put("origin", JsonPrimitive("LEARNED"))
            put("source_candidate_id", JsonPrimitive(candidateId))
            put(
                "authority_subject_id",
                (proposal.exactGrant.scope as?
                    me.rerere.rikkahub.learning.model.LearningScope.AuthoritySubject)
                    ?.authoritySubjectId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull,
            )
        }
        val templateJson = WorkflowArtifactCanonicalizer.canonicalTemplate(template)
        if (templateJson.toByteArray(Charsets.UTF_8).size > LearnedWorkflowCandidate.MAX_TEMPLATE_BYTES) {
            return rejected(LearnedWorkflowCompileRejection.TEMPLATE_TOO_LARGE)
        }
        val slotsWire = WorkflowArtifactCanonicalizer.canonicalSlots(proposal.typedSlots)
        val capabilityWire = WorkflowArtifactCanonicalizer.canonicalCapabilities(capabilities)
        val schemasWire = WorkflowArtifactCanonicalizer.canonicalToolSchemas(actionSchemas)
        val grantDigest = WorkflowArtifactCanonicalizer.grantDigest(
            proposal.exactGrant.grantId,
            proposal.exactGrant.sourceStreamId,
            proposal.exactGrant.stateVersion,
            proposal.exactGrant.contentRevision,
            proposal.exactGrant.artifactSha256,
        )
        val artifactSha256 = WorkflowArtifactCanonicalizer.artifactSha256(
            canonicalTemplateJson = templateJson,
            canonicalTypedSlots = slotsWire,
            canonicalCapabilities = capabilityWire,
            canonicalToolSchemas = schemasWire,
            assistantId = proposal.consumingAssistantId,
            authoritySubjectId = proposal.exactGrant.scope.let { scope ->
                (scope as? me.rerere.rikkahub.learning.model.LearningScope.AuthoritySubject)
                    ?.authoritySubjectId
            },
            sourcePolicyId = proposal.policyId,
            sourcePolicyRevision = proposal.policyRevision,
            sourcePolicyArtifactSha256 = proposal.policyArtifactSha256,
            sourceGrantDigest = grantDigest,
            compilerVersion = proposal.compilerVersion,
            templateVersion = proposal.templateVersion,
        )
        val assistantId = Uuid.parse(proposal.consumingAssistantId).toString()
        LearnedWorkflowCompileResult.Compiled(
            LearnedWorkflowCandidate(
                id = candidateId,
                candidateVersion = 1L,
                stateVersion = 1L,
                state = LearnedWorkflowCandidateState.PROPOSED,
                assistantId = assistantId,
                authoritySubjectId = (proposal.exactGrant.scope as?
                    me.rerere.rikkahub.learning.model.LearningScope.AuthoritySubject)
                    ?.authoritySubjectId,
                sourcePolicyId = proposal.policyId,
                sourcePolicyRevision = proposal.policyRevision,
                sourcePolicyArtifactSha256 = proposal.policyArtifactSha256,
                sourceGrantDigest = grantDigest,
                positiveAnchorEvidenceId = positiveAnchors.minBy { it.evidenceId }.evidenceId,
                evidenceIds = proposal.evidence.map { it.evidenceId }.distinct().sorted(),
                canonicalTemplateJson = templateJson,
                typedSlots = proposal.typedSlots.sortedBy { it.name },
                capabilitySnapshot = capabilities.toList(),
                toolSchemaFingerprints = actionSchemas,
                producerProviderIdentity = proposal.producerProviderIdentity,
                producerModelIdentity = proposal.producerModelIdentity,
                producerConfigurationIdentity = proposal.producerConfigurationIdentity,
                producerConfigGeneration = proposal.producerConfigGeneration,
                compilerVersion = proposal.compilerVersion,
                promptVersion = proposal.promptVersion,
                templateVersion = proposal.templateVersion,
                validatorVersion = proposal.validatorVersion,
                verifierVersion = proposal.verifierVersion,
                maxOutputUtf8Bytes = proposal.maxOutputUtf8Bytes,
                artifactSha256 = artifactSha256,
                verificationReport = null,
                verifiedAtMs = null,
                archivedAtMs = null,
                createdAtMs = proposal.frozenNowMs,
                updatedAtMs = proposal.frozenNowMs,
            ),
        )
    } catch (_: IllegalArgumentException) {
        rejected(LearnedWorkflowCompileRejection.INVALID_PROPOSAL)
    }
}

internal fun String.isForbiddenLearnedWorkflowTool(): Boolean {
    val normalized = trim().lowercase()
    if (normalized in EXACT_FORBIDDEN_TOOLS) return true
    return FORBIDDEN_TOOL_PREFIXES.any(normalized::startsWith) ||
        FORBIDDEN_TOOL_FRAGMENTS.any(normalized::contains)
}

private fun rejected(reason: LearnedWorkflowCompileRejection) =
    LearnedWorkflowCompileResult.Rejected(reason)

private val EXACT_FORBIDDEN_TOOLS = setOf(
    "run_js",
    "eval_javascript",
    "workflow_run",
    "workflow_create",
    "workflow_update",
    "workflow_delete",
    "workflow_set_enabled",
    "schedule_job",
    "trigger_job_now",
    "install_apk",
    "send_sms",
    "call_phone",
    "post_notification",
    "share",
    "open_url",
    "download_file",
    "delete_file",
    "batch_delete",
    "calendar_create",
    "calendar_delete",
    "calendar_update",
    "send_email_intent",
    "send_sms_intent",
    "notification_reply",
    "telegram_send_message",
    "telegram_send_photo",
    "telegram_send_document",
)
private val FORBIDDEN_TOOL_PREFIXES = setOf(
    "mcp__",
    "plugin__",
    "plugin_",
    "skill_",
    "ssh_",
    "termux_",
    "linux_",
    "workspace_",
    "privileged_",
    "external_bridge_",
    "shizuku_",
    "telegram_",
    "workflow_",
    "schedule_",
    "script_",
)
private val FORBIDDEN_TOOL_FRAGMENTS = setOf(
    "install",
    "uninstall",
    "delete",
    "payment",
    "purchase",
    "communicat",
    "message",
    "email",
    "notification",
    "intent",
    "accessibility",
    "shell",
    "execute_command",
)
