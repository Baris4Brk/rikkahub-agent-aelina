package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.data.ai.execution.RedactedToolLifecycleEvent
import me.rerere.rikkahub.data.ai.execution.ToolLifecycleObserver
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.capability.ResourceScope
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver

/**
 * Projects ToolRuntime lifecycle events into the authoritative execution table.
 *
 * It intentionally resolves resource metadata without argument values, so no command, file
 * content, credential, URL parameter, or tool output can leak into Room, audit, or logs.
 */
class ExecutionRecordToolLifecycleObserver(
    private val repository: ExecutionRepository,
) : ToolLifecycleObserver {
    override suspend fun onEvent(event: RedactedToolLifecycleEvent) {
        val context = event.context
        val recordId = recordId(context.runId, context.toolCallId)
        when (event.phase) {
            RedactedToolLifecycleEvent.Phase.STARTING -> {
                val resolved = ToolCapabilityResolver.resolve(context.toolName)
                repository.open(
                    ExecutionRecordDraft(
                        id = recordId,
                        traceId = context.runId,
                        commandId = context.runId,
                        conversationId = context.conversationId,
                        subjectId = context.subjectId.ifBlank { context.assistantId },
                        subjectType = (context.subjectType ?: SubjectType.LOCAL_ASSISTANT).name,
                        origin = context.origin.name,
                        capabilityKeys = resolved.capabilities
                            .map { it.value }
                            .sorted()
                            .joinToString(","),
                        resourceSummary = resolved.resource.toAuditSummary(),
                        runtime = runtimeFor(context.toolName, context.legacyExecution),
                        idempotencyKey = "tool:${context.runId}:${context.toolCallId}".take(300),
                        initialStatus = ExecutionStatus.starting,
                    ),
                )
            }

            RedactedToolLifecycleEvent.Phase.RUNNING -> {
                event.executionId?.let { handle ->
                    repository.bindRuntime(recordId, runtimeForHandle(context.toolName, handle), handle)
                }
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.running,
                    runtimeHandleSummary = event.executionId,
                )
            }

            RedactedToolLifecycleEvent.Phase.CANCEL_REQUESTED -> repository.transition(
                id = recordId,
                target = ExecutionStatus.cancel_requested,
                runtimeHandleSummary = event.executionId,
                detail = event.detail ?: "cancel_requested",
            )

            RedactedToolLifecycleEvent.Phase.TERMINATING -> repository.transition(
                id = recordId,
                target = ExecutionStatus.terminating,
                runtimeHandleSummary = event.executionId,
            )

            RedactedToolLifecycleEvent.Phase.COMPLETED -> repository.transition(
                id = recordId,
                target = ExecutionStatus.succeeded,
                runtimeHandleSummary = event.executionId,
            )

            RedactedToolLifecycleEvent.Phase.FAILED -> repository.transition(
                id = recordId,
                target = ExecutionStatus.failed,
                runtimeHandleSummary = event.executionId,
                detail = event.detail ?: "runtime_failed",
            )

            RedactedToolLifecycleEvent.Phase.CANCELLED -> {
                val terminal = when (event.terminationState) {
                    ToolTerminationState.StoppedConfirmed -> ExecutionStatus.cancelled
                    ToolTerminationState.CancelRequested,
                    ToolTerminationState.StillRunning,
                    ToolTerminationState.Unsupported,
                    ToolTerminationState.Unknown,
                    null,
                    -> ExecutionStatus.orphaned
                }
                repository.transition(
                    id = recordId,
                    target = terminal,
                    runtimeHandleSummary = event.executionId,
                    cancellationResult = event.terminationState?.name,
                    detail = if (terminal == ExecutionStatus.cancelled) {
                        "termination_confirmed"
                    } else {
                        "termination_unconfirmed"
                    },
                )
            }

            RedactedToolLifecycleEvent.Phase.TIMED_OUT -> repository.transition(
                id = recordId,
                target = ExecutionStatus.timed_out,
                runtimeHandleSummary = event.executionId,
                cancellationResult = event.terminationState?.name,
                detail = if (event.terminationState == ToolTerminationState.StoppedConfirmed) {
                    "wall_clock_timeout_termination_confirmed"
                } else {
                    "wall_clock_timeout_termination_unconfirmed"
                },
            )
        }
    }

    private fun ResourceScope.toAuditSummary(): String = "$kind:${identifier.take(120)}"

    private fun runtimeFor(toolName: String, legacy: Boolean): ExecutionRuntime = when {
        legacy -> ExecutionRuntime.LEGACY
        toolName.startsWith("termux_") -> ExecutionRuntime.TERMUX
        toolName.startsWith("ssh_") -> ExecutionRuntime.SSH
        toolName.startsWith("workspace_") -> ExecutionRuntime.WORKSPACE
        toolName.startsWith("mcp__") -> ExecutionRuntime.MCP
        toolName.startsWith("plugin__") -> ExecutionRuntime.PLUGIN
        toolName.startsWith("privileged_") || toolName.startsWith("external_bridge_") ->
            ExecutionRuntime.SHIZUKU
        else -> ExecutionRuntime.LOCAL_TOOL
    }

    private fun runtimeForHandle(toolName: String, handle: String): ExecutionRuntime = when {
        handle.startsWith("termux:") -> ExecutionRuntime.TERMUX
        handle.startsWith("workspace:") -> ExecutionRuntime.WORKSPACE
        toolName.startsWith("ssh_") -> ExecutionRuntime.SSH
        else -> runtimeFor(toolName, legacy = false)
    }

    private fun recordId(runId: String, toolCallId: String): String =
        "tool:$runId:$toolCallId".take(480)
}
