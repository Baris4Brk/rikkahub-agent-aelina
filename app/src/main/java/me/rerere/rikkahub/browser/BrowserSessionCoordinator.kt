package me.rerere.rikkahub.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-level seam for tabs, AI task ownership and page lifecycle.
 *
 * Callers receive immutable state and opaque ids; Android WebView instances stay behind the
 * coordinator implementation. This keeps the visible tab independent from an AI-controlled tab
 * and gives tools one ownership check instead of a process-global "active WebView" convention.
 */
interface BrowserSessionCoordinator {
    val state: StateFlow<BrowserRuntimeState>
    suspend fun dispatch(request: BrowserRequest): BrowserResult
}

@JvmInline value class BrowserPageId(val value: String)
@JvmInline value class BrowserTaskLeaseId(val value: String)

enum class BrowserCallOrigin {
    USER,
    LOCAL_CHAT,
    SYSTEM_ASSISTANT,
    TELEGRAM,
    WEB_API,
    CRON,
    WORKFLOW,
    RESEARCH,
}

enum class BrowserProfileClass { LOCAL_SHARED, REMOTE_EPHEMERAL, RESEARCH_EPHEMERAL }
enum class BrowserPageLifecycle { LIVE_VISIBLE, LIVE_SILENT, PAUSED, HIBERNATED, CRASHED }
enum class BrowserPageKind { WEB, LOCAL_SKILL }

data class BrowserCaller(
    val assistantId: String?,
    val conversationId: String?,
    val runId: String?,
    val origin: BrowserCallOrigin,
) {
    val isUser: Boolean get() = origin == BrowserCallOrigin.USER

    companion object {
        fun localUser() = BrowserCaller(null, null, null, BrowserCallOrigin.USER)
    }
}

data class BrowserPageState(
    val id: BrowserPageId,
    val url: String,
    val title: String = "",
    val lifecycle: BrowserPageLifecycle,
    val profileClass: BrowserProfileClass,
    val kind: BrowserPageKind = BrowserPageKind.WEB,
    val desktopMode: Boolean = false,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val taskLeaseId: BrowserTaskLeaseId? = null,
)

data class BrowserTaskState(
    val id: BrowserTaskLeaseId,
    val caller: BrowserCaller,
    val controlledPageId: BrowserPageId,
    val running: Boolean,
    val completedAtMs: Long? = null,
)

data class BrowserRuntimeState(
    val pages: List<BrowserPageState> = emptyList(),
    val tasks: List<BrowserTaskState> = emptyList(),
    val uiSelectedPageId: BrowserPageId? = null,
)

sealed interface BrowserRequest {
    data class NewPage(val caller: BrowserCaller, val url: String = "about:blank") : BrowserRequest
    data class SelectPage(val caller: BrowserCaller, val pageId: BrowserPageId) : BrowserRequest
    data class ClosePage(val caller: BrowserCaller, val pageId: BrowserPageId) : BrowserRequest
    data class StartTask(val caller: BrowserCaller, val url: String) : BrowserRequest
    data class Navigate(
        val caller: BrowserCaller,
        val leaseId: BrowserTaskLeaseId,
        val pageId: BrowserPageId,
        val url: String,
    ) : BrowserRequest
    data class FinishTask(val caller: BrowserCaller, val leaseId: BrowserTaskLeaseId) : BrowserRequest
    data object HibernateIdle : BrowserRequest
}

enum class BrowserError {
    PAGE_NOT_FOUND,
    PAGE_NOT_OWNED,
    TASK_NOT_FOUND,
    TASK_CALLER_MISMATCH,
    TASK_RUNNING,
}

sealed interface BrowserResult {
    data class PageCreated(val pageId: BrowserPageId) : BrowserResult
    data class LeaseCreated(val leaseId: BrowserTaskLeaseId, val pageId: BrowserPageId) : BrowserResult
    data class StateChanged(val state: BrowserRuntimeState) : BrowserResult
    data class Error(val code: BrowserError, val detail: String) : BrowserResult
}

/**
 * Deterministic state implementation used by JVM tests and as the policy core of the Android
 * adapter. It deliberately has no Android dependency; WebView creation and attachment are driven
 * from successful results by the Android adapter.
 */
class InMemoryBrowserSessionCoordinator(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val completedPageIdleMs: Long = 10 * 60 * 1_000L,
) : BrowserSessionCoordinator {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(BrowserRuntimeState())
    override val state: StateFlow<BrowserRuntimeState> = mutableState.asStateFlow()
    private var nextPageId = 1L
    private var nextLeaseId = 1L

    override suspend fun dispatch(request: BrowserRequest): BrowserResult = mutex.withLock {
        when (request) {
            is BrowserRequest.NewPage -> newPage(request.caller, request.url, selectForUser = true)
            is BrowserRequest.SelectPage -> selectPage(request)
            is BrowserRequest.ClosePage -> closePage(request)
            is BrowserRequest.StartTask -> startTask(request)
            is BrowserRequest.Navigate -> navigate(request)
            is BrowserRequest.FinishTask -> finishTask(request)
            BrowserRequest.HibernateIdle -> hibernateIdle()
        }
    }

    private fun newPage(
        caller: BrowserCaller,
        url: String,
        selectForUser: Boolean,
        leaseId: BrowserTaskLeaseId? = null,
    ): BrowserResult.PageCreated {
        val now = nowMs()
        val pageId = BrowserPageId("page-${nextPageId++}")
        val profile = profileFor(caller.origin)
        val shouldSelect = caller.isUser && selectForUser
        val page = BrowserPageState(
            id = pageId,
            url = url,
            lifecycle = if (shouldSelect) BrowserPageLifecycle.LIVE_VISIBLE else BrowserPageLifecycle.LIVE_SILENT,
            profileClass = profile,
            createdAtMs = now,
            lastUsedAtMs = now,
            taskLeaseId = leaseId,
        )
        val pages = if (shouldSelect) {
            mutableState.value.pages.map { existing ->
                if (existing.lifecycle == BrowserPageLifecycle.LIVE_VISIBLE) {
                    existing.copy(lifecycle = BrowserPageLifecycle.PAUSED)
                } else existing
            } + page
        } else mutableState.value.pages + page
        mutableState.value = mutableState.value.copy(
            pages = pages,
            uiSelectedPageId = if (shouldSelect) pageId else mutableState.value.uiSelectedPageId,
        )
        return BrowserResult.PageCreated(pageId)
    }

    private fun selectPage(request: BrowserRequest.SelectPage): BrowserResult {
        if (!request.caller.isUser) {
            return BrowserResult.Error(BrowserError.PAGE_NOT_OWNED, "Only the foreground user selects the visible page")
        }
        if (mutableState.value.pages.none { it.id == request.pageId }) {
            return BrowserResult.Error(BrowserError.PAGE_NOT_FOUND, "Unknown page ${request.pageId.value}")
        }
        val now = nowMs()
        mutableState.value = mutableState.value.copy(
            pages = mutableState.value.pages.map { page ->
                when {
                    page.id == request.pageId -> page.copy(
                        lifecycle = BrowserPageLifecycle.LIVE_VISIBLE,
                        lastUsedAtMs = now,
                    )
                    page.lifecycle == BrowserPageLifecycle.LIVE_VISIBLE -> page.copy(
                        lifecycle = if (page.taskLeaseId != null) BrowserPageLifecycle.LIVE_SILENT else BrowserPageLifecycle.PAUSED,
                    )
                    else -> page
                }
            },
            uiSelectedPageId = request.pageId,
        )
        return BrowserResult.StateChanged(mutableState.value)
    }

    private fun closePage(request: BrowserRequest.ClosePage): BrowserResult {
        val state = mutableState.value
        val index = state.pages.indexOfFirst { it.id == request.pageId }
        if (index < 0) return BrowserResult.Error(BrowserError.PAGE_NOT_FOUND, "Unknown page ${request.pageId.value}")
        val task = state.tasks.firstOrNull { it.controlledPageId == request.pageId && it.running }
        if (task != null && task.caller != request.caller) {
            return BrowserResult.Error(BrowserError.TASK_RUNNING, "The page is controlled by a running AI task")
        }
        var remaining = state.pages.filterNot { it.id == request.pageId }
        var selected = state.uiSelectedPageId
        if (selected == request.pageId) {
            selected = remaining.getOrNull(index)?.id ?: remaining.getOrNull(index - 1)?.id
        }
        if (remaining.isEmpty()) {
            mutableState.value = state.copy(
                pages = emptyList(),
                tasks = state.tasks.filterNot { it.controlledPageId == request.pageId },
                uiSelectedPageId = null,
            )
            return newPage(BrowserCaller.localUser(), "about:blank", selectForUser = true)
        }
        remaining = remaining.map { page ->
            if (page.id == selected) page.copy(lifecycle = BrowserPageLifecycle.LIVE_VISIBLE)
            else page
        }
        mutableState.value = state.copy(
            pages = remaining,
            tasks = state.tasks.filterNot { it.controlledPageId == request.pageId },
            uiSelectedPageId = selected,
        )
        return BrowserResult.StateChanged(mutableState.value)
    }

    private fun startTask(request: BrowserRequest.StartTask): BrowserResult {
        if (request.caller.conversationId.isNullOrBlank() || request.caller.runId.isNullOrBlank()) {
            return BrowserResult.Error(BrowserError.TASK_CALLER_MISMATCH, "AI browser tasks require conversationId and runId")
        }
        val leaseId = BrowserTaskLeaseId("lease-${nextLeaseId++}")
        val pageId = newPage(request.caller, request.url, selectForUser = false, leaseId = leaseId).pageId
        mutableState.value = mutableState.value.copy(
            tasks = mutableState.value.tasks + BrowserTaskState(
                id = leaseId,
                caller = request.caller,
                controlledPageId = pageId,
                running = true,
            ),
        )
        return BrowserResult.LeaseCreated(leaseId, pageId)
    }

    private fun navigate(request: BrowserRequest.Navigate): BrowserResult {
        val task = mutableState.value.tasks.firstOrNull { it.id == request.leaseId }
            ?: return BrowserResult.Error(BrowserError.TASK_NOT_FOUND, "Unknown lease ${request.leaseId.value}")
        if (task.caller != request.caller) {
            return BrowserResult.Error(BrowserError.TASK_CALLER_MISMATCH, "The lease belongs to another caller")
        }
        if (task.controlledPageId != request.pageId) {
            return BrowserResult.Error(BrowserError.PAGE_NOT_OWNED, "The lease does not control ${request.pageId.value}")
        }
        val now = nowMs()
        mutableState.value = mutableState.value.copy(
            pages = mutableState.value.pages.map { page ->
                if (page.id == request.pageId) page.copy(url = request.url, lastUsedAtMs = now) else page
            },
        )
        return BrowserResult.StateChanged(mutableState.value)
    }

    private fun finishTask(request: BrowserRequest.FinishTask): BrowserResult {
        val task = mutableState.value.tasks.firstOrNull { it.id == request.leaseId }
            ?: return BrowserResult.Error(BrowserError.TASK_NOT_FOUND, "Unknown lease ${request.leaseId.value}")
        if (task.caller != request.caller) {
            return BrowserResult.Error(BrowserError.TASK_CALLER_MISMATCH, "The lease belongs to another caller")
        }
        val now = nowMs()
        mutableState.value = mutableState.value.copy(
            tasks = mutableState.value.tasks.map {
                if (it.id == request.leaseId) it.copy(running = false, completedAtMs = now) else it
            },
            pages = mutableState.value.pages.map {
                if (it.id == task.controlledPageId) it.copy(lastUsedAtMs = now) else it
            },
        )
        return BrowserResult.StateChanged(mutableState.value)
    }

    private fun hibernateIdle(): BrowserResult {
        val now = nowMs()
        val completedPages = mutableState.value.tasks
            .filter { !it.running && it.completedAtMs != null && now - it.completedAtMs > completedPageIdleMs }
            .mapTo(mutableSetOf()) { it.controlledPageId }
        mutableState.value = mutableState.value.copy(
            pages = mutableState.value.pages.map { page ->
                if (page.id in completedPages && page.id != mutableState.value.uiSelectedPageId) {
                    page.copy(lifecycle = BrowserPageLifecycle.HIBERNATED)
                } else page
            },
        )
        return BrowserResult.StateChanged(mutableState.value)
    }

    private fun profileFor(origin: BrowserCallOrigin): BrowserProfileClass = when (origin) {
        BrowserCallOrigin.USER,
        BrowserCallOrigin.LOCAL_CHAT,
        BrowserCallOrigin.SYSTEM_ASSISTANT,
        -> BrowserProfileClass.LOCAL_SHARED
        BrowserCallOrigin.RESEARCH -> BrowserProfileClass.RESEARCH_EPHEMERAL
        BrowserCallOrigin.TELEGRAM,
        BrowserCallOrigin.WEB_API,
        BrowserCallOrigin.CRON,
        BrowserCallOrigin.WORKFLOW,
        -> BrowserProfileClass.REMOTE_EPHEMERAL
    }
}
