package me.rerere.rikkahub.owner

import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.execution.WorkspaceManagedProcessStarter
import me.rerere.rikkahub.owner.db.HostLocalServiceDao
import me.rerere.rikkahub.owner.db.HostLocalServiceEntity
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.ai.tools.local.shellSingleQuote
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessStartRequest
import me.rerere.workspace.WorkspaceProcessStopReason
import me.rerere.workspace.WorkspaceRestartPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.uuid.Uuid

class OwnerLocalServiceOperationHandler(
    private val dao: HostLocalServiceDao,
    private val manager: WorkspaceProcessManager,
    private val starter: WorkspaceManagedProcessStarter,
    private val workspaces: WorkspaceRepository,
    private val httpClient: OkHttpClient,
    private val specStore: OwnerServiceSpecStore,
    private val termux: OwnerTermuxServiceLauncher,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.SERVICE && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val fields = FIELDS[action.type] ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported service action.")
        if ((action.arguments.keys - fields).isNotEmpty()) return invalid("OWNER_UNSUPPORTED_FIELD", "Service action contains an unsupported field.")
        if (action.type in setOf("service_register", "emotion_tts_setup")) {
            val command = action.arguments.string("command")?.takeIf { it.isNotBlank() }
            val executable = action.arguments.string("executable")?.takeIf { it.isNotBlank() }
            if ((command == null) == (executable == null)) {
                return invalid("SERVICE_COMMAND_SHAPE_INVALID", "Provide exactly one of command or executable plus arguments.")
            }
            val runtime = action.arguments.string("runtime")?.uppercase() ?: "WORKSPACE"
            if (runtime !in setOf("WORKSPACE", "TERMUX")) return invalid("SERVICE_RUNTIME_INVALID", "runtime must be WORKSPACE or TERMUX.")
            if (runtime == "WORKSPACE") {
                val workspaceId = action.arguments.string("workspace_id")
                    ?: return invalid("WORKSPACE_ID_REQUIRED", "workspace_id is required for Workspace services.")
                if (workspaces.getById(workspaceId) == null) return invalid("WORKSPACE_NOT_FOUND", "Workspace does not exist.")
            }
            if (action.arguments.arguments().any { '\u0000' in it } || executable?.contains('\u0000') == true) {
                return invalid("SERVICE_ARGUMENT_INVALID", "Service executable and arguments may not contain NUL.")
            }
            action.arguments.string("health_url")?.takeIf { it.isNotBlank() }?.let { url ->
                if (!validHealthUrl(url)) return invalid("SERVICE_HEALTH_URL_INVALID", "Health URL must be http(s), contain no credentials/query/fragment, and be bounded.")
            }
        }
        return OwnerActionValidation(true, "SERVICE_ACTION_VALID", "Service action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "service_list" -> list(index, request)
            "service_register", "emotion_tts_setup" -> register(index, request, action, context)
            "service_start", "service_restart" -> restart(index, request, action)
            "service_stop" -> stop(index, request, action)
            "service_status" -> status(index, request, action)
            "service_delete" -> delete(index, request, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported service action.")
        }
    }.getOrElse {
        failure(index, action.type, "SERVICE_OPERATION_FAILED", "Service operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ) = if (applied.result.ok) OwnerActionValidation(true, "SERVICE_ACTION_VERIFIED", "Service facts were probed after the operation.")
    else invalid(applied.result.code, applied.result.message)

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val serviceId = (applied.compensationReceipt as? String) ?: return OwnerCompensationResult(false, "SERVICE_RECEIPT_MISSING")
        val service = dao.get(serviceId) ?: return OwnerCompensationResult(true, "SERVICE_ALREADY_REMOVED")
        if (service.runtime() == "TERMUX") {
            val stopped = termux.stop(service, force = true)
            if (stopped == null || stopped.alive) {
                return OwnerCompensationResult(false, "SERVICE_COMPENSATION_STOP_UNCONFIRMED")
            }
        } else {
            val processId = service.executionId?.removePrefix("workspace:")
            if (processId != null) {
                manager.stop(processId, force = true, reason = WorkspaceProcessStopReason.USER)
                if (manager.status(processId).process?.alive != false) {
                    return OwnerCompensationResult(false, "SERVICE_COMPENSATION_STOP_UNCONFIRMED")
                }
            }
        }
        dao.delete(serviceId)
        specStore.delete(serviceId)
        return OwnerCompensationResult(true, "SERVICE_START_COMPENSATED")
    }

    private suspend fun registerTermux(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
        serviceId: String,
        spec: OwnerLocalServiceSpec,
        restartPolicy: WorkspaceRestartPolicy,
    ): OwnerAppliedAction {
        val toolContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = context.conversationId,
            assistantId = context.assistantId.toString(),
            callOrigin = context.origin,
            toolCallId = "owner-service:${request.requestId.take(80)}",
            capabilitySubject = CapabilitySubject(
                id = request.authoritySubjectId,
                type = SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = request.conversationId,
            ),
            selectedPrivilegedConversation = true,
        )
        val snapshot = termux.start(spec, toolContext).getOrElse {
            specStore.delete(serviceId)
            return failure(index, action.type, "SERVICE_START_UNCONFIRMED", "Termux process did not become live.")
        }
        val health = probeHealth(spec.healthUrl, if (action.type == "emotion_tts_setup") 20 else 3)
        val now = nowMs()
        val manifest = buildJsonObject {
            put("version", 1)
            put("runtime", "TERMUX")
            put("assistant_id", request.assistantId)
            put("conversation_id", request.conversationId)
            put("origin", context.origin.name)
        }.toString()
        dao.insert(
            HostLocalServiceEntity(
                serviceId = serviceId,
                authoritySubjectId = request.authoritySubjectId,
                authorityEpoch = request.authorityEpoch,
                manifestJson = manifest,
                manifestHash = ownerServiceSpecHash(spec),
                executionId = snapshot.executionId,
                healthState = health,
                restartPolicy = restartPolicy.name,
                restartCount = 0,
                nextProbeAtMs = now + 60_000L,
                lastProbeAtMs = now,
                lastReasonCode = if (health in setOf("HEALTHY", "RUNTIME_CONFIRMED")) null else "HEALTH_NOT_CONFIRMED",
                enabled = true,
                stateVersion = 0,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
        return OwnerAppliedAction(
            OwnerActionResult(index, action.type, true, "SERVICE_STARTED", "Termux service started and independently probed.", buildJsonObject {
                put("service_id", serviceId)
                put("execution_id", snapshot.executionId)
                put("alive", snapshot.alive)
                put("health_state", health)
            }),
            compensationReceipt = serviceId,
        )
    }

    private suspend fun register(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val args = action.arguments
        val serviceId = args.string("service_id")?.takeIf { SAFE_ID.matches(it) } ?: Uuid.random().toString()
        if (dao.get(serviceId) != null) return failure(index, action.type, "SERVICE_ALREADY_EXISTS", "service_id already exists.")
        val runtime = args.string("runtime")?.uppercase() ?: "WORKSPACE"
        val workspaceId = args.string("workspace_id")
        val workspace = workspaceId?.let { workspaces.getById(it) }
        val command = args.string("command")?.takeIf { it.isNotBlank() }
        val executable = args.string("executable")?.takeIf { it.isNotBlank() }
        val arguments = args.arguments()
        val workingDirectory = args.string("working_dir") ?: args.string("cwd") ?: if (runtime == "TERMUX") {
            "/data/data/com.termux/files/home"
        } else ""
        val healthUrl = args.string("health_url")?.takeIf { it.isNotBlank() }
        val restartPolicy = when (args.string("restart_policy")?.lowercase() ?: "on_failure") {
            "never" -> WorkspaceRestartPolicy.NEVER
            "always" -> WorkspaceRestartPolicy.ALWAYS
            else -> WorkspaceRestartPolicy.ON_FAILURE
        }
        val spec = OwnerLocalServiceSpec(
            runtime = runtime,
            command = command,
            executable = executable,
            arguments = arguments,
            workingDirectory = workingDirectory,
            healthUrl = healthUrl,
            workspaceId = workspaceId,
            name = args.string("name")?.take(120).orEmpty().ifBlank { "Owner service" },
            keepAwake = args.boolean("keep_awake") ?: true,
            restartPolicy = restartPolicy.name,
        )
        if (!specStore.put(serviceId, spec)) {
            return failure(index, action.type, "SERVICE_SPEC_STORE_FAILED", "Encrypted restart specification could not be saved.")
        }
        if (runtime == "TERMUX") {
            return registerTermux(index, request, action, context, serviceId, spec, restartPolicy)
        }
        val workspaceRequired = workspace ?: run {
            specStore.delete(serviceId)
            return failure(index, action.type, "WORKSPACE_NOT_FOUND", "Workspace does not exist.")
        }
        val workspaceCommand = command ?: buildString {
            append("exec ").append(shellSingleQuote(requireNotNull(executable)))
            arguments.forEach { append(' ').append(shellSingleQuote(it)) }
        }
        val handle = starter.start(
            request = WorkspaceProcessStartRequest(
                workspaceId = workspaceRequired.id,
                workspaceRoot = workspaceRequired.root,
                name = spec.name,
                command = workspaceCommand,
                cwd = workingDirectory.take(2048),
                keepAwake = spec.keepAwake,
                restartPolicy = restartPolicy,
            ),
            context = ToolExecutionContext(
                runId = Uuid.random(),
                conversationId = context.conversationId,
                assistantId = context.assistantId.toString(),
                callOrigin = context.origin,
                toolCallId = "owner-service:${request.requestId.take(80)}",
                workspaceId = workspaceRequired.id,
                capabilitySubject = CapabilitySubject(
                    id = request.authoritySubjectId,
                    type = SubjectType.LOCAL_SECOND_USER,
                    privilegedConversationId = request.conversationId,
                ),
                selectedPrivilegedConversation = true,
            ),
            completionPolicy = CompletionPolicy.SERVICE_EXPECTED_TO_STAY_ALIVE,
        )
        val result = handle.awaitResult()
        val processId = handle.executionId.removePrefix("workspace:")
        val snapshot = manager.status(processId).process
        if (snapshot?.alive != true) {
            specStore.delete(serviceId)
            return failure(index, action.type, "SERVICE_START_UNCONFIRMED", "Workspace process did not become live.")
        }
        val health = probeHealth(spec.healthUrl, attempts = if (action.type == "emotion_tts_setup") 20 else 3)
        val now = nowMs()
        val manifest = buildJsonObject {
            put("version", 1)
            put("runtime", "WORKSPACE")
            put("workspace_id", workspaceRequired.id)
            put("process_id", processId)
            put("assistant_id", request.assistantId)
            put("conversation_id", request.conversationId)
            put("origin", context.origin.name)
        }.toString()
        dao.insert(
            HostLocalServiceEntity(
                serviceId = serviceId,
                authoritySubjectId = request.authoritySubjectId,
                authorityEpoch = request.authorityEpoch,
                manifestJson = manifest,
                manifestHash = ownerServiceSpecHash(spec),
                executionId = handle.executionId,
                healthState = health,
                restartPolicy = restartPolicy.name,
                restartCount = 0,
                nextProbeAtMs = now + 60_000L,
                lastProbeAtMs = now,
                lastReasonCode = if (health == "HEALTHY") null else "HEALTH_NOT_CONFIRMED",
                enabled = true,
                stateVersion = 0,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
        return OwnerAppliedAction(
            OwnerActionResult(index, action.type, true, "SERVICE_STARTED", "Service started and independently probed.", buildJsonObject {
                put("service_id", serviceId)
                put("execution_id", handle.executionId)
                put("process_id", processId)
                put("alive", true)
                put("health_state", health)
            }),
            compensationReceipt = serviceId,
        )
    }

    private suspend fun list(index: Int, request: OwnerOperationRequest): OwnerAppliedAction {
        val records = dao.getRecent().filter { it.authoritySubjectId == request.authoritySubjectId }
        return success(index, "service_list", "SERVICE_LIST", "Local service projections returned.", buildJsonObject {
            put("services", buildJsonArray {
                records.forEach { service -> add(buildJsonObject {
                    put("service_id", service.serviceId)
                    put("execution_id", service.executionId ?: "")
                    put("health_state", service.healthState)
                    put("enabled", service.enabled)
                    put("restart_count", service.restartCount)
                }) }
            })
        })
    }

    private suspend fun restart(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val service = ownedService(request, action) ?: return failure(index, action.type, "SERVICE_NOT_FOUND", "Service does not exist for this authority.")
        if (service.runtime() == "TERMUX") return restartTermux(index, action, service)
        val processId = service.executionId?.removePrefix("workspace:")
            ?: return failure(index, action.type, "SERVICE_EXECUTION_MISSING", "Service has no execution ID.")
        val result = manager.restart(processId)
        val probe = manager.status(processId).process
        val health = probeHealth(serviceHealthUrl(service), 5)
        updateProjection(service, probe?.alive == true, health, if (result.ok) null else result.code)
        return if (result.ok && probe?.alive == true) success(index, action.type, "SERVICE_RESTARTED", "Service restarted and probed.", serviceData(service, probe.alive, health))
        else failure(index, action.type, "SERVICE_RESTART_UNCONFIRMED", "Service restart was not confirmed.")
    }

    private suspend fun restartTermux(
        index: Int,
        action: OwnerAction,
        service: HostLocalServiceEntity,
    ): OwnerAppliedAction {
        val current = when (val probe = termux.probe(service)) {
            is OwnerTermuxProbeResult.Reachable -> probe.snapshot
            is OwnerTermuxProbeResult.Unreachable -> return failure(
                index,
                action.type,
                "SERVICE_STATUS_UNREACHABLE",
                "Termux runtime is temporarily unreachable; restart was not attempted.",
            )
        }
        if (current.alive) {
            val stopped = termux.stop(service, force = true)
            if (stopped?.alive != false) return failure(index, action.type, "SERVICE_STOP_UNCONFIRMED", "Existing Termux process may still be running.")
        }
        val spec = loadVerifiedSpec(service)
            ?: return failure(index, action.type, "SERVICE_SPEC_MISSING", "Encrypted restart specification is unavailable.")
        val toolContext = termux.context(service)
            ?: return failure(index, action.type, "SERVICE_OWNER_CONTEXT_MISSING", "Service owner context is unavailable.")
        val restarted = termux.start(spec, toolContext).getOrElse {
            return failure(index, action.type, "SERVICE_RESTART_UNCONFIRMED", "Termux restart was not confirmed.")
        }
        val health = probeHealth(serviceHealthUrl(service), 5)
        updateProjection(
            service = service,
            alive = restarted.alive,
            health = health,
            reason = "PROCESS_RESTARTED",
            enabled = true,
            executionId = restarted.executionId,
            restartCount = service.restartCount + 1,
        )
        return success(index, action.type, "SERVICE_RESTARTED", "Termux service restarted with a new execution instance.", buildJsonObject {
            put("service_id", service.serviceId)
            put("execution_id", restarted.executionId)
            put("alive", restarted.alive)
            put("health_state", health)
        })
    }

    private suspend fun stop(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val service = ownedService(request, action) ?: return failure(index, action.type, "SERVICE_NOT_FOUND", "Service does not exist for this authority.")
        if (service.runtime() == "TERMUX") {
            val probe = termux.stop(service, force = action.arguments.boolean("force") ?: false)
            val stopped = probe?.alive == false
            updateProjection(service, !stopped, if (stopped) "STOPPED" else "UNKNOWN", if (stopped) null else "STOP_UNCONFIRMED", enabled = !stopped)
            return if (stopped) success(index, action.type, "SERVICE_STOPPED_CONFIRMED", "Termux service stop was independently confirmed.")
            else failure(index, action.type, "SERVICE_STOP_UNCONFIRMED", "Termux service may still be running.")
        }
        val processId = service.executionId?.removePrefix("workspace:")
            ?: return failure(index, action.type, "SERVICE_EXECUTION_MISSING", "Service has no execution ID.")
        manager.stop(processId, force = action.arguments.boolean("force") ?: false, reason = WorkspaceProcessStopReason.USER)
        val probe = manager.status(processId).process
        val stopped = probe?.alive == false
        updateProjection(service, alive = !stopped, health = if (stopped) "STOPPED" else "UNKNOWN", reason = if (stopped) null else "STOP_UNCONFIRMED", enabled = !stopped)
        return if (stopped) success(index, action.type, "SERVICE_STOPPED_CONFIRMED", "Service stop was confirmed by an independent probe.")
        else failure(index, action.type, "SERVICE_STOP_UNCONFIRMED", "Service may still be running.")
    }

    private suspend fun status(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val service = ownedService(request, action) ?: return failure(index, action.type, "SERVICE_NOT_FOUND", "Service does not exist for this authority.")
        if (service.runtime() == "TERMUX") {
            return when (val probe = termux.probe(service)) {
                is OwnerTermuxProbeResult.Reachable -> {
                    val health = if (probe.snapshot.alive) probeHealth(serviceHealthUrl(service), 1) else "STOPPED"
                    updateProjection(service, probe.snapshot.alive, health, null)
                    success(index, action.type, "SERVICE_STATUS", "Termux service facts probed.", serviceData(service, probe.snapshot.alive, health))
                }
                is OwnerTermuxProbeResult.Unreachable -> {
                    updateProjection(service, alive = true, health = "UNKNOWN", reason = "TERMUX_UNREACHABLE")
                    failure(index, action.type, "SERVICE_STATUS_UNREACHABLE", "Termux runtime is temporarily unreachable; process state remains unknown.")
                }
            }
        }
        val processId = service.executionId?.removePrefix("workspace:")
        val probe = processId?.let { manager.status(it).process }
        val health = if (probe?.alive == true) probeHealth(serviceHealthUrl(service), 1) else "STOPPED"
        updateProjection(service, probe?.alive == true, health, probe?.lastErrorCode)
        return success(index, action.type, "SERVICE_STATUS", "Service facts probed.", serviceData(service, probe?.alive == true, health))
    }

    private suspend fun delete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val service = ownedService(request, action) ?: return failure(index, action.type, "SERVICE_NOT_FOUND", "Service does not exist for this authority.")
        if (service.runtime() == "TERMUX") {
            val stopped = termux.stop(service, force = true)
            if (stopped == null || stopped.alive) {
                return failure(index, action.type, "SERVICE_DELETE_STOP_UNCONFIRMED", "Projection was retained because the process may still be running.")
            }
        } else service.executionId?.removePrefix("workspace:")?.let { processId ->
            manager.stop(processId, force = true, reason = WorkspaceProcessStopReason.USER)
            if (manager.status(processId).process?.alive == true) {
                return failure(index, action.type, "SERVICE_DELETE_STOP_UNCONFIRMED", "Projection was retained because the process may still be running.")
            }
        }
        dao.delete(service.serviceId)
        specStore.delete(service.serviceId)
        return success(index, action.type, "SERVICE_DELETED", "Stopped service projection deleted; workspace history remains auditable.")
    }

    private suspend fun ownedService(request: OwnerOperationRequest, action: OwnerAction): HostLocalServiceEntity? {
        val id = action.arguments.string("service_id") ?: return null
        return dao.get(id)?.takeIf {
            it.authoritySubjectId == request.authoritySubjectId && it.authorityEpoch == request.authorityEpoch
        }
    }

    private suspend fun updateProjection(
        service: HostLocalServiceEntity,
        alive: Boolean,
        health: String,
        reason: String?,
        enabled: Boolean = service.enabled,
        executionId: String? = service.executionId,
        restartCount: Int = service.restartCount,
    ) {
        val now = nowMs()
        dao.compareAndSetRuntime(
            serviceId = service.serviceId,
            expectedVersion = service.stateVersion,
            executionId = executionId,
            healthState = if (alive) health else "STOPPED",
            restartCount = restartCount,
            nextProbeAtMs = now + 60_000L,
            lastProbeAtMs = now,
            reasonCode = reason,
            enabled = enabled,
            updatedAtMs = now,
        )
    }

    private suspend fun probeHealth(url: String?, attempts: Int): String {
        if (url == null) return "RUNTIME_CONFIRMED"
        repeat(attempts.coerceIn(1, 20)) { attempt ->
            val ok = runCatching {
                httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return "HEALTHY"
            if (attempt + 1 < attempts) delay(1_000L)
        }
        return "UNHEALTHY"
    }

    private suspend fun loadVerifiedSpec(service: HostLocalServiceEntity): OwnerLocalServiceSpec? =
        specStore.get(service.serviceId)
            ?.takeIf { ownerServiceSpecHash(it) == service.manifestHash }

    private suspend fun serviceHealthUrl(service: HostLocalServiceEntity): String? =
        loadVerifiedSpec(service)?.healthUrl

    private fun HostLocalServiceEntity.runtime(): String = runCatching {
        JsonInstant.parseToJsonElement(manifestJson).let { it as JsonObject }.string("runtime")
    }.getOrNull() ?: "WORKSPACE"

    private fun validHealthUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        raw.length <= 2048 && uri.scheme in setOf("http", "https") && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun serviceData(service: HostLocalServiceEntity, alive: Boolean, health: String) = buildJsonObject {
        put("service_id", service.serviceId)
        put("execution_id", service.executionId ?: "")
        put("alive", alive)
        put("health_state", health)
    }

    private fun success(index: Int, type: String, code: String, message: String, data: JsonObject? = null) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data))
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message)
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()
    private fun JsonObject.arguments(): List<String> = (this["arguments"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.take(128)
        .orEmpty()

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
        val FIELDS = mapOf(
            "service_list" to emptySet(),
            "service_register" to setOf("service_id", "runtime", "workspace_id", "name", "command", "executable", "arguments", "cwd", "working_dir", "keep_awake", "restart_policy", "health_url"),
            "emotion_tts_setup" to setOf("service_id", "runtime", "workspace_id", "name", "command", "executable", "arguments", "cwd", "working_dir", "keep_awake", "restart_policy", "health_url"),
            "service_start" to setOf("service_id"),
            "service_restart" to setOf("service_id"),
            "service_stop" to setOf("service_id", "force"),
            "service_status" to setOf("service_id"),
            "service_delete" to setOf("service_id"),
        )
    }
}
