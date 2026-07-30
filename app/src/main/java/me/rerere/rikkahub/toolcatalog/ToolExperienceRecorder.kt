package me.rerere.rikkahub.toolcatalog

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorSource
import me.rerere.rikkahub.data.ai.execution.ToolTrackingState
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.execution.ExecutionRecordIds
import me.rerere.rikkahub.data.execution.ExecutionRepository
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.VerificationState

/**
 * Converts an in-memory result into a strictly redacted learning signal. It deliberately never
 * returns or stores tool arguments or output. The execution ledger remains the proof source.
 */
class ToolExperienceRecorder(
    private val executionRepository: ExecutionRepository,
    private val experiences: ToolExperienceRepository,
    private val shortcuts: ToolShortcutRepository,
) {
    suspend fun recordIfEligible(
        definition: Tool,
        result: ToolExecutionPlanResult,
        context: ToolExecutionContext?,
    ) {
        val completed = result as? ToolExecutionPlanResult.Completed ?: return
        if (completed.trackingState != ToolTrackingState.TRACKED) return
        val executionContext = context ?: return
        val subject = executionContext.capabilitySubject ?: return
        if (subject.type != SubjectType.LOCAL_SECOND_USER) return
        val snapshot = ToolCatalogSnapshot.fromDefinitions(listOf(definition))
        val entry = snapshot.entry(definition.name) ?: return
        if (!isAutoLearnable(entry.toolName, entry.source) || entry.externalUntrusted) return
        val executionId = ExecutionRecordIds.tool(
            executionContext.runId.toString(),
            executionContext.toolCallId,
        )
        val ledger = executionRepository.get(executionId) ?: return
        if (ExecutionStatus.fromWire(ledger.status) != ExecutionStatus.succeeded) return
        val verification = VerificationState.fromWire(ledger.verificationState)
        if (verification in setOf(
                VerificationState.STALE,
                VerificationState.UNKNOWN,
                VerificationState.RECONCILING,
            )
        ) return
        val outcome = ToolExperienceOutcomeClassifier.classify(completed.output, verification) ?: return
        experiences.record(
            ToolExperienceSignal(
                authoritySubjectId = subject.id,
                origin = executionContext.callOrigin,
                executionId = executionId,
                toolName = entry.toolName,
                toolNames = listOf(entry.toolName),
                categoryPath = entry.categoryPath,
                schemaFingerprint = entry.schemaFingerprint,
                source = entry.source,
                outcome = outcome,
            ),
        )
        // A model-confirmed shortcut becomes more relevant only after an independently tracked
        // successful execution. This refresh stores no tool argument or result and never pins a
        // tool automatically.
        shortcuts.recordSuccessfulUse(entry, subject.id)
    }

    private companion object {
        fun isAutoLearnable(toolName: String, source: ToolDescriptorSource): Boolean = when {
            toolName in setOf(
                ToolDiscoverySession.TOOL_CATALOG_SEARCH,
                ToolDiscoverySession.TOOL_CATALOG_LIST,
                ToolDiscoverySession.TOOL_CATALOG_OPEN,
                ToolDiscoverySession.TOOL_EXPERIENCE_UPDATE,
                "ask_user",
            ) -> false
            source == ToolDescriptorSource.STATIC_CAPABILITY -> true
            source != ToolDescriptorSource.INTERNAL -> false
            else -> toolName.startsWith("workspace_") ||
                toolName.startsWith("linux_") ||
                toolName.startsWith("termux_") ||
                toolName.startsWith("ssh_") ||
                toolName.startsWith("privileged_") ||
                toolName.startsWith("external_bridge_")
        }
    }
}

/**
 * Classifies only transient tool result envelopes. Neither raw text nor parsed values are ever
 * persisted; the caller retains just the stable outcome kind needed for an evidence row.
 */
internal object ToolExperienceOutcomeClassifier {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun classify(
        output: List<UIMessagePart>,
        verification: VerificationState,
    ): ToolExperienceOutcomeKind? {
        val envelopes = output.filterIsInstance<UIMessagePart.Text>().mapNotNull { part ->
            runCatching { json.parseToJsonElement(part.text).jsonObject }.getOrNull()
        }
        val hasExplicitFailure = envelopes.any { envelope ->
            envelope["error"] != null ||
                envelope.booleanValue("success") == false ||
                envelope.booleanValue("ok") == false
        }
        if (hasExplicitFailure) return null
        if (envelopes.any { envelope ->
                envelope.booleanValue("success") == true || envelope.booleanValue("ok") == true
            }
        ) {
            return ToolExperienceOutcomeKind.STANDARD_SUCCESS
        }
        return if (verification == VerificationState.RUNTIME_CONFIRMED) {
            ToolExperienceOutcomeKind.RUNTIME_CONFIRMED
        } else ToolExperienceOutcomeKind.HOST_COMPLETED
    }

    private fun kotlinx.serialization.json.JsonObject.booleanValue(name: String): Boolean? =
        this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
}
