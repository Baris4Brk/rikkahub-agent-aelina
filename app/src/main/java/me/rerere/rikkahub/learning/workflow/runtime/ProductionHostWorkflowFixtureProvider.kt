package me.rerere.rikkahub.learning.workflow.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.RiskLevel
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolAdapter
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolCase
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolOrigin
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolOutcome
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolRegistration
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolRegistry
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolRisk
import me.rerere.rikkahub.learning.verification.WorkflowReplayExpectedAction
import me.rerere.rikkahub.learning.verification.WorkflowReplayExpectedResult
import me.rerere.rikkahub.learning.verification.WorkflowReplayFixture
import me.rerere.rikkahub.learning.verification.WorkflowReplayResultKind
import me.rerere.rikkahub.learning.verification.WorkflowReplayTerminal
import me.rerere.rikkahub.learning.verification.WORKFLOW_CANDIDATE_VERIFIER_VERSION
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowFakeAdapterRegistry
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

/**
 * A deliberately tiny production host profile. It can prove one exact, read-only time lookup and
 * nothing else. Extending the set requires a new enum profile plus a separately reviewed oracle.
 */
object ProductionHostWorkflowFixtureProvider : HostWorkflowFixtureProvider {
    override fun resolve(
        profile: HostWorkflowFixtureProfile,
        candidate: LearnedWorkflowCandidate,
        catalog: ToolCatalogSnapshot,
    ): HostWorkflowFixtureBundle? = when (profile) {
        HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1 -> safeTimeInfo(candidate, catalog)
    }

    private fun safeTimeInfo(
        candidate: LearnedWorkflowCandidate,
        catalog: ToolCatalogSnapshot,
    ): HostWorkflowFixtureBundle? {
        if (candidate.verifierVersion != WORKFLOW_CANDIDATE_VERIFIER_VERSION ||
            candidate.typedSlots.isNotEmpty() || candidate.toolSchemaFingerprints.size != 1
        ) return null
        val template = runCatching {
            Json.parseToJsonElement(candidate.canonicalTemplateJson) as? JsonObject
        }.getOrNull() ?: return null
        val actions = template["actions"] as? kotlinx.serialization.json.JsonArray ?: return null
        val action = actions.singleOrNull() as? JsonObject ?: return null
        if (action.keys != ACTION_KEYS ||
            (action["tool"] as? JsonPrimitive)?.contentOrNull != SAFE_TOOL ||
            (action["args"] as? JsonObject)?.isNotEmpty() != false ||
            (action["timeout_seconds"] as? JsonPrimitive)?.intOrNull !in 1..120
        ) return null
        val frozen = candidate.toolSchemaFingerprints.single()
        val entry = catalog.entry(SAFE_TOOL) ?: return null
        if (frozen.actionIndex != 0 || frozen.toolName != SAFE_TOOL ||
            frozen.schemaFingerprint != entry.schemaFingerprint ||
            (action["tool_schema_fingerprint"] as? JsonPrimitive)?.contentOrNull !=
            entry.schemaFingerprint || entry.externalUntrusted || !entry.currentlyInjectable ||
            entry.risk !in setOf(RiskLevel.Low, RiskLevel.Medium) ||
            ToolCallOrigin.TrustedWorkflow !in entry.allowedOrigins
        ) return null

        val args = JsonObject(emptyMap())
        val output = buildJsonObject {
            put("date", JsonPrimitive("2000-01-01"))
            put("time", JsonPrimitive("00:00:00"))
            put("timezone", JsonPrimitive("Etc/UTC"))
            put("timestamp_ms", JsonPrimitive(946_684_800_000L))
        }
        val adapter = FakeWorkflowToolAdapter(
            adapterVersion = HOST_VERSION,
            cases = listOf(
                FakeWorkflowToolCase(
                    actionIndex = 0,
                    expectedArgs = args,
                    outcome = FakeWorkflowToolOutcome.Success(output),
                ),
            ),
        )
        val registration = FakeWorkflowToolRegistration(
            toolName = SAFE_TOOL,
            schemaFingerprint = entry.schemaFingerprint,
            catalogued = true,
            allowedOrigins = setOf(FakeWorkflowToolOrigin.TRUSTED_WORKFLOW),
            risk = when (entry.risk) {
                RiskLevel.Low -> FakeWorkflowToolRisk.LOW
                RiskLevel.Medium -> FakeWorkflowToolRisk.MEDIUM
                else -> return null
            },
            adapter = adapter,
        )
        val registry = FakeWorkflowToolRegistry.of(registration)
        val fixture = WorkflowReplayFixture(
            fixtureId = FIXTURE_ID,
            subjectArtifactSha256 = candidate.artifactSha256,
            inputRevision = HOST_INPUT_REVISION,
            expectedActions = listOf(
                WorkflowReplayExpectedAction(
                    actionIndex = 0,
                    toolName = SAFE_TOOL,
                    schemaFingerprint = entry.schemaFingerprint,
                    resolvedArgs = args,
                    expectedResult = WorkflowReplayExpectedResult(
                        kind = WorkflowReplayResultKind.SUCCESS,
                        output = output,
                    ),
                ),
            ),
            expectedTerminal = WorkflowReplayTerminal.COMPLETED,
        )
        return HostWorkflowFixtureBundle(
            profile = HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1,
            hostVersion = HOST_VERSION,
            fixtures = listOf(fixture),
            fakeTools = registry,
            validatorAdapters = LearnedWorkflowFakeAdapterRegistry { toolName, fingerprint ->
                toolName == SAFE_TOOL && fingerprint == entry.schemaFingerprint &&
                    registry.hasExplicitAdapter(toolName, fingerprint)
            },
        )
    }

    private const val SAFE_TOOL = "get_time_info"
    private const val HOST_VERSION = "host-safe-time-info-v1"
    private const val HOST_INPUT_REVISION = "host-safe-time-info-input-v1"
    private const val FIXTURE_ID = "host-safe-time-info-success-v1"
    private val ACTION_KEYS = setOf(
        "tool", "args", "timeout_seconds", "tool_schema_fingerprint",
    )
}
