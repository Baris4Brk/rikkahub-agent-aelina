package me.rerere.rikkahub.research

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
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
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.subagent.toSubAgentCallerContext
import kotlin.uuid.Uuid

private const val TAG = "DeepResearch"

private fun researchError(error: String, detail: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("error", error)
        put("detail", detail)
    }.toString()),
)

private fun encodeResearchRun(
    run: ResearchRun,
    includeResults: Boolean,
) = buildJsonObject {
    put("id", run.id)
    put("topic", run.topic)
    put("status", run.status.name)
    put("started_at_ms", run.startedAtMs)
    run.finishedAtMs?.let { put("finished_at_ms", it) }
    put("children", buildJsonArray {
        run.children.forEach { child ->
            addJsonObject {
                put("label", child.label)
                put("status", child.status.name)
                child.subAgentRunId?.let { put("subagent_run_id", it) }
                child.error?.let { put("error", it) }
                if (includeResults) child.result?.let { put("result", it.take(4_000)) }
            }
        }
    })
}

fun researchStartTool(
    coordinator: ResearchCoordinator,
    invocationContext: ToolInvocationContext,
): Tool = Tool(
    name = "research_start",
    description = """
        Start a bounded deep-research plan with 2-5 independent subtasks. Every worker is
        restricted to search_web, scrape_web, and web_fetch; no browser interaction, device
        action, file mutation, or recursive delegation is available. Returns immediately with a
        research id. Use research_status to inspect progress and research_cancel to stop it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("topic", buildJsonObject { put("type", "string") })
                put("subtasks", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("label", buildJsonObject { put("type", "string") })
                            put("task", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("label"))
                            add(kotlinx.serialization.json.JsonPrimitive("task"))
                        })
                    })
                })
                put("model_id", buildJsonObject { put("type", "string") })
                put("max_trips", buildJsonObject { put("type", "integer") })
                put("timeout_seconds", buildJsonObject { put("type", "integer") })
            },
            required = listOf("topic", "subtasks"),
        )
    },
    needsApproval = { true },
    execute = { args ->
        val params = args.jsonObject
        val topic = params["topic"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool researchError("invalid_topic", "topic is required")
        val rawSubtasks = params["subtasks"] as? JsonArray
            ?: return@Tool researchError("invalid_subtasks", "subtasks must be an array")
        val subtasks = arrayListOf<ResearchSubtask>()
        rawSubtasks.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
                ?: return@Tool researchError(
                    "invalid_subtask",
                    "subtask $index must be an object",
                )
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool researchError("invalid_subtask", "subtask $index needs label")
            val task = obj["task"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool researchError("invalid_subtask", "subtask $index needs task")
            subtasks += ResearchSubtask(label, task)
        }
        when (val result = coordinator.start(
            caller = invocationContext.toSubAgentCallerContext(),
            request = ResearchStartRequest(
                topic = topic,
                subtasks = subtasks,
                modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
                maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull ?: 8,
                timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 300,
            ),
        )) {
            is ResearchStartResult.Rejected -> researchError(result.error, result.detail)
            is ResearchStartResult.Started ->
                listOf(UIMessagePart.Text(encodeResearchRun(result.run, false).toString()))
        }
    },
)

fun researchStatusTool(
    coordinator: ResearchCoordinator,
    invocationContext: ToolInvocationContext,
): Tool = Tool(
    name = "research_status",
    description = "Return one owner-scoped in-memory research run and its compact child results.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool researchError("invalid_id", "id is required")
        val owner = invocationContext.callerAssistantId
            ?: return@Tool researchError("missing_owner", "caller assistant is required")
        val run = coordinator.status(owner, id)
            ?: return@Tool researchError("unknown_id", "research run is not visible to this assistant")
        listOf(UIMessagePart.Text(encodeResearchRun(run, true).toString()))
    },
)

fun researchCancelTool(
    coordinator: ResearchCoordinator,
    invocationContext: ToolInvocationContext,
): Tool = Tool(
    name = "research_cancel",
    description = "Cancel every active child in one owner-scoped research run.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool researchError("invalid_id", "id is required")
        val owner = invocationContext.callerAssistantId
            ?: return@Tool researchError("missing_owner", "caller assistant is required")
        val cancelled = coordinator.cancel(owner, id)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("id", id)
            put("cancelled_children", cancelled)
        }.toString()))
    },
)

internal fun buildResearchCompletionMessage(run: ResearchRun): String {
    val sources = linkedSetOf<String>()
    val urlRegex = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
    run.children.forEach { child ->
        child.result.orEmpty().let { result ->
            urlRegex.findAll(result).forEach { match ->
                sources += match.value.trimEnd('.', ',', ';', ')', ']', '}')
            }
        }
    }
    val payload = buildJsonObject {
        put("research_id", run.id)
        put("topic", run.topic)
        put("status", run.status.name)
        put("source_urls", buildJsonArray {
            sources.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        })
        put("subtasks", buildJsonArray {
            run.children.forEach { child ->
                addJsonObject {
                    put("label", child.label)
                    put("status", child.status.name)
                    child.result?.let { put("report", it.take(4_000)) }
                    child.error?.let { put("error", it) }
                }
            }
        })
    }
    return "[Deep research coordinator completed]\n$payload"
}

/** Lazy ChatService adapter keeps the existing ChatService -> LocalTools graph acyclic. */
class ChatResearchCompletionNotifier : ResearchCompletionNotifier {
    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    override suspend fun notify(run: ResearchRun) {
        val conversationId = runCatching { Uuid.parse(run.parentConversationId) }.getOrNull()
            ?: return
        if (HeadlessConversations.isHeadless(conversationId)) return
        runCatching {
            withTimeoutOrNull(5 * 60_000L) {
                chatService.getGenerationJobStateFlow(conversationId).first { it == null }
            }
            chatService.sendMessage(
                conversationId,
                listOf(UIMessagePart.Text(buildResearchCompletionMessage(run))),
            )
        }.onFailure { error ->
            Log.w(TAG, "failed to notify parent of research ${run.id}", error)
        }
    }
}
