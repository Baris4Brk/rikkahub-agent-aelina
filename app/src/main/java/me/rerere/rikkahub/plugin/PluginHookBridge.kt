package me.rerere.rikkahub.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.RedactedToolCallContext
import me.rerere.rikkahub.data.ai.execution.RedactedToolLifecycleEvent
import me.rerere.rikkahub.data.ai.execution.ToolCallInterceptor
import me.rerere.rikkahub.data.ai.execution.ToolHookDecision
import me.rerere.rikkahub.data.ai.execution.ToolLifecycleObserver

data class PluginPromptHookRequest(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    val origin: ToolCallOrigin,
    val assistantEnabledPluginIds: Set<String>,
    val isHeadless: Boolean = false,
    val isSubAgent: Boolean = false,
)

/**
 * The only bridge between host lifecycle events and plugin hooks. It deliberately constructs a
 * new JSON projection instead of forwarding tool arguments, outputs, chat messages, API keys, or
 * the host's opaque ownership IDs. Interceptors can only return Proceed or Block; they run after
 * the hard safety gates and therefore cannot turn a denial into an allow.
 */
class PluginHookBridge(
    private val registry: PluginRegistryStore,
    private val invoker: PluginInvocationRunner,
    private val isRuntimeEnabled: () -> Boolean,
    private val enabledPluginsForAssistant: (String) -> Set<String>,
) : ToolCallInterceptor, ToolLifecycleObserver {
    override suspend fun intercept(context: RedactedToolCallContext): ToolHookDecision {
        if (!isRuntimeEnabled() || !hasCompleteLocalIdentity(
                context.assistantId,
                context.conversationId,
                context.runId,
                context.origin,
            )
        ) {
            return ToolHookDecision.Proceed
        }
        val enabledIds = enabledPluginsForAssistant(context.assistantId)
        val hooks = eligible(enabledIds).mapNotNull { record ->
            record.manifest.hooks.interceptHandler?.let { handler -> record to handler }
        }
        if (hooks.isEmpty()) return ToolHookDecision.Proceed
        val input = interceptProjection(context).toString()
        for ((record, handler) in hooks) {
            val response = runCatching {
                invoker.invoke(
                    invocation(
                        record = record,
                        handler = handler,
                        kind = PluginInvocationKind.INTERCEPT_HOOK,
                        inputJson = input,
                        assistantId = context.assistantId,
                        conversationId = context.conversationId,
                        runId = context.runId,
                        enabledIds = enabledIds,
                        timeoutMs = INTERCEPT_TIMEOUT_MS,
                    )
                )
            }.getOrNull()
            if (response?.ok != true) return interceptorFailure()
            val decision = response.outputJson?.let(::parseDecision)
                ?: return interceptorFailure()
            if (decision == "block") {
                return ToolHookDecision.Block("An enabled plugin blocked this tool call.")
            }
            if (decision != "proceed") return interceptorFailure()
        }
        return ToolHookDecision.Proceed
    }

    override suspend fun onEvent(event: RedactedToolLifecycleEvent) {
        if (!isRuntimeEnabled() || !hasCompleteLocalIdentity(
                event.context.assistantId,
                event.context.conversationId,
                event.context.runId,
                event.context.origin,
            )
        ) return
        val context = event.context
        val enabledIds = enabledPluginsForAssistant(context.assistantId)
        val input = observerProjection(event).toString()
        eligible(enabledIds).forEach { record ->
            val handler = record.manifest.hooks.observerHandler ?: return@forEach
            runCatching {
                invoker.invoke(
                    invocation(
                        record = record,
                        handler = handler,
                        kind = PluginInvocationKind.OBSERVER_HOOK,
                        inputJson = input,
                        assistantId = context.assistantId,
                        conversationId = context.conversationId,
                        runId = context.runId,
                        enabledIds = enabledIds,
                        timeoutMs = OBSERVER_TIMEOUT_MS,
                    )
                )
            }
            // Observation is best-effort by contract. The coordinator records failures and
            // quarantines repeatedly faulty plugins; the tool result is never changed here.
        }
    }

    suspend fun collectPromptAddendum(request: PluginPromptHookRequest): String? {
        if (!isRuntimeEnabled() || !hasCompleteLocalIdentity(
                request.assistantId,
                request.conversationId,
                request.runId,
                request.origin,
            ) ||
            request.isHeadless || request.isSubAgent
        ) return null
        val enabledIds = request.assistantEnabledPluginIds.intersect(
            enabledPluginsForAssistant(request.assistantId)
        )
        val fragments = mutableListOf<String>()
        for (record in eligible(enabledIds)) {
            val handler = record.manifest.hooks.promptHandler ?: continue
            val response = runCatching {
                invoker.invoke(
                    invocation(
                        record = record,
                        handler = handler,
                        kind = PluginInvocationKind.PROMPT_HOOK,
                        inputJson = buildJsonObject {
                            put("version", 1)
                            put("origin", request.origin.name)
                        }.toString(),
                        assistantId = request.assistantId,
                        conversationId = request.conversationId,
                        runId = request.runId,
                        enabledIds = enabledIds,
                        timeoutMs = PROMPT_TIMEOUT_MS,
                    )
                )
            }.getOrNull() ?: continue
            if (!response.ok) continue
            val raw = response.outputJson?.let(::parseAddendum)?.takeIf(String::isNotBlank)
                ?: continue
            val hash = PluginManifestValidator.pluginIdHash(record.id)
            val opening = "<plugin-addendum trust=\"untrusted\" plugin=\"$hash\">"
            val closing = "</plugin-addendum>"
            val remaining = MAX_PROMPT_ADDENDUM_CHARS -
                fragments.sumOf(String::length) -
                fragments.size.coerceAtLeast(1) - opening.length - closing.length
            if (remaining <= 0) break
            fragments += opening + xmlEscape(raw).take(remaining) + closing
            if (fragments.sumOf(String::length) + fragments.lastIndex >=
                MAX_PROMPT_ADDENDUM_CHARS
            ) break
        }
        return fragments.joinToString("\n").take(MAX_PROMPT_ADDENDUM_CHARS).ifBlank { null }
    }

    private fun eligible(enabledIds: Set<String>): List<InstalledPluginRecord> =
        registry.snapshot()
            .filter { record ->
                record.enabled && record.reviewStatus == PluginReviewStatus.APPROVED &&
                    record.id in enabledIds
            }
            .sortedBy(InstalledPluginRecord::id)

    private fun invocation(
        record: InstalledPluginRecord,
        handler: String,
        kind: PluginInvocationKind,
        inputJson: String,
        assistantId: String,
        conversationId: String,
        runId: String,
        enabledIds: Set<String>,
        timeoutMs: Long,
    ) = PluginInvocation(
        pluginId = record.id,
        handler = handler,
        kind = kind,
        inputJson = inputJson,
        assistantEnabledPluginIds = enabledIds,
        stateProjection = "{}",
        timeoutMs = timeoutMs,
        assistantId = assistantId,
        conversationId = conversationId,
        runId = runId,
        origin = ToolCallOrigin.LocalChat,
    )

    private fun interceptProjection(context: RedactedToolCallContext): JsonObject =
        buildJsonObject {
            put("version", 1)
            put("toolName", context.toolName)
            put("effects", buildJsonArray {
                context.effects.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("resourceNamespaces", buildJsonArray {
                context.resourceNamespaces.sorted().forEach { add(JsonPrimitive(it)) }
            })
            put("origin", context.origin.name)
        }

    private fun observerProjection(event: RedactedToolLifecycleEvent): JsonObject =
        buildJsonObject {
            put("version", 1)
            put("phase", event.phase.name)
            put("toolName", event.context.toolName)
            put("effects", buildJsonArray {
                event.context.effects.map { it.name }.sorted().forEach {
                    add(JsonPrimitive(it))
                }
            })
            put("origin", event.context.origin.name)
            event.terminationState?.let { put("terminationState", it.name) }
        }

    private fun parseDecision(raw: String): String? = runCatching {
        JSON.parseToJsonElement(raw).jsonObject["decision"]
            ?.jsonPrimitive?.contentOrNull?.lowercase()
    }.getOrNull()

    private fun parseAddendum(raw: String): String? = runCatching {
        JSON.parseToJsonElement(raw).jsonObject["addendum"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun interceptorFailure() = ToolHookDecision.Block(
        "An enabled plugin interceptor failed or returned an invalid decision.",
    )

    private fun xmlEscape(value: String): String = buildString(value.length.coerceAtMost(2_000)) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                }
            )
        }
    }

    private fun hasCompleteLocalIdentity(
        assistantId: String,
        conversationId: String,
        runId: String,
        origin: ToolCallOrigin,
    ): Boolean = origin == ToolCallOrigin.LocalChat &&
        assistantId.isNotBlank() && conversationId.isNotBlank() && runId.isNotBlank()

    private companion object {
        const val INTERCEPT_TIMEOUT_MS = 2_000L
        const val OBSERVER_TIMEOUT_MS = 1_500L
        const val PROMPT_TIMEOUT_MS = 2_000L
        const val MAX_PROMPT_ADDENDUM_CHARS = 2_000
        val JSON = Json { ignoreUnknownKeys = false }
    }
}
