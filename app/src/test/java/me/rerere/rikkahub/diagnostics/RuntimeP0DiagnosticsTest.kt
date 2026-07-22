package me.rerere.rikkahub.diagnostics

import me.rerere.rikkahub.context.ContextInvocationSurface
import me.rerere.rikkahub.context.ContextOmissionDiagnostic
import me.rerere.rikkahub.context.ContextOmissionReason
import me.rerere.rikkahub.context.ContextRunDiagnostic
import me.rerere.rikkahub.context.ContextSource
import me.rerere.rikkahub.context.ContextSourceDiagnostic
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorApproval
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorSource
import me.rerere.rikkahub.data.ai.execution.ToolCancellationCapability
import me.rerere.rikkahub.data.ai.execution.ToolConcurrency
import me.rerere.rikkahub.data.ai.execution.ToolEffect
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicyResolver
import me.rerere.rikkahub.data.ai.execution.ToolSecurityDescriptor
import me.rerere.rikkahub.data.ai.execution.ToolSecurityDescriptorResolver
import me.rerere.rikkahub.display.DisplayCaller
import me.rerere.rikkahub.display.DisplaySession
import me.rerere.rikkahub.display.DisplaySessionLifecycle
import me.rerere.rikkahub.execution.ManagedExecutionRuntime
import me.rerere.rikkahub.execution.ManagedExecutionSnapshot
import me.rerere.rikkahub.execution.ManagedExecutionStatus
import me.rerere.rikkahub.plugin.InstalledPluginRecord
import me.rerere.rikkahub.plugin.PluginManifestV1
import me.rerere.rikkahub.plugin.PluginPermissions
import me.rerere.rikkahub.plugin.PluginReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeP0DiagnosticsTest {
    @Test
    fun `summary retains only context counters and never the observed content`() {
        val summary = summarizeRuntimeP0Diagnostics(
            RuntimeP0DiagnosticsState(
                contextRuns = listOf(
                    ContextRunDiagnostic(
                        opaqueRunId = "opaque-run",
                        invocationSurface = ContextInvocationSurface.LOCAL_CHAT,
                        sources = listOf(
                            ContextSourceDiagnostic(ContextSource.UI_TREE, 128, "vision"),
                            ContextSourceDiagnostic(ContextSource.DEVICE_STATUS, 32, null),
                        ),
                        omissions = listOf(
                            ContextOmissionDiagnostic(
                                ContextSource.OCR_FALLBACK,
                                ContextOmissionReason.UI_TREE_SUFFICIENT,
                                null,
                            ),
                        ),
                        totalCharacters = 160,
                        collectedAtMs = 100L,
                    ),
                ),
                displaySessions = emptyList(),
                managedExecutions = emptyList(),
                plugins = emptyList(),
                securityCoverage = ToolSecurityCoverage(4, 4, 0, 0),
            ),
            nowMs = 1_000L,
        )

        assertEquals(1, summary.context.recentRunCount)
        assertEquals(1, summary.context.sourceCounts[ContextSource.UI_TREE])
        assertEquals(160, summary.context.totalCharacters)
        assertEquals(RuntimeDiagnosticStatus.READY, summary.context.status)
        assertFalse(summary.toString().contains("sensitive screen content"))
    }

    @Test
    fun `lost display uncertain termination and quarantined plugin are surfaced`() {
        val summary = summarizeRuntimeP0Diagnostics(
            RuntimeP0DiagnosticsState(
                contextRuns = emptyList(),
                displaySessions = listOf(displaySession(DisplaySessionLifecycle.LOST)),
                managedExecutions = listOf(
                    ManagedExecutionSnapshot(
                        executionId = "termux:one",
                        runtime = ManagedExecutionRuntime.TERMUX,
                        name = "redacted-name",
                        status = ManagedExecutionStatus.UNKNOWN,
                        alive = false,
                        terminationUncertain = true,
                    ),
                ),
                plugins = listOf(plugin(PluginReviewStatus.QUARANTINED)),
                securityCoverage = ToolSecurityCoverage(2, 1, 0, 0),
            ),
            nowMs = 1_000L,
        )

        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, summary.display.status)
        assertEquals(1, summary.managedExecution.terminationUncertainCount)
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, summary.managedExecution.status)
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, summary.plugins.status)
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, summary.securityCoverage.status)
        assertFalse(summary.toString().contains("redacted-name"))
    }

    @Test
    fun `security coverage detects an uncovered fixed model tool`() {
        val resolver = ToolSecurityDescriptorResolver { toolName, _ ->
            if (toolName == "memory_query") {
                null
            } else {
                ToolSecurityDescriptor(
                    toolName = toolName,
                    source = ToolDescriptorSource.INTERNAL,
                    approval = ToolDescriptorApproval.CALL_DEFINED,
                    allowsPermanentApproval = false,
                )
            }
        }

        val coverage = calculateToolSecurityCoverage(
            resolver = resolver,
            policyResolver = ToolExecutionPolicyResolver { _, _, _ -> TEST_POLICY },
            plugins = emptyList(),
        )

        assertTrue(coverage.staticToolCount > 0)
        assertEquals(coverage.staticToolCount - 1, coverage.staticCoveredToolCount)
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, coverage.status)
    }

    private fun displaySession(lifecycle: DisplaySessionLifecycle) = DisplaySession(
        id = "session-1",
        displayId = 42,
        caller = DisplayCaller(
            assistantId = "assistant",
            conversationId = "conversation",
            runId = "run",
            origin = me.rerere.rikkahub.data.ai.ToolCallOrigin.LocalChat,
        ),
        capabilities = emptySet(),
        lifecycle = lifecycle,
        createdAtMs = 1L,
        lastUsedAtMs = 2L,
        hardExpiresAtMs = 3L,
    )

    private fun plugin(status: PluginReviewStatus) = InstalledPluginRecord(
        id = "sample-plugin",
        name = "Sample",
        version = "1",
        manifest = PluginManifestV1(
            schemaVersion = 1,
            id = "sample-plugin",
            name = "Sample",
            version = "1",
            entry = "index.html",
            permissions = PluginPermissions(),
        ),
        sourceSha256 = "0".repeat(64),
        permissions = emptySet(),
        enabled = false,
        reviewStatus = status,
        installedAtMs = 1L,
        updatedAtMs = 1L,
    )

    private companion object {
        val TEST_POLICY = ToolExecutionPolicy(
            effects = setOf(ToolEffect.LOCAL_READ),
            concurrency = ToolConcurrency.PARALLEL_SAFE,
            cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
        )
    }
}
