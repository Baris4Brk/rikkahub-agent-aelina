package me.rerere.rikkahub.display

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.ToolCallOrigin

enum class DisplayCapability {
    CREATE,
    LAUNCH,
    TREE,
    SCREENSHOT,
    GESTURE,
    KEY,
}

enum class DisplaySessionLifecycle {
    ACTIVE,
    CLOSED,
    EXPIRED,
    LOST,
}

data class DisplayCaller(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    val origin: ToolCallOrigin,
)

data class DisplaySession(
    val id: String,
    val displayId: Int,
    val caller: DisplayCaller,
    val capabilities: Set<DisplayCapability>,
    val lifecycle: DisplaySessionLifecycle,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val hardExpiresAtMs: Long,
)

data class DisplayRuntimeState(
    val sessions: List<DisplaySession> = emptyList(),
)

sealed interface DisplayRequest {
    data class Create(val caller: DisplayCaller) : DisplayRequest
    data class ListOwned(val caller: DisplayCaller) : DisplayRequest
    data class Status(val caller: DisplayCaller, val sessionId: String) : DisplayRequest
    data class Resolve(
        val caller: DisplayCaller,
        val sessionId: String,
        val requiredCapability: DisplayCapability,
    ) : DisplayRequest
    data class Close(val caller: DisplayCaller, val sessionId: String) : DisplayRequest

    /** Internal lifecycle signal. Never expose this request through a model tool. */
    data class ProvisionerDied(val displayId: Int? = null) : DisplayRequest

    /** Internal emergency-stop signal. Never expose this request through a model tool. */
    data object EmergencyStop : DisplayRequest
}

sealed interface DisplayResult {
    data class Created(val session: DisplaySession) : DisplayResult
    data class Sessions(val sessions: List<DisplaySession>) : DisplayResult
    data class SessionStatus(val session: DisplaySession) : DisplayResult
    data class Resolved(val sessionId: String, val displayId: Int) : DisplayResult
    data class Closed(val sessionId: String) : DisplayResult
    data class Error(val code: String) : DisplayResult
}

interface DisplayAutomationRuntime {
    val state: StateFlow<DisplayRuntimeState>
    suspend fun dispatch(request: DisplayRequest): DisplayResult
}

data class ProvisionedDisplay(
    val displayId: Int,
    val capabilities: Set<DisplayCapability>,
)

fun interface DisplayProvisionerLifecycleListener {
    fun onProvisionerDied(displayId: Int?)
}

interface DisplayProvisioner {
    suspend fun create(): Result<ProvisionedDisplay>

    suspend fun close(displayId: Int) = Unit

    fun setLifecycleListener(listener: DisplayProvisionerLifecycleListener?) = Unit
}

class UnavailableDisplayProvisioner : DisplayProvisioner {
    override suspend fun create(): Result<ProvisionedDisplay> =
        Result.failure(IllegalStateException("display_capability_unavailable"))
}

class DefaultDisplayAutomationRuntime(
    private val provisioner: DisplayProvisioner,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = 10 * 60_000L,
    private val hardLifetimeMs: Long = 60 * 60_000L,
    private val maxActiveSessions: Int = 1,
    private val lifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DisplayAutomationRuntime {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, DisplaySession>()
    private val mutableState = MutableStateFlow(DisplayRuntimeState())
    override val state: StateFlow<DisplayRuntimeState> = mutableState.asStateFlow()

    init {
        provisioner.setLifecycleListener(DisplayProvisionerLifecycleListener { displayId ->
            lifecycleScope.launch { dispatch(DisplayRequest.ProvisionerDied(displayId)) }
        })
    }

    override suspend fun dispatch(request: DisplayRequest): DisplayResult = mutex.withLock {
        expireLocked()
        when (request) {
            is DisplayRequest.Create -> createLocked(request.caller)
            is DisplayRequest.ListOwned -> DisplayResult.Sessions(
                sessions.values.filter { it.lifecycle == DisplaySessionLifecycle.ACTIVE }
                    .filter { it.caller == request.caller }
            )
            is DisplayRequest.Status -> withOwnedSession(request.caller, request.sessionId) {
                DisplayResult.SessionStatus(it)
            }
            is DisplayRequest.Resolve -> withOwnedSession(request.caller, request.sessionId) {
                if (request.requiredCapability !in it.capabilities) {
                    DisplayResult.Error("display_capability_unavailable")
                } else if (it.displayId == PRIMARY_DISPLAY_ID) {
                    DisplayResult.Error("display_primary_forbidden")
                } else {
                    val touched = it.copy(lastUsedAtMs = nowMs())
                    sessions[it.id] = touched
                    publishLocked()
                    DisplayResult.Resolved(touched.id, touched.displayId)
                }
            }
            is DisplayRequest.Close -> closeOwnedLocked(request.caller, request.sessionId)
            is DisplayRequest.ProvisionerDied -> markProvisionerLostLocked(request.displayId)
            DisplayRequest.EmergencyStop -> closeAllLocked()
        }
    }

    private suspend fun createLocked(caller: DisplayCaller): DisplayResult {
        if (caller.assistantId.isBlank() || caller.conversationId.isBlank() || caller.runId.isBlank()) {
            return DisplayResult.Error("display_session_key_missing")
        }
        if (caller.origin != ToolCallOrigin.LocalChat &&
            caller.origin != ToolCallOrigin.SystemAssistant
        ) {
            return DisplayResult.Error("display_origin_blocked")
        }
        if (sessions.values.count { it.lifecycle == DisplaySessionLifecycle.ACTIVE } >= maxActiveSessions) {
            return DisplayResult.Error("display_capacity_reached")
        }
        val provisioned = provisioner.create().getOrElse { failure ->
            return DisplayResult.Error(
                failure.message?.takeIf { it.matches(ERROR_CODE) }
                    ?: "display_capability_unavailable"
            )
        }
        if (provisioned.displayId == PRIMARY_DISPLAY_ID || provisioned.displayId < 0) {
            runCatching { provisioner.close(provisioned.displayId) }
            return DisplayResult.Error("display_primary_forbidden")
        }
        val now = nowMs()
        val session = DisplaySession(
            id = UUID.randomUUID().toString(),
            displayId = provisioned.displayId,
            caller = caller,
            capabilities = provisioned.capabilities,
            lifecycle = DisplaySessionLifecycle.ACTIVE,
            createdAtMs = now,
            lastUsedAtMs = now,
            hardExpiresAtMs = now + hardLifetimeMs,
        )
        sessions[session.id] = session
        publishLocked()
        return DisplayResult.Created(session)
    }

    private inline fun withOwnedSession(
        caller: DisplayCaller,
        sessionId: String,
        block: (DisplaySession) -> DisplayResult,
    ): DisplayResult {
        val session = sessions[sessionId] ?: return DisplayResult.Error("display_session_not_found")
        if (session.lifecycle != DisplaySessionLifecycle.ACTIVE) {
            return DisplayResult.Error("display_session_not_active")
        }
        if (session.caller != caller) return DisplayResult.Error("display_session_owner_mismatch")
        return block(session)
    }

    private suspend fun closeOwnedLocked(caller: DisplayCaller, sessionId: String): DisplayResult {
        val session = sessions[sessionId] ?: return DisplayResult.Error("display_session_not_found")
        if (session.caller != caller) return DisplayResult.Error("display_session_owner_mismatch")
        closeSessionLocked(session, DisplaySessionLifecycle.CLOSED)
        return DisplayResult.Closed(sessionId)
    }

    private suspend fun expireLocked() {
        val now = nowMs()
        sessions.values.toList().filter { session ->
            session.lifecycle == DisplaySessionLifecycle.ACTIVE &&
                (now >= session.hardExpiresAtMs || now - session.lastUsedAtMs >= idleTimeoutMs)
        }.forEach { session ->
            closeSessionLocked(session, DisplaySessionLifecycle.EXPIRED)
        }
    }

    private suspend fun markProvisionerLostLocked(displayId: Int?): DisplayResult {
        val affected = sessions.values.toList().filter { session ->
            session.lifecycle == DisplaySessionLifecycle.ACTIVE &&
                (displayId == null || session.displayId == displayId)
        }
        affected.forEach { session ->
            sessions[session.id] = session.copy(lifecycle = DisplaySessionLifecycle.LOST)
        }
        publishLocked()
        return DisplayResult.Closed(affected.firstOrNull()?.id.orEmpty())
    }

    private suspend fun closeAllLocked(): DisplayResult {
        val active = sessions.values.toList()
            .filter { it.lifecycle == DisplaySessionLifecycle.ACTIVE }
        active.forEach { closeSessionLocked(it, DisplaySessionLifecycle.CLOSED) }
        return DisplayResult.Closed(active.firstOrNull()?.id.orEmpty())
    }

    private suspend fun closeSessionLocked(
        session: DisplaySession,
        lifecycle: DisplaySessionLifecycle,
    ) {
        runCatching { provisioner.close(session.displayId) }
        sessions[session.id] = session.copy(lifecycle = lifecycle)
        publishLocked()
    }

    private fun publishLocked() {
        mutableState.value = DisplayRuntimeState(sessions.values.toList())
    }

    private companion object {
        const val PRIMARY_DISPLAY_ID = 0
        val ERROR_CODE = Regex("[a-z0-9_]{3,80}")
    }
}
