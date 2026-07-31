package me.rerere.rikkahub.owner

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.owner.db.HostLocalServiceDao
import me.rerere.rikkahub.owner.db.HostLocalServiceEntity
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessManagerState
import me.rerere.workspace.WorkspaceProcessStopReason
import okhttp3.OkHttpClient
import okhttp3.Request

/** Process-lifetime health/restart fallback; it neither schedules WorkManager nor owns an FGS. */
class OwnerLocalServiceSupervisor(
    private val dao: HostLocalServiceDao,
    private val manager: WorkspaceProcessManager,
    private val safety: AgentSafetySettings,
    private val httpClient: OkHttpClient,
    private val scope: AppScope,
    private val specStore: OwnerServiceSpecStore,
    private val termux: OwnerTermuxServiceLauncher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { reconcileOnce() }
                delay(POLL_MS)
            }
        }
    }

    suspend fun reconcileOnce(): Int {
        val workspaceReady = manager.initializationState.value == WorkspaceProcessManagerState.READY
        val now = nowMs()
        val active = SecondUserAuthorityRegistry.current()
        var changed = 0
        for (service in dao.getEnabled()) {
            if (service.nextProbeAtMs != null && service.nextProbeAtMs > now) continue
            val processId = service.executionId?.removePrefix("workspace:")
            val isTermux = service.runtime() == "TERMUX"
            if (!isTermux && !workspaceReady) continue
            if (active == null || active.subjectId != service.authoritySubjectId ||
                active.authorityEpoch != service.authorityEpoch || safety.isEmergencyStop()
            ) {
                val stopped = if (isTermux) {
                    termux.stop(service, force = true)?.alive == false
                } else if (processId != null) {
                    manager.stop(processId, force = true, reason = WorkspaceProcessStopReason.EMERGENCY_STOP)
                    manager.status(processId).process?.alive == false
                } else {
                    false
                }
                val reason = if (safety.isEmergencyStop()) "EMERGENCY_STOP" else "AUTHORITY_REVOKED"
                changed += if (stopped) {
                    update(service, alive = false, health = "STOPPED", enabled = false, reason = reason)
                } else {
                    // A revoked owner must not be reported as stopped until an independent
                    // runtime probe confirms it. Keep the projection in the probe set so the
                    // next pass retries termination instead of stranding a live process.
                    update(
                        service,
                        alive = true,
                        health = "UNKNOWN",
                        enabled = true,
                        reason = "${reason}_STOP_UNCONFIRMED",
                    )
                }
                continue
            }
            val workspaceSnapshot = if (!isTermux) processId?.let { manager.status(it).process } else null
            val termuxProbe = if (isTermux) termux.probe(service) else null
            if (termuxProbe is OwnerTermuxProbeResult.Unreachable) {
                changed += update(
                    service = service,
                    alive = true,
                    health = "UNKNOWN",
                    enabled = true,
                    reason = "TERMUX_UNREACHABLE",
                )
                continue
            }
            val termuxSnapshot = (termuxProbe as? OwnerTermuxProbeResult.Reachable)?.snapshot
            val alive = termuxSnapshot?.alive == true || workspaceSnapshot?.alive == true
            if (alive) {
                val health = probe(verifiedSpec(service)?.healthUrl)
                changed += update(service, alive = true, health = health, enabled = true, reason =
                    if (health == "UNHEALTHY") "HEALTH_PROBE_FAILED" else null)
                continue
            }
            if (service.restartPolicy == "NEVER" || (!isTermux && processId == null)) {
                changed += update(service, alive = false, health = "STOPPED", enabled = false, reason = "PROCESS_EXITED")
                continue
            }
            val termuxRestart = if (isTermux) {
                val spec = verifiedSpec(service)
                val context = termux.context(service)
                if (spec != null && context != null) termux.start(spec, context).getOrNull() else null
            } else null
            val resultCode: String
            val restarted: Boolean
            val executionId: String?
            if (isTermux) {
                restarted = termuxRestart?.alive == true
                executionId = termuxRestart?.executionId ?: service.executionId
                resultCode = if (restarted) "PROCESS_RESTARTED" else "TERMUX_RESTART_FAILED"
            } else {
                val result = manager.restart(requireNotNull(processId))
                restarted = manager.status(processId).process?.alive == true
                executionId = service.executionId
                resultCode = result.code
            }
            val health = if (restarted) probe(verifiedSpec(service)?.healthUrl) else "UNHEALTHY"
            val backoff = ownerServiceRestartBackoffMs(service.restartCount + 1)
            changed += dao.compareAndSetRuntime(
                serviceId = service.serviceId,
                expectedVersion = service.stateVersion,
                executionId = executionId,
                healthState = if (restarted) health else "UNHEALTHY",
                restartCount = service.restartCount + 1,
                nextProbeAtMs = now + backoff,
                lastProbeAtMs = now,
                reasonCode = if (restarted) "PROCESS_RESTARTED" else resultCode,
                enabled = true,
                updatedAtMs = now,
            )
        }
        return changed
    }

    private suspend fun update(
        service: HostLocalServiceEntity,
        alive: Boolean,
        health: String,
        enabled: Boolean,
        reason: String?,
    ): Int {
        val now = nowMs()
        return dao.compareAndSetRuntime(
            serviceId = service.serviceId,
            expectedVersion = service.stateVersion,
            executionId = service.executionId,
            healthState = if (alive) health else "STOPPED",
            restartCount = service.restartCount,
            nextProbeAtMs = if (enabled) now + POLL_MS else null,
            lastProbeAtMs = now,
            reasonCode = reason,
            enabled = enabled,
            updatedAtMs = now,
        )
    }

    private fun probe(url: String?): String {
        if (url == null) return "RUNTIME_CONFIRMED"
        return if (runCatching {
                httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        ) "HEALTHY" else "UNHEALTHY"
    }

    private suspend fun verifiedSpec(service: HostLocalServiceEntity): OwnerLocalServiceSpec? =
        specStore.get(service.serviceId)
            ?.takeIf { ownerServiceSpecHash(it) == service.manifestHash }

    private fun HostLocalServiceEntity.runtime(): String = runCatching {
        (JsonInstant.parseToJsonElement(manifestJson) as JsonObject)["runtime"]
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: "WORKSPACE"

    private companion object {
        const val POLL_MS = 60_000L
    }
}

internal fun ownerServiceRestartBackoffMs(attempt: Int): Long =
    (5_000L shl attempt.coerceIn(0, 6)).coerceAtMost(5 * 60_000L)
