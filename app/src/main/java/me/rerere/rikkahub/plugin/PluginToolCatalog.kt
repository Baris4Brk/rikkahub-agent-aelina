package me.rerere.rikkahub.plugin

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import kotlin.time.Duration

data class PluginToolSurfaceRequest(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    val origin: ToolCallOrigin,
    val assistantEnabledPluginIds: Set<String>,
    val isHeadless: Boolean = false,
    val isSubAgent: Boolean = false,
    /** A deliberately bounded, non-chat state projection. */
    val stateProjection: String = "{}",
)

data class PluginToolRegistration(
    val definition: Tool,
    val startable: StartableTool,
)

/**
 * Projects reviewed plugin manifests into the model tool surface. Package lookup, assistant
 * scoping, schema conversion, execution, cancellation, and untrusted-result labelling stay behind
 * this one interface; callers never receive a WebView, path, RPC token, or raw plugin ID.
 */
class PluginToolCatalog(
    private val registry: PluginRegistryStore,
    private val invoker: PluginInvocationRunner,
    private val isRuntimeEnabled: () -> Boolean,
    private val executionScope: CoroutineScope,
) {
    fun registrations(request: PluginToolSurfaceRequest): List<PluginToolRegistration> {
        if (!isRuntimeEnabled() || request.origin != ToolCallOrigin.LocalChat ||
            request.isHeadless || request.isSubAgent
        ) return emptyList()
        if (request.assistantId.isBlank() || request.conversationId.isBlank() ||
            request.runId.isBlank()
        ) return emptyList()

        val names = hashSetOf<String>()
        return registry.snapshot()
            .asSequence()
            .filter { record ->
                record.enabled &&
                    record.reviewStatus == PluginReviewStatus.APPROVED &&
                    record.id in request.assistantEnabledPluginIds
            }
            .sortedBy(InstalledPluginRecord::id)
            .flatMap { record ->
                record.manifest.tools.sortedBy(PluginToolManifest::slug).asSequence().mapNotNull { tool ->
                    val modelName = PluginManifestValidator.modelToolName(record.id, tool.slug)
                    if (!names.add(modelName)) return@mapNotNull null
                    val invocationFactory: (JsonElement, ToolExecutionContext?) -> PluginInvocation =
                        { args, context ->
                            PluginInvocation(
                                pluginId = record.id,
                                handler = tool.handler,
                                kind = PluginInvocationKind.TOOL,
                                inputJson = args.toString(),
                                assistantEnabledPluginIds = request.assistantEnabledPluginIds,
                                stateProjection = request.stateProjection.take(MAX_STATE_PROJECTION_CHARS),
                                assistantId = context?.assistantId ?: request.assistantId,
                                conversationId = context?.conversationId?.toString()
                                    ?: request.conversationId,
                                runId = context?.runId?.toString() ?: request.runId,
                                origin = request.origin,
                                isHeadless = request.isHeadless,
                                isSubAgent = request.isSubAgent,
                            )
                        }
                    PluginToolRegistration(
                        definition = Tool(
                            name = modelName,
                            description = tool.description,
                            parameters = { tool.inputSchema.toInputSchema() },
                            needsApproval = { true },
                            execute = { args ->
                                renderPluginResult(
                                    pluginId = record.id,
                                    response = invoker.invoke(invocationFactory(args, null)),
                                )
                            },
                        ),
                        startable = PluginStartableTool(
                            pluginId = record.id,
                            invoker = invoker,
                            invocationFactory = invocationFactory,
                            executionScope = executionScope,
                        ),
                    )
                }
            }
            .toList()
    }

    private fun JsonObject.toInputSchema(): InputSchema.Obj {
        val properties = this["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (this["required"] as? JsonArray)
            ?.mapNotNull { value -> value.jsonPrimitive.contentOrNull }
            ?.distinct()
            ?.takeIf(List<String>::isNotEmpty)
        return InputSchema.Obj(properties = properties, required = required)
    }

    private class PluginStartableTool(
        private val pluginId: String,
        private val invoker: PluginInvocationRunner,
        private val invocationFactory: (JsonElement, ToolExecutionContext?) -> PluginInvocation,
        private val executionScope: CoroutineScope,
    ) : StartableTool {
        override suspend fun start(
            args: JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionHandle {
            val executionId = "plugin_${UUID.randomUUID().toString().replace("-", "")}"
            val deferred = executionScope.async {
                renderPluginResult(pluginId, invoker.invoke(invocationFactory(args, context)))
            }
            return PluginExecutionHandle(executionId, deferred)
        }
    }

    private class PluginExecutionHandle(
        override val executionId: String,
        private val result: Deferred<ToolResult>,
    ) : ToolExecutionHandle {
        private val cancelRequested = AtomicBoolean(false)

        override suspend fun awaitResult(): ToolResult = result.await()

        override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
            if (!cancelRequested.compareAndSet(false, true)) {
                return CancelRequestResult.AlreadyRequested
            }
            result.cancel(CancellationException(reason.message))
            return CancelRequestResult.Requested
        }

        override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
            val stopped = withTimeoutOrNull(gracePeriod.inWholeMilliseconds.coerceAtLeast(1L)) {
                result.join()
                true
            } == true
            return when {
                stopped -> ToolTerminationState.StoppedConfirmed
                cancelRequested.get() -> ToolTerminationState.StillRunning
                else -> ToolTerminationState.Unknown
            }
        }
    }

    private companion object {
        const val MAX_STATE_PROJECTION_CHARS = 8 * 1024
        val JSON = Json { ignoreUnknownKeys = false }

        fun renderPluginResult(
            pluginId: String,
            response: PluginRuntimeResponse,
        ): ToolResult {
            val body = buildJsonObject {
                put("trust", "untrusted_plugin_output")
                put("plugin_id_hash", PluginManifestValidator.pluginIdHash(pluginId))
                if (response.ok) {
                    val output = response.outputJson?.take(PluginRuntimeRequestValidator.MAX_OUTPUT_CHARS)
                    val parsed = output?.let { runCatching { JSON.parseToJsonElement(it) }.getOrNull() }
                    put("content", parsed ?: JsonPrimitive(output.orEmpty()))
                } else {
                    put("error", response.errorCode ?: "plugin_execution_failed")
                }
            }
            return listOf(UIMessagePart.Text(body.toString()))
        }
    }
}
