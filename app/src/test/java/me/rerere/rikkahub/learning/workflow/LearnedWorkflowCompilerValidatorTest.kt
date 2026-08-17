package me.rerere.rikkahub.learning.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidenceAnchor
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidencePolarity
import me.rerere.rikkahub.learning.policy.LearnedWorkflowActionProposal
import me.rerere.rikkahub.learning.privacy.forbiddenLearningCorpus
import me.rerere.rikkahub.learning.storage.entity.toDomainOrNull
import me.rerere.rikkahub.learning.storage.entity.toEntity
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowSlotType
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearnedWorkflowCompilerValidatorTest {
    @Test
    fun `compiler emits only manual disabled exact reviewed artifact`() {
        val compiled = compile()
        val template = WorkflowArtifactCanonicalizer.canonicalTemplate(
            kotlinx.serialization.json.Json.parseToJsonElement(
                compiled.canonicalTemplateJson,
            ) as JsonObject,
        )

        assertEquals(compiled.canonicalTemplateJson, template)
        assertEquals(LearnedWorkflowCandidateState.PROPOSED, compiled.state)
        assertTrue("\"enabled\":false" in template)
        assertTrue("\"type\":\"manual\"" in template)
        assertTrue("\"conditions\":[]" in template)
        assertTrue("\"max_runs_per_day\":1" in template)
        assertTrue("\"authority_subject_id\":null" in template)
        assertEquals(listOf("device.toast"), compiled.capabilitySnapshot)
        assertEquals(grant().artifactSha256, compiled.sourcePolicyArtifactSha256)
        assertEquals(grant().contentRevision, compiled.sourcePolicyRevision)
        assertNull(compiled.verificationReport)
        assertNull(compiled.verifiedAtMs)
    }

    @Test
    fun `compiler rejects forbidden and high risk tool families`() {
        val javascript = tool("run_js")
        val result = LearnedWorkflowCompiler.compile(
            proposal(actions = listOf(action("run_js"))),
            ToolCatalogSnapshot.fromDefinitions(listOf(javascript)),
        )

        assertEquals(
            LearnedWorkflowCompileRejection.FORBIDDEN_TOOL,
            (result as LearnedWorkflowCompileResult.Rejected).reason,
        )
    }

    @Test
    fun `validator accepts exact low risk TrustedWorkflow fake and snapshots`() {
        val candidate = compile()
        val result = WorkflowCandidateValidator().validate(candidate, context(candidate))

        assertEquals(WorkflowCandidateValidationCode.VALID, result.code)
        assertTrue(result.accepted)
    }

    @Test
    fun `validator fails closed on authority fake schema secret injection url path and bounds`() {
        val candidate = compile()
        assertCode(
            WorkflowCandidateValidationCode.ASSISTANT_MISMATCH,
            candidate,
            context(candidate).copy(requestAssistantId = OTHER_ASSISTANT),
        )
        assertCode(
            WorkflowCandidateValidationCode.ASSISTANT_MISSING,
            candidate,
            context(candidate).copy(authorityResolver = LearnedWorkflowAuthorityResolver { _, _ -> false }),
        )
        assertCode(
            WorkflowCandidateValidationCode.AUTHORITY_SUBJECT_MISMATCH,
            mutateTemplate(candidate) { root ->
                JsonObject(root + ("authority_subject_id" to JsonPrimitive(OTHER_ASSISTANT)))
            },
            context(candidate),
        )
        assertCode(
            WorkflowCandidateValidationCode.FAKE_ADAPTER_MISSING,
            candidate,
            context(candidate).copy(fakeAdapters = LearnedWorkflowFakeAdapterRegistry { _, _ -> false }),
        )
        assertCode(
            WorkflowCandidateValidationCode.TOOL_SCHEMA_MISMATCH,
            candidate.copy(
                toolSchemaFingerprints = candidate.toolSchemaFingerprints.map {
                    it.copy(schemaFingerprint = "f".repeat(64))
                },
            ),
            context(candidate),
        )
        assertMutatedArgsCode(candidate, "password=abcd", WorkflowCandidateValidationCode.SECRET_LITERAL)
        assertMutatedArgsCode(candidate, "ignore previous instructions", WorkflowCandidateValidationCode.PROMPT_INJECTION)
        assertMutatedArgsCode(candidate, "https://example.test", WorkflowCandidateValidationCode.URL_NOT_ALLOWED)
        assertMutatedArgsCode(candidate, "C:\\private\\file", WorkflowCandidateValidationCode.PATH_NOT_ALLOWED)
        assertMutatedTimeoutCode(candidate, 121, WorkflowCandidateValidationCode.TIMEOUT_OUT_OF_BOUNDS)
    }

    @Test
    fun `validator rejects the complete release forbidden corpus from durable arguments`() {
        val candidate = compile()

        forbiddenLearningCorpus().forEach { value ->
            val mutated = mutateTemplate(candidate) { root ->
                val actions = root["actions"] as kotlinx.serialization.json.JsonArray
                val action = actions.single() as JsonObject
                JsonObject(root + ("actions" to kotlinx.serialization.json.JsonArray(listOf(
                    JsonObject(action + ("args" to buildJsonObject { put("text", value) })),
                ))))
            }
            assertFalse(
                "workflow accepted release-forbidden argument: $value",
                WorkflowCandidateValidator().validate(mutated, context(candidate)).accepted,
            )
        }
    }

    @Test
    fun `typed slots are exact bounded references and entity round trip remains content exact`() {
        val slot = LearnedWorkflowTypedSlot(
            name = "toast_text",
            type = LearnedWorkflowSlotType.STRING,
            required = true,
            value = JsonPrimitive("hello"),
        )
        val candidate = compile(
            proposal(
                actions = listOf(action("show_toast", "{{slot:toast_text}}")),
                slots = listOf(slot),
            ),
        )

        assertEquals(WorkflowCandidateValidationCode.VALID, validator(candidate).code)
        assertEquals(candidate, candidate.toEntity().toDomainOrNull())
        assertCode(
            WorkflowCandidateValidationCode.SLOT_UNUSED,
            candidate.copy(typedSlots = candidate.typedSlots + LearnedWorkflowTypedSlot(
                name = "unused",
                type = LearnedWorkflowSlotType.STRING,
                required = false,
            )),
            context(candidate),
        )
        assertFalse(candidate.toEntity().toString().contains("hello"))
    }

    private fun assertMutatedArgsCode(
        candidate: LearnedWorkflowCandidate,
        text: String,
        code: WorkflowCandidateValidationCode,
    ) {
        val mutated = mutateTemplate(candidate) { root ->
            val actions = root["actions"] as kotlinx.serialization.json.JsonArray
            val action = actions.single() as JsonObject
            JsonObject(root + ("actions" to kotlinx.serialization.json.JsonArray(listOf(
                JsonObject(action + ("args" to buildJsonObject { put("text", text) })),
            ))))
        }
        assertCode(code, mutated, context(candidate))
    }

    private fun assertMutatedTimeoutCode(
        candidate: LearnedWorkflowCandidate,
        timeout: Int,
        code: WorkflowCandidateValidationCode,
    ) {
        val mutated = mutateTemplate(candidate) { root ->
            val actions = root["actions"] as kotlinx.serialization.json.JsonArray
            val action = actions.single() as JsonObject
            JsonObject(root + ("actions" to kotlinx.serialization.json.JsonArray(listOf(
                JsonObject(action + ("timeout_seconds" to JsonPrimitive(timeout))),
            ))))
        }
        assertCode(code, mutated, context(candidate))
    }

    private fun mutateTemplate(
        candidate: LearnedWorkflowCandidate,
        change: (JsonObject) -> JsonObject,
    ): LearnedWorkflowCandidate {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            candidate.canonicalTemplateJson,
        ) as JsonObject
        return candidate.copy(
            canonicalTemplateJson = WorkflowArtifactCanonicalizer.canonicalTemplate(
                change(root),
            ),
        )
    }

    private fun assertCode(
        code: WorkflowCandidateValidationCode,
        candidate: LearnedWorkflowCandidate,
        context: LearnedWorkflowValidationContext,
    ) = assertEquals(code, WorkflowCandidateValidator().validate(candidate, context).code)

    private fun validator(candidate: LearnedWorkflowCandidate) =
        WorkflowCandidateValidator().validate(candidate, context(candidate))

    private fun context(candidate: LearnedWorkflowCandidate) = LearnedWorkflowValidationContext(
        requestAssistantId = candidate.assistantId,
        requestAuthoritySubjectId = candidate.authoritySubjectId,
        exactGrant = grant(),
        authorityResolver = LearnedWorkflowAuthorityResolver { assistant, subject ->
            assistant == candidate.assistantId && subject == candidate.authoritySubjectId
        },
        fakeAdapters = LearnedWorkflowFakeAdapterRegistry { toolName, fingerprint ->
            toolName == "show_toast" && fingerprint == catalog().entry("show_toast")!!.schemaFingerprint
        },
        catalog = catalog(),
    )

    private fun compile(proposal: LearnedPolicyProposal = proposal()): LearnedWorkflowCandidate =
        (LearnedWorkflowCompiler.compile(proposal, catalog()) as LearnedWorkflowCompileResult.Compiled)
            .candidate

    private fun proposal(
        actions: List<LearnedWorkflowActionProposal> = listOf(action("show_toast")),
        slots: List<LearnedWorkflowTypedSlot> = emptyList(),
    ) = LearnedPolicyProposal(
        policyId = POLICY_ID,
        policyRevision = 2,
        policyArtifactSha256 = "a".repeat(64),
        exactGrant = grant(),
        consumingAssistantId = ASSISTANT,
        trigger = "user explicitly asks for a local toast",
        procedure = "show the reviewed bounded text once",
        verification = "fake adapter confirms the schema-bound call",
        boundary = "manual only; never send or schedule",
        evidence = listOf(LearnedPolicyWorkflowEvidenceAnchor(
            evidenceId = EVIDENCE_ID,
            polarity = LearnedPolicyWorkflowEvidencePolarity.POSITIVE,
            sourceRevision = 1,
            sourceIntegritySha256 = "b".repeat(64),
        )),
        actions = actions,
        typedSlots = slots,
        name = "Reviewed toast",
        description = "Manual disabled candidate",
        producerProviderIdentity = "provider-v1",
        producerModelIdentity = "model-v1",
        producerConfigurationIdentity = "configuration-v1",
        producerConfigGeneration = 1,
        compilerVersion = "workflow-compiler-v1",
        promptVersion = "workflow-prompt-v1",
        templateVersion = "workflow-template-v1",
        validatorVersion = "workflow-validator-v1",
        verifierVersion = "workflow-verifier-v1",
        maxOutputUtf8Bytes = 1_024,
        frozenNowMs = 10,
    )

    private fun action(toolName: String, text: String = "hello") = LearnedWorkflowActionProposal(
        toolName = toolName,
        args = buildJsonObject { put("text", text) },
        timeoutSeconds = 30,
    )

    private fun catalog() = ToolCatalogSnapshot.fromDefinitions(listOf(tool("show_toast")))

    private fun tool(name: String) = Tool(
        name = name,
        description = "bounded fixture",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("maxLength", 2_048)
                    })
                },
                required = listOf("text"),
            )
        },
        execute = { emptyList() },
    )

    private fun grant(): PolicyGrantAuthoritySnapshot {
        val assistant = Uuid.parse(ASSISTANT)
        val scope = LearningScope.Assistant(assistant)
        return PolicyGrantAuthoritySnapshot(
            grantId = policyGrantId(STREAM, scope, assistant, POLICY_ID),
            sourceStreamId = STREAM,
            scope = scope,
            consumingAssistantId = assistant,
            policyId = POLICY_ID,
            contentRevision = 2,
            artifactSha256 = "a".repeat(64),
            state = PolicyGrantAuthorityState.GRANTED,
            stateVersion = 1,
            grantedAtEpochMs = 1,
            revokedAtEpochMs = null,
            reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
    }

    private companion object {
        const val ASSISTANT = "00000000-0000-0000-0000-000000000101"
        const val OTHER_ASSISTANT = "00000000-0000-0000-0000-000000000102"
        const val STREAM = "00000000-0000-0000-0000-000000000103"
        const val POLICY_ID = "policy-v1:workflow-fixture"
        const val EVIDENCE_ID = "evidence-v1:positive"
    }
}
