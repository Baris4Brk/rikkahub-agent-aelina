package me.rerere.rikkahub.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid
import me.rerere.rikkahub.context.ContextOmissionReason
import me.rerere.rikkahub.context.ContextRunDiagnostic
import me.rerere.rikkahub.context.ContextSource
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.InternalToolSecurityCatalog
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicyResolver
import me.rerere.rikkahub.data.ai.execution.ToolSecurityDescriptorResolver
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.display.DisplaySession
import me.rerere.rikkahub.display.DisplaySessionLifecycle
import me.rerere.rikkahub.execution.ManagedExecutionSnapshot
import me.rerere.rikkahub.execution.ManagedExecutionStatus
import me.rerere.rikkahub.plugin.InstalledPluginRecord
import me.rerere.rikkahub.plugin.PluginManifestValidator
import me.rerere.rikkahub.plugin.PluginReviewStatus
import me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES

/**
 * Aggregates P0 runtime diagnostics without retaining observations, task inputs, or plugin code.
 * The provider formats these counters with localized resources before showing them to the user.
 */
internal data class RuntimeP0DiagnosticsState(
    val contextRuns: List<ContextRunDiagnostic>,
    val displaySessions: List<DisplaySession>,
    val managedExecutions: List<ManagedExecutionSnapshot>,
    val plugins: List<InstalledPluginRecord>,
    val securityCoverage: ToolSecurityCoverage,
)

internal data class RuntimeP0DiagnosticsSummary(
    val context: ContextBrokerDiagnosticSummary,
    val display: DisplaySessionDiagnosticSummary,
    val managedExecution: ManagedExecutionDiagnosticSummary,
    val plugins: PluginRuntimeDiagnosticSummary,
    val securityCoverage: ToolSecurityCoverage,
)

internal data class ContextBrokerDiagnosticSummary(
    val recentRunCount: Int,
    val sourceCounts: Map<ContextSource, Int>,
    val omissionCounts: Map<ContextOmissionReason, Int>,
    val totalCharacters: Int,
    val unavailableOrFailedCount: Int,
) {
    val status: RuntimeDiagnosticStatus
        get() = if (unavailableOrFailedCount > 0) {
            RuntimeDiagnosticStatus.SERVICE_OFFLINE
        } else {
            RuntimeDiagnosticStatus.READY
        }
}

internal data class DisplaySessionDiagnosticSummary(
    val activeCount: Int,
    val expiredCount: Int,
    val lostCount: Int,
    val closedCount: Int,
) {
    val status: RuntimeDiagnosticStatus
        get() = if (lostCount > 0) RuntimeDiagnosticStatus.SERVICE_OFFLINE
        else RuntimeDiagnosticStatus.READY
}

internal data class ManagedExecutionDiagnosticSummary(
    val knownCount: Int,
    val activeCount: Int,
    val stopRequestedCount: Int,
    val terminationUncertainCount: Int,
) {
    val status: RuntimeDiagnosticStatus
        get() = if (terminationUncertainCount > 0) {
            RuntimeDiagnosticStatus.SERVICE_OFFLINE
        } else {
            RuntimeDiagnosticStatus.READY
        }
}

internal data class PluginRuntimeDiagnosticSummary(
    val installedCount: Int,
    val enabledCount: Int,
    val needsReviewCount: Int,
    val quarantinedCount: Int,
    val recentFailureCount: Int,
) {
    val status: RuntimeDiagnosticStatus
        get() = if (quarantinedCount > 0) RuntimeDiagnosticStatus.SERVICE_OFFLINE
        else RuntimeDiagnosticStatus.READY
}

internal data class ToolSecurityCoverage(
    val staticToolCount: Int,
    val staticCoveredToolCount: Int,
    val approvedPluginToolCount: Int,
    val approvedPluginCoveredToolCount: Int,
) {
    val status: RuntimeDiagnosticStatus
        get() = if (
            staticToolCount == staticCoveredToolCount &&
            approvedPluginToolCount == approvedPluginCoveredToolCount
        ) {
            RuntimeDiagnosticStatus.READY
        } else {
            RuntimeDiagnosticStatus.SERVICE_OFFLINE
        }
}

internal fun summarizeRuntimeP0Diagnostics(
    state: RuntimeP0DiagnosticsState,
    nowMs: Long,
): RuntimeP0DiagnosticsSummary = RuntimeP0DiagnosticsSummary(
    context = ContextBrokerDiagnosticSummary(
        recentRunCount = state.contextRuns.size,
        sourceCounts = state.contextRuns
            .flatMap(ContextRunDiagnostic::sources)
            .groupingBy { it.source }
            .eachCount(),
        omissionCounts = state.contextRuns
            .flatMap(ContextRunDiagnostic::omissions)
            .groupingBy { it.reason }
            .eachCount(),
        totalCharacters = state.contextRuns.sumOf(ContextRunDiagnostic::totalCharacters),
        unavailableOrFailedCount = state.contextRuns
            .flatMap(ContextRunDiagnostic::omissions)
            .count { it.reason in CONTEXT_RUNTIME_FAILURE_REASONS },
    ),
    display = DisplaySessionDiagnosticSummary(
        activeCount = state.displaySessions.count { it.lifecycle == DisplaySessionLifecycle.ACTIVE },
        expiredCount = state.displaySessions.count { it.lifecycle == DisplaySessionLifecycle.EXPIRED },
        lostCount = state.displaySessions.count { it.lifecycle == DisplaySessionLifecycle.LOST },
        closedCount = state.displaySessions.count { it.lifecycle == DisplaySessionLifecycle.CLOSED },
    ),
    managedExecution = ManagedExecutionDiagnosticSummary(
        knownCount = state.managedExecutions.size,
        activeCount = state.managedExecutions.count {
            it.status in ACTIVE_MANAGED_EXECUTION_STATUSES
        },
        stopRequestedCount = state.managedExecutions.count {
            it.status == ManagedExecutionStatus.STOP_REQUESTED
        },
        terminationUncertainCount = state.managedExecutions.count {
            it.terminationUncertain || it.status in TERMINATION_UNCERTAIN_STATUSES
        },
    ),
    plugins = PluginRuntimeDiagnosticSummary(
        installedCount = state.plugins.size,
        enabledCount = state.plugins.count(InstalledPluginRecord::enabled),
        needsReviewCount = state.plugins.count {
            it.reviewStatus == PluginReviewStatus.NEEDS_REVIEW
        },
        quarantinedCount = state.plugins.count {
            it.reviewStatus == PluginReviewStatus.QUARANTINED
        },
        recentFailureCount = state.plugins.sumOf { plugin ->
            plugin.failureTimestampsMs.count { timestamp ->
                timestamp in (nowMs - PLUGIN_FAILURE_WINDOW_MS)..nowMs
            }
        },
    ),
    securityCoverage = state.securityCoverage,
)

/**
 * Verifies the fixed model-visible catalog and currently enabled approved plugin tools. MCP
 * tools remain deliberately per-call because their names arrive only after a remote discovery.
 */
internal fun calculateToolSecurityCoverage(
    resolver: ToolSecurityDescriptorResolver,
    policyResolver: ToolExecutionPolicyResolver,
    plugins: List<InstalledPluginRecord>,
): ToolSecurityCoverage {
    val context = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "runtime-diagnostics",
        callOrigin = ToolCallOrigin.LocalChat,
    )
    val staticToolNames = fixedSecurityToolNames()
    val approvedPluginToolNames = approvedPluginToolNames(plugins)
    return ToolSecurityCoverage(
        staticToolCount = staticToolNames.size,
        staticCoveredToolCount = staticToolNames.count { toolName ->
            hasSecurityAndPolicyCoverage(
                toolName = toolName,
                context = context,
                descriptorResolver = resolver,
                policyResolver = policyResolver,
                allowsUnknownPolicy = false,
            )
        },
        approvedPluginToolCount = approvedPluginToolNames.size,
        approvedPluginCoveredToolCount = approvedPluginToolNames.count { toolName ->
            hasSecurityAndPolicyCoverage(
                toolName = toolName,
                context = context,
                descriptorResolver = resolver,
                policyResolver = policyResolver,
                // Third-party plugin tools are intentionally UNKNOWN + GLOBAL_SERIAL until an
                // every-call approval. That is an explicit fail-closed policy, not a gap.
                allowsUnknownPolicy = true,
            )
        },
    )
}

private fun hasSecurityAndPolicyCoverage(
    toolName: String,
    context: ToolExecutionContext,
    descriptorResolver: ToolSecurityDescriptorResolver,
    policyResolver: ToolExecutionPolicyResolver,
    allowsUnknownPolicy: Boolean,
): Boolean {
    val descriptor = runCatching { descriptorResolver.resolve(toolName, context) }.getOrNull()
        ?: return false
    val policy = runCatching {
        policyResolver.resolve(toolName, JsonObject(emptyMap()), context)
    }.getOrNull() ?: return false
    return allowsUnknownPolicy || policy != ToolExecutionPolicy.UNKNOWN
}

private fun fixedSecurityToolNames(): Set<String> = buildSet {
    CapabilityCatalog.allCapabilities().forEach { descriptor -> addAll(descriptor.toolNames) }
    addAll(InternalToolSecurityCatalog.ALL)
    add(PRIVILEGED_SHELL_TOOL_NAME)
    add("privileged_run_command")
    add("external_bridge_run_command")
    addAll(STRUCTURED_PRIVILEGED_TOOL_NAMES)
    addAll(STRUCTURED_PRIVILEGED_V2_TOOL_NAMES)
}

private fun approvedPluginToolNames(plugins: List<InstalledPluginRecord>): Set<String> = plugins
    .asSequence()
    .filter { plugin -> plugin.enabled && plugin.reviewStatus == PluginReviewStatus.APPROVED }
    .flatMap { plugin ->
        plugin.manifest.tools.asSequence().map { tool ->
            PluginManifestValidator.modelToolName(plugin.id, tool.slug)
        }
    }
    .toSet()

private val CONTEXT_RUNTIME_FAILURE_REASONS = setOf(
    ContextOmissionReason.UNAVAILABLE,
    ContextOmissionReason.TIMED_OUT,
    ContextOmissionReason.FAILED,
)

private val ACTIVE_MANAGED_EXECUTION_STATUSES = setOf(
    ManagedExecutionStatus.STARTING,
    ManagedExecutionStatus.RUNNING,
    ManagedExecutionStatus.RECOVERING,
    ManagedExecutionStatus.STOP_REQUESTED,
)

private val TERMINATION_UNCERTAIN_STATUSES = setOf(
    ManagedExecutionStatus.LOST,
    ManagedExecutionStatus.UNKNOWN,
)

private const val PLUGIN_FAILURE_WINDOW_MS = 10 * 60_000L
