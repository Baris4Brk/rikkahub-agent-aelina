package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.data.ai.execution.RedactedToolLifecycleEvent
import me.rerere.rikkahub.data.ai.execution.CriticalLifecyclePersistenceException
import me.rerere.rikkahub.data.ai.execution.CriticalToolLifecycleSink
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
class ExecutionRecordCriticalToolLifecycleSink(
    private val repository: ExecutionRepository,
) : CriticalToolLifecycleSink {
    override suspend fun persist(event: RedactedToolLifecycleEvent) {
        val context = event.context
        val recordId = ExecutionRecordIds.tool(context.runId, context.toolCallId)
        when (event.phase) {
            RedactedToolLifecycleEvent.Phase.STARTING -> {
                val resolved = ToolCapabilityResolver.resolve(context.toolName)
                val record = repository.open(
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
                val current = ExecutionStatus.fromWire(record.status)
                if (current != ExecutionStatus.starting && current != ExecutionStatus.running) {
                    requireDurable(
                        repository.transition(
                            id = recordId,
                            target = ExecutionStatus.starting,
                            mutationId = mutationId(event),
                            reasonCode = "tool_starting",
                        ),
                        ExecutionStatus.starting,
                    )
                }
            }

            RedactedToolLifecycleEvent.Phase.RUNNING -> requireDurable(
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.running,
                    runtimeHandleSummary = event.executionId,
                    runtime = event.executionId?.let { runtimeForHandle(context.toolName, it) },
                    mutationId = mutationId(event),
                    reasonCode = "tool_running",
                ),
                ExecutionStatus.running,
            )

            RedactedToolLifecycleEvent.Phase.CANCEL_REQUESTED -> requireDurable(
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.cancel_requested,
                    runtimeHandleSummary = event.executionId,
                    detail = event.detail ?: "cancel_requested",
                    mutationId = mutationId(event),
                    reasonCode = event.detail ?: "cancel_requested",
                ),
                ExecutionStatus.cancel_requested,
            )

            RedactedToolLifecycleEvent.Phase.TERMINATING -> requireDurable(
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.terminating,
                    runtimeHandleSummary = event.executionId,
                    mutationId = mutationId(event),
                    reasonCode = "termination_started",
                ),
                ExecutionStatus.terminating,
            )

            RedactedToolLifecycleEvent.Phase.COMPLETED -> requireDurable(
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.succeeded,
                    runtimeHandleSummary = event.executionId,
                    mutationId = mutationId(event),
                    reasonCode = "tool_completed",
                    verificationState = VerificationState.LIVE_CONFIRMED,
                ),
                ExecutionStatus.succeeded,
            )

            RedactedToolLifecycleEvent.Phase.FAILED -> requireDurable(
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.failed,
                    runtimeHandleSummary = event.executionId,
                    detail = event.detail ?: "runtime_failed",
                    mutationId = mutationId(event),
                    reasonCode = event.detail ?: "runtime_failed",
                ),
                ExecutionStatus.failed,
            )

            RedactedToolLifecycleEvent.Phase.CANCELLED -> {
                val confirmed = event.terminationState == ToolTerminationState.StoppedConfirmed
                val target = if (confirmed) ExecutionStatus.cancelled else ExecutionStatus.terminating
                val reason = if (confirmed) "termination_confirmed" else "termination_unconfirmed"
                requireDurable(
                    repository.transition(
                        id = recordId,
                        target = target,
                        runtimeHandleSummary = event.executionId,
                        cancellationResult = event.terminationState?.name,
                        detail = reason,
                        mutationId = mutationId(event),
                        reasonCode = reason,
                        verificationState = if (confirmed) {
                            VerificationState.LIVE_CONFIRMED
                        } else {
                            VerificationState.STALE
                        },
                    ),
                    target,
                )
            }

            RedactedToolLifecycleEvent.Phase.TIMED_OUT -> requireDurable(
                repository.transition(
                    id = recordId,
                    target = ExecutionStatus.timed_out,
                    runtimeHandleSummary = event.executionId,
                    cancellationResult = event.terminationState?.name,
                    detail = if (event.terminationState == ToolTerminationState.StoppedConfirmed) {
                        "wall_clock_timeout_termination_confirmed"
                    } else {
                        "wall_clock_timeout_termination_unconfirmed"
                    },
                    mutationId = mutationId(event),
                    reasonCode = event.detail ?: "wall_clock_timeout",
                    verificationState = if (
                        event.terminationState == ToolTerminationState.StoppedConfirmed ||
                        event.executionId == null
                    ) {
                        VerificationState.LIVE_CONFIRMED
                    } else {
                        VerificationState.STALE
                    },
                ),
                ExecutionStatus.timed_out,
            )
        }
    }

    private fun requireDurable(
        result: ExecutionTransitionResult,
        requested: ExecutionStatus,
    ) {
        when (result) {
            is ExecutionTransitionResult.Applied -> Unit
            is ExecutionTransitionResult.Terminal -> {
                if (ExecutionStatus.fromWire(result.record.status) != requested) {
                    throw CriticalLifecyclePersistenceException("execution_already_terminal")
                }
            }
            is ExecutionTransitionResult.Missing ->
                throw CriticalLifecyclePersistenceException("execution_record_missing")
            is ExecutionTransitionResult.Invalid ->
                throw CriticalLifecyclePersistenceException("execution_transition_invalid")
            is ExecutionTransitionResult.Conflict ->
                throw CriticalLifecyclePersistenceException("execution_cas_conflict")
        }
    }

    private fun mutationId(event: RedactedToolLifecycleEvent): String = buildString {
        append("tool-event:")
        append(event.context.runId)
        append(':')
        append(event.context.toolCallId)
        append(':')
        append(event.phase.name)
        event.executionId?.let { append(':').append(it) }
    }.take(500)

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

}

object ExecutionRecordIds {
    fun tool(runId: String, toolCallId: String): String = "tool:$runId:$toolCallId".take(480)
}
