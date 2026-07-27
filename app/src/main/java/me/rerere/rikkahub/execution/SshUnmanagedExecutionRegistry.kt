package me.rerere.rikkahub.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import kotlin.time.Duration

internal data class SshUnmanagedOwner(
    val assistantId: String,
    val conversationId: String,
    val origin: me.rerere.rikkahub.data.ai.ToolCallOrigin,
) {
    fun caller(runId: String) = ManagedExecutionCaller(
        assistantId = assistantId,
        conversationId = conversationId,
        runId = runId,
        origin = origin,
        allowedRuntimes = setOf(ManagedExecutionRuntime.SSH),
    )

    fun matches(caller: ManagedExecutionCaller): Boolean =
        assistantId == caller.assistantId &&
            conversationId == caller.conversationId &&
            origin == caller.origin
}

/** Process-memory-only ownership for temporary-credential SSH tasks. No credential is persisted. */
class SshUnmanagedExecutionRegistry(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val executionId: String,
        val owner: SshUnmanagedOwner,
        val started: StartedSshExecution,
        val startedAtMs: Long,
        val status: AtomicReference<ManagedExecutionStatus> =
            AtomicReference(ManagedExecutionStatus.RUNNING),
        val exitCode: AtomicReference<Int?> = AtomicReference(null),
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    internal fun register(
        executionId: String,
        owner: SshUnmanagedOwner,
        started: StartedSshExecution,
    ) {
        pruneTerminalEntries()
        val entry = Entry(executionId, owner, started, nowMs())
        entries[executionId] = entry
        scope.launch {
            val result = runCatching { started.result.await() }
            val success = result.getOrNull()?.toolSucceeded() == true
            entry.status.updateAndGet { current ->
                when (current) {
                    ManagedExecutionStatus.STOPPED -> current
                    ManagedExecutionStatus.STOP_REQUESTED -> ManagedExecutionStatus.EXITED
                    else -> if (success) ManagedExecutionStatus.EXITED else ManagedExecutionStatus.FAILED
                }
            }
            entry.exitCode.set(if (success) 0 else null)
        }
    }

    internal fun list(caller: ManagedExecutionCaller, includeStopped: Boolean): List<ManagedExecutionSnapshot> =
        entries.values.asSequence()
            .filter { it.owner.matches(caller) }
            .map(::snapshot)
            .filter { includeStopped || it.alive }
            .sortedBy(ManagedExecutionSnapshot::executionId)
            .toList()

    internal fun status(caller: ManagedExecutionCaller, executionId: String): ManagedExecutionResult {
        val entry = entries[executionId]
            ?: return ManagedExecutionResult.Error("execution_unsupported")
        if (!entry.owner.matches(caller)) return ManagedExecutionResult.Error("execution_not_found")
        return ManagedExecutionResult.Snapshot(snapshot(entry))
    }

    internal suspend fun stop(
        caller: ManagedExecutionCaller,
        executionId: String,
        force: Boolean,
    ): ManagedExecutionResult {
        val entry = entries[executionId]
            ?: return ManagedExecutionResult.Error("execution_unsupported")
        if (!entry.owner.matches(caller)) return ManagedExecutionResult.Error("execution_not_found")
        if (!snapshot(entry).alive) return ManagedExecutionResult.Stopped(snapshot(entry))
        entry.status.set(ManagedExecutionStatus.STOP_REQUESTED)
        val requested = runCatching {
            entry.started.hooks.terminateRemoteProcessGroup(force)
        }.getOrDefault(false)
        val confirmed = requested && withTimeoutOrNull(STOP_CONFIRM_TIMEOUT_MS) {
            entry.started.hooks.awaitRemoteExit()
        } == true
        return if (confirmed) {
            entry.status.set(ManagedExecutionStatus.STOPPED)
            ManagedExecutionResult.Stopped(snapshot(entry))
        } else {
            if (force) entry.status.set(ManagedExecutionStatus.UNKNOWN)
            ManagedExecutionResult.Snapshot(snapshot(entry, terminationUncertain = force))
        }
    }

    internal suspend fun emergencyStop(): List<ManagedExecutionSnapshot> = entries.values.map { entry ->
        when (val result = stop(entry.owner.caller("emergency-stop"), entry.executionId, force = true)) {
            is ManagedExecutionResult.Stopped -> result.execution
            is ManagedExecutionResult.Snapshot -> result.execution
            else -> snapshot(entry, terminationUncertain = true)
        }
    }

    private fun snapshot(
        entry: Entry,
        terminationUncertain: Boolean = entry.status.get() == ManagedExecutionStatus.UNKNOWN,
    ): ManagedExecutionSnapshot {
        val status = entry.status.get()
        return ManagedExecutionSnapshot(
            executionId = entry.executionId,
            runtime = ManagedExecutionRuntime.SSH,
            name = "temporary SSH task",
            status = status,
            alive = status in setOf(
                ManagedExecutionStatus.STARTING,
                ManagedExecutionStatus.RUNNING,
                ManagedExecutionStatus.STOP_REQUESTED,
                ManagedExecutionStatus.RECOVERING,
                ManagedExecutionStatus.UNKNOWN,
            ),
            startedAtMs = entry.startedAtMs,
            runtimeInstanceMarker = entry.started.identity.processStartTicks.toString(),
            lastExitCode = entry.exitCode.get(),
            terminationUncertain = terminationUncertain,
        )
    }

    private fun pruneTerminalEntries() {
        if (entries.size < MAX_ENTRIES) return
        entries.values
            .filter { !snapshot(it).alive }
            .sortedBy(Entry::startedAtMs)
            .take((entries.size - MAX_ENTRIES + 1).coerceAtLeast(1))
            .forEach { entries.remove(it.executionId, it) }
    }

    private fun ToolResult.toolSucceeded(): Boolean = asSequence()
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            runCatching {
                Json.parseToJsonElement(part.text).jsonObject["success"]
                    ?.jsonPrimitive?.booleanOrNull
            }.getOrNull()
        }
        .firstOrNull() == true

    private companion object {
        const val MAX_ENTRIES = 64
        const val STOP_CONFIRM_TIMEOUT_MS = 2_000L
    }
}

internal class SshUnmanagedBackgroundToolHandle(
    override val executionId: String,
    private val acknowledgement: ToolResult,
    private val registry: SshUnmanagedExecutionRegistry,
    private val owner: SshUnmanagedOwner,
    private val runId: String,
    private val scope: CoroutineScope,
) : ToolExecutionHandle {
    private val stop = AtomicReference<Deferred<ManagedExecutionResult>?>(null)

    override suspend fun awaitResult(): ToolResult = acknowledgement

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        val deferred = scope.async {
            registry.stop(owner.caller(runId), executionId, force = false)
        }
        return if (stop.compareAndSet(null, deferred)) {
            CancelRequestResult.Requested
        } else {
            deferred.cancel()
            CancelRequestResult.AlreadyRequested
        }
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        val first = stop.get()?.let { withTimeoutOrNull(gracePeriod) { it.await() } }
        if (first is ManagedExecutionResult.Stopped) return ToolTerminationState.StoppedConfirmed
        val forced = registry.stop(owner.caller(runId), executionId, force = true)
        return if (forced is ManagedExecutionResult.Stopped) {
            ToolTerminationState.StoppedConfirmed
        } else {
            ToolTerminationState.Unknown
        }
    }
}
