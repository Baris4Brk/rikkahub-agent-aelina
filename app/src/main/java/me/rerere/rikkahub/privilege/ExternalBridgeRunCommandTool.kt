package me.rerere.rikkahub.privilege

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridge
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgeStatus
import kotlin.time.Duration
import kotlin.uuid.Uuid

const val PRIVILEGED_SHELL_TOOL_NAME = "external_bridge_run_command"

data class PrivilegedShellToolRegistration(
    val definition: Tool,
    val startable: StartableTool,
)

fun shouldInjectPrivilegedShell(
    privilege: PrivilegedSessionContext,
    origin: ToolCallOrigin,
    isHeadless: Boolean,
    privilegedBridgeEnabled: Boolean,
    bridgeStatus: ExternalPrivilegeBridgeStatus,
): Boolean = privilege.isPrivileged &&
    InvocationSurfacePolicy.canInjectPrivilegedTools(origin, isHeadless) &&
    privilegedBridgeEnabled &&
    bridgeStatus.binderAvailable &&
    bridgeStatus.permissionGranted &&
    bridgeStatus.userServiceAvailable

fun createExternalBridgeRunCommandTool(
    bridge: ExternalPrivilegeBridge,
): PrivilegedShellToolRegistration {
    val startable = object : StartableTool {
        override suspend fun start(
            args: JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionHandle = decodeInput(args).fold(
            onSuccess = { bridge.startCommand(it) },
            onFailure = { invalidInputHandle(it) },
        )
    }
    val definition = Tool(
        name = PRIVILEGED_SHELL_TOOL_NAME,
        description = """
            Execute a finite Android command through the configured Shizuku or Sui external
            privilege bridge. Prefer argv mode for normal commands. Use shell mode only for pipes,
            redirects, variables, or combined shell syntax. Prefer the fixed Shizuku tools when
            they already cover the operation, and use Termux sessions for long-running services.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("argv"); add("shell") })
                    })
                    put("executable", buildJsonObject {
                        put("type", "string")
                        put("description", "Executable path or name for argv mode.")
                    })
                    put("arguments", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Argument vector for argv mode; never include a shell command here.")
                    })
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell text for shell mode only.")
                    })
                    put("stdin", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional UTF-8 standard input.")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("minimum", PrivilegedCommandLimits.MIN_TIMEOUT_MS)
                        put("maximum", PrivilegedCommandLimits.MAX_TIMEOUT_MS)
                    })
                    put("max_output_bytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES)
                    })
                },
                required = listOf("mode"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            decodeInput(args).fold(
                onSuccess = { bridge.startCommand(it).awaitResult() },
                onFailure = { invalidInputResult(it) },
            )
        },
    )
    return PrivilegedShellToolRegistration(definition, startable)
}

private fun decodeInput(args: JsonElement): Result<PrivilegedCommandInput> = runCatching {
    PrivilegedCommandJson.decodeToolInput(args).also { input ->
        val validation = input.validate()
        if (!validation.valid) throw SerializationException(validation.message)
    }
}

private fun invalidInputHandle(error: Throwable): ToolExecutionHandle = ImmediateToolExecutionHandle(
    executionId = Uuid.random().toString(),
    result = invalidInputResult(error),
)

private fun invalidInputResult(error: Throwable): ToolResult = listOf(
    UIMessagePart.Text(
        PrivilegedCommandJson.encodeResult(
            PrivilegedCommandResult(
                ok = false,
                code = "INVALID_ARGUMENTS",
                message = error.message?.take(300) ?: "Invalid privileged command input.",
            ),
        ),
    ),
)

private class ImmediateToolExecutionHandle(
    override val executionId: String,
    private val result: ToolResult,
) : ToolExecutionHandle {
    override suspend fun awaitResult(): ToolResult = result

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult =
        CancelRequestResult.NotFound

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState =
        ToolTerminationState.StoppedConfirmed
}
