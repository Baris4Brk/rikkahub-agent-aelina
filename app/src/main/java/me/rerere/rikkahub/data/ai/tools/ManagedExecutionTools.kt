package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.execution.ManagedExecutionCaller
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionLogs
import me.rerere.rikkahub.execution.ManagedExecutionRequest
import me.rerere.rikkahub.execution.ManagedExecutionResult
import me.rerere.rikkahub.execution.ManagedExecutionSnapshot
import me.rerere.rikkahub.data.execution.CancellationCoordinator

internal fun managedExecutionToolsForInvocation(
    coordinator: ManagedExecutionCoordinator,
    cancellationCoordinator: CancellationCoordinator? = null,
    options: List<LocalToolOption>,
    invocationContext: ToolInvocationContext,
): List<Tool> {
    val caller = invocationContext.toManagedExecutionCaller(options) ?: return emptyList()
    return managedExecutionTools(coordinator, caller, cancellationCoordinator)
}

fun managedExecutionTools(
    coordinator: ManagedExecutionCoordinator,
    caller: ManagedExecutionCaller,
    cancellationCoordinator: CancellationCoordinator? = null,
): List<Tool> = listOf(
    Tool(
        name = "execution_list",
        description = "List managed Workspace, Termux, and saved-profile SSH tasks visible to this assistant. This tool cannot start commands.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("include_stopped", booleanSchema("Include terminal task records"))
            })
        },
        execute = { input ->
            executionResult(
                coordinator.dispatch(
                    ManagedExecutionRequest.List(
                        caller,
                        includeStopped = input.jsonObject["include_stopped"]
                            ?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                )
            )
        },
    ),
    idTool(
        name = "execution_status",
        description = "Get one managed task status by the namespaced execution_id returned by execution_list.",
    ) { id, _ -> coordinator.dispatch(ManagedExecutionRequest.Status(caller, id)) },
    Tool(
        name = "execution_logs",
        description = "Read a bounded stdout/stderr tail for one managed task. Output may contain private data.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("execution_id", stringSchema("Namespaced managed execution id"))
                    put("tail_bytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 256 * 1024)
                    })
                },
                required = listOf("execution_id"),
            )
        },
        needsApproval = { true },
        execute = { input ->
            val id = input.jsonObject["execution_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tail = input.jsonObject["tail_bytes"]?.jsonPrimitive?.intOrNull ?: 32 * 1024
            executionResult(
                coordinator.dispatch(ManagedExecutionRequest.Logs(caller, id, tail))
            )
        },
    ),
    idTool(
        name = "execution_stop",
        description = "Request TERM then KILL when needed and report whether task termination was confirmed.",
        includeForce = true,
        needsApproval = true,
    ) { id, force ->
        val ownership = coordinator.dispatch(ManagedExecutionRequest.Status(caller, id))
        if (ownership !is ManagedExecutionResult.Snapshot || cancellationCoordinator == null) {
            coordinator.dispatch(ManagedExecutionRequest.Stop(caller, id, force))
        } else {
            cancellationCoordinator.cancelAndAwait(id)
            coordinator.dispatch(ManagedExecutionRequest.Status(caller, id))
        }
    },
)

private fun idTool(
    name: String,
    description: String,
    includeForce: Boolean = false,
    needsApproval: Boolean = false,
    execute: suspend (String, Boolean) -> ManagedExecutionResult,
) = Tool(
    name = name,
    description = description,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("execution_id", stringSchema("Namespaced managed execution id"))
                if (includeForce) put("force", booleanSchema("Skip graceful wait when supported"))
            },
            required = listOf("execution_id"),
        )
    },
    needsApproval = { needsApproval },
    execute = { input ->
        val id = input.jsonObject["execution_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val force = input.jsonObject["force"]?.jsonPrimitive?.booleanOrNull ?: false
        executionResult(execute(id, force))
    },
)

private fun executionResult(result: ManagedExecutionResult): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        when (result) {
            is ManagedExecutionResult.Executions -> buildJsonObject {
                put("executions", buildJsonArray {
                    result.executions.forEach { add(it.toJson()) }
                })
            }
            is ManagedExecutionResult.Snapshot -> buildJsonObject {
                put("execution", result.execution.toJson())
            }
            is ManagedExecutionResult.Logs -> buildJsonObject {
                put("execution", result.execution.toJson())
                put("logs", result.logs.toJson())
            }
            is ManagedExecutionResult.Stopped -> buildJsonObject {
                put("stopped", true)
                put("execution", result.execution.toJson())
            }
            is ManagedExecutionResult.Error -> buildJsonObject { put("error", result.code) }
        }.toString()
    )
)

private fun ManagedExecutionSnapshot.toJson() = buildJsonObject {
    put("execution_id", executionId)
    put("runtime", runtime.name.lowercase())
    put("name", name)
    put("status", status.name.lowercase())
    put("alive", alive)
    startedAtMs?.let { put("started_at_ms", it) }
    lastExitCode?.let { put("last_exit_code", it) }
    put("termination_uncertain", terminationUncertain)
}

private fun ManagedExecutionLogs.toJson() = buildJsonObject {
    put("stdout", stdout)
    put("stderr", stderr)
    put("truncated", truncated)
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun booleanSchema(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
