package me.rerere.rikkahub.research

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.subagent.SubAgentCallerContext
import me.rerere.rikkahub.subagent.SubAgentEngine
import me.rerere.rikkahub.subagent.SubAgentParentCompletionPolicy
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRequest
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import kotlin.uuid.Uuid

val RESEARCH_READ_ONLY_TOOLS: Set<String> = linkedSetOf(
    "search_web",
    "scrape_web",
    "web_fetch",
)

private const val RESEARCH_MIN_SUBTASKS = 2
private const val RESEARCH_MAX_SUBTASKS = 5
private const val RESEARCH_RESULT_MAX_CHARS = 8_000

private val RESEARCH_CHILD_SYSTEM_PROMPT = """
    You are one worker in a bounded research plan. Investigate only the assigned subtask using
    the provided read-only web tools. Never perform device, file-write, browser-interaction, or
    configuration actions. End with compact JSON containing: summary, claims (each with claim,
    sources, and high|medium|low confidence), and open_questions. Cite source URLs, do not copy a
    full page, and state uncertainty explicitly.
""".trimIndent()

data class ResearchSubtask(
    val label: String,
    val task: String,
)

data class ResearchStartRequest(
    val topic: String,
    val subtasks: List<ResearchSubtask>,
    val modelId: String? = null,
    val maxTrips: Int = 8,
    val timeoutSeconds: Int = 300,
)

enum class ResearchStatus {
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
}

data class ResearchChild(
    val label: String,
    val task: String,
    val subAgentRunId: String?,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
)

data class ResearchRun(
    val id: String,
    val ownerAssistantId: String,
    val parentConversationId: String,
    val topic: String,
    val children: List<ResearchChild>,
    val status: ResearchStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
)

sealed interface ResearchStartResult {
    data class Started(val run: ResearchRun) : ResearchStartResult
    data class Rejected(val error: String, val detail: String) : ResearchStartResult
}

sealed interface ResearchChildDispatchResult {
    data class Started(val runId: String) : ResearchChildDispatchResult
    data class Rejected(val error: String, val detail: String) : ResearchChildDispatchResult
}

interface ResearchChildGateway {
    suspend fun dispatch(
        caller: SubAgentCallerContext,
        request: SubAgentRequest,
    ): ResearchChildDispatchResult

    suspend fun awaitTerminal(runIds: Set<String>): Map<String, SubAgentRun>

    fun cancel(ownerAssistantId: String, runId: String): Boolean
}

fun interface ResearchCompletionNotifier {
    suspend fun notify(run: ResearchRun)
}

class ResearchCoordinator(
    private val childGateway: ResearchChildGateway,
    private val scope: CoroutineScope,
    private val completionNotifier: ResearchCompletionNotifier,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val runs = ConcurrentHashMap<String, ResearchRun>()
    private val stateLock = Any()
    @Volatile
    private var acceptingNewRuns = true

    suspend fun start(
        caller: SubAgentCallerContext,
        request: ResearchStartRequest,
    ): ResearchStartResult {
        validate(caller, request)?.let { return it }
        val parentConversationId = caller.parentConversationId!!
        val runId = Uuid.random().toString()
        val initial = ResearchRun(
            id = runId,
            ownerAssistantId = caller.parentAssistantId,
            parentConversationId = parentConversationId,
            topic = request.topic.trim(),
            children = emptyList(),
            status = ResearchStatus.RUNNING,
            startedAtMs = nowMs(),
        )
        synchronized(stateLock) {
            if (!acceptingNewRuns) {
                return ResearchStartResult.Rejected(
                    "emergency_stop_active",
                    "new research runs are paused by Emergency Stop",
                )
            }
            runs[runId] = initial
        }

        val childCaller = caller.copy(
            completionPolicy = SubAgentParentCompletionPolicy.COORDINATOR_ONLY,
        )
        val children = mutableListOf<ResearchChild>()
        try {
            request.subtasks.forEach { subtask ->
                val childRequest = SubAgentRequest(
                    task = subtask.task.trim(),
                    label = subtask.label.trim(),
                    modelId = request.modelId,
                    systemPrompt = RESEARCH_CHILD_SYSTEM_PROMPT,
                    tools = RESEARCH_READ_ONLY_TOOLS.toList(),
                    runInBackground = true,
                    timeoutSeconds = request.timeoutSeconds,
                    maxTrips = request.maxTrips,
                )
                if (isCancelled(runId)) {
                    children += ResearchChild(
                        label = childRequest.label.orEmpty(),
                        task = childRequest.task,
                        subAgentRunId = null,
                        status = SubAgentStatus.CANCELLED,
                        error = "research_cancelled",
                    )
                    return@forEach
                }
                val child = when (val dispatched = childGateway.dispatch(childCaller, childRequest)) {
                    is ResearchChildDispatchResult.Started -> ResearchChild(
                        label = childRequest.label.orEmpty(),
                        task = childRequest.task,
                        subAgentRunId = dispatched.runId,
                        status = SubAgentStatus.RUNNING,
                    )
                    is ResearchChildDispatchResult.Rejected -> ResearchChild(
                        label = childRequest.label.orEmpty(),
                        task = childRequest.task,
                        subAgentRunId = null,
                        status = SubAgentStatus.FAILED,
                        error = "${dispatched.error}: ${dispatched.detail}",
                    )
                }
                children += if (isCancelled(runId) && child.subAgentRunId != null) {
                    childGateway.cancel(initial.ownerAssistantId, child.subAgentRunId)
                    child.copy(status = SubAgentStatus.CANCELLED, error = "research_cancelled")
                } else {
                    child
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val cancelledChildren = children.map { child ->
                    if (child.status == SubAgentStatus.RUNNING || child.status == SubAgentStatus.PENDING) {
                        child.copy(status = SubAgentStatus.CANCELLED, error = "research_cancelled")
                    } else {
                        child
                    }
                }
                synchronized(stateLock) {
                    val current = runs[runId] ?: initial
                    runs[runId] = current.copy(
                        children = cancelledChildren,
                        status = ResearchStatus.CANCELLED,
                        finishedAtMs = current.finishedAtMs ?: nowMs(),
                    )
                }
                cancelledChildren.mapNotNull { it.subAgentRunId }.forEach { childId ->
                    runCatching { childGateway.cancel(initial.ownerAssistantId, childId) }
                }
            }
            throw cancelled
        }
        val dispatchedRun = synchronized(stateLock) {
            val cancelled = runs[runId]?.status == ResearchStatus.CANCELLED
            initial.copy(
                children = if (cancelled) {
                    children.map { child ->
                        if (child.status == SubAgentStatus.RUNNING || child.status == SubAgentStatus.PENDING) {
                            child.copy(status = SubAgentStatus.CANCELLED, error = "research_cancelled")
                        } else {
                            child
                        }
                    }
                } else {
                    children
                },
                status = if (cancelled) ResearchStatus.CANCELLED else ResearchStatus.RUNNING,
                finishedAtMs = if (cancelled) runs[runId]?.finishedAtMs ?: nowMs() else null,
            ).also { runs[runId] = it }
        }
        if (dispatchedRun.status == ResearchStatus.CANCELLED) {
            dispatchedRun.children.mapNotNull { it.subAgentRunId }.forEach { childId ->
                childGateway.cancel(initial.ownerAssistantId, childId)
            }
            return ResearchStartResult.Started(dispatchedRun)
        }
        val childIds = children.mapNotNull { it.subAgentRunId }.toSet()
        if (childIds.isEmpty()) {
            val failed = dispatchedRun.copy(
                status = ResearchStatus.FAILED,
                finishedAtMs = nowMs(),
            )
            synchronized(stateLock) { runs[runId] = failed }
            completionNotifier.notify(failed)
            return ResearchStartResult.Started(failed)
        }

        scope.launch {
            val terminal = childGateway.awaitTerminal(childIds)
            complete(runId, terminal)
        }
        return ResearchStartResult.Started(dispatchedRun)
    }

    fun status(ownerAssistantId: String, runId: String): ResearchRun? =
        runs[runId]?.takeIf { it.ownerAssistantId == ownerAssistantId }

    fun list(ownerAssistantId: String): List<ResearchRun> = runs.values
        .filter { it.ownerAssistantId == ownerAssistantId }
        .sortedByDescending { it.startedAtMs }

    fun cancel(ownerAssistantId: String, runId: String): Int {
        val cancellation = synchronized(stateLock) {
            val run = runs[runId]?.takeIf { it.ownerAssistantId == ownerAssistantId }
                ?: return 0
            if (run.status != ResearchStatus.RUNNING) return 0
            val childIds = run.children.mapNotNull { it.subAgentRunId }
            runs[runId] = run.copy(
                status = ResearchStatus.CANCELLED,
                finishedAtMs = nowMs(),
                children = run.children.map { child ->
                    if (child.status == SubAgentStatus.RUNNING || child.status == SubAgentStatus.PENDING) {
                        child.copy(status = SubAgentStatus.CANCELLED, error = "research_cancelled")
                    } else {
                        child
                    }
                },
            )
            childIds
        }
        var cancelled = 0
        cancellation.forEach { childId ->
            if (childGateway.cancel(ownerAssistantId, childId)) cancelled++
        }
        return cancelled
    }

    /** Pauses new work and cancels every in-memory research graph for Emergency Stop. */
    fun cancelAllActive(): Int {
        val active = synchronized(stateLock) {
            acceptingNewRuns = false
            runs.values.filter { it.status == ResearchStatus.RUNNING }
        }
        active.forEach { run -> cancel(run.ownerAssistantId, run.id) }
        return active.size
    }

    fun resumeNewRuns() {
        acceptingNewRuns = true
    }

    private suspend fun complete(
        runId: String,
        terminalRuns: Map<String, SubAgentRun>,
    ) {
        val current = synchronized(stateLock) {
            runs[runId]?.takeUnless { it.status == ResearchStatus.CANCELLED }
        } ?: return
        val children = current.children.map { child ->
            val childId = child.subAgentRunId ?: return@map child
            val terminal = terminalRuns[childId]
            if (terminal == null) {
                child.copy(status = SubAgentStatus.FAILED, error = "child_run_missing")
            } else {
                child.copy(
                    status = terminal.status,
                    result = terminal.result?.take(RESEARCH_RESULT_MAX_CHARS),
                    error = terminal.error?.take(500),
                )
            }
        }
        val successCount = children.count { it.status == SubAgentStatus.SUCCEEDED }
        val status = when {
            children.any { it.status == SubAgentStatus.CANCELLED } -> ResearchStatus.CANCELLED
            successCount == children.size -> ResearchStatus.SUCCEEDED
            successCount > 0 -> ResearchStatus.PARTIAL
            else -> ResearchStatus.FAILED
        }
        val finished = current.copy(
            children = children,
            status = status,
            finishedAtMs = nowMs(),
        )
        synchronized(stateLock) {
            if (runs[runId]?.status == ResearchStatus.CANCELLED) return
            runs[runId] = finished
        }
        if (finished.status != ResearchStatus.CANCELLED) {
            completionNotifier.notify(finished)
        }
    }

    private fun isCancelled(runId: String): Boolean =
        runs[runId]?.status == ResearchStatus.CANCELLED

    private fun validate(
        caller: SubAgentCallerContext,
        request: ResearchStartRequest,
    ): ResearchStartResult.Rejected? {
        if (caller.parentAssistantId.isBlank() || caller.parentConversationId.isNullOrBlank()) {
            return ResearchStartResult.Rejected("missing_owner", "assistant and conversation are required")
        }
        if (request.topic.isBlank()) {
            return ResearchStartResult.Rejected("invalid_topic", "topic may not be blank")
        }
        if (request.subtasks.size !in RESEARCH_MIN_SUBTASKS..RESEARCH_MAX_SUBTASKS) {
            return ResearchStartResult.Rejected(
                "invalid_subtask_count",
                "research requires $RESEARCH_MIN_SUBTASKS-$RESEARCH_MAX_SUBTASKS subtasks",
            )
        }
        if (request.subtasks.any { it.label.isBlank() || it.task.isBlank() }) {
            return ResearchStartResult.Rejected(
                "invalid_subtask",
                "every subtask requires a non-blank label and task",
            )
        }
        val unavailable = RESEARCH_READ_ONLY_TOOLS - caller.toolNames.available
        if (unavailable.isNotEmpty()) {
            return ResearchStartResult.Rejected(
                "research_tools_unavailable",
                "enable these read-only tools for the parent assistant: ${unavailable.sorted().joinToString()}",
            )
        }
        if (request.maxTrips !in 1..30 || request.timeoutSeconds !in 1..1_800) {
            return ResearchStartResult.Rejected(
                "invalid_limits",
                "max_trips or timeout_seconds is outside the sub-agent limits",
            )
        }
        return null
    }
}

class SubAgentResearchChildGateway(
    private val engine: SubAgentEngine,
    private val registry: SubAgentRegistry,
) : ResearchChildGateway {
    override suspend fun dispatch(
        caller: SubAgentCallerContext,
        request: SubAgentRequest,
    ): ResearchChildDispatchResult = when (val result = engine.dispatch(caller, request)) {
        is SubAgentEngine.DispatchResult.Ok -> ResearchChildDispatchResult.Started(result.run.id)
        is SubAgentEngine.DispatchResult.Reject ->
            ResearchChildDispatchResult.Rejected(result.error, result.detail)
    }

    override suspend fun awaitTerminal(runIds: Set<String>): Map<String, SubAgentRun> =
        registry.runs
            .map { runs -> runIds.mapNotNull { id -> runs[id]?.let { id to it } }.toMap() }
            .first { runs ->
                runs.size == runIds.size && runs.values.all { it.status.isTerminal }
            }

    override fun cancel(ownerAssistantId: String, runId: String): Boolean =
        registry.requestCancelForAssistant(runId, ownerAssistantId)
}

private val SubAgentStatus.isTerminal: Boolean
    get() = this != SubAgentStatus.PENDING && this != SubAgentStatus.RUNNING
