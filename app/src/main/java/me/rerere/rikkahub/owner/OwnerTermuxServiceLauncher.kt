package me.rerere.rikkahub.owner

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool
import me.rerere.rikkahub.execution.ManagedExecutionCaller
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionRequest
import me.rerere.rikkahub.execution.ManagedExecutionResult
import me.rerere.rikkahub.execution.ManagedExecutionRuntime
import me.rerere.rikkahub.execution.ManagedExecutionSnapshot
import me.rerere.rikkahub.execution.TermuxManagedStartableFactory
import me.rerere.rikkahub.owner.db.HostLocalServiceEntity
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

sealed interface OwnerTermuxProbeResult {
    data class Reachable(val snapshot: ManagedExecutionSnapshot) : OwnerTermuxProbeResult
    data class Unreachable(val code: String) : OwnerTermuxProbeResult
}

class OwnerTermuxServiceLauncher(
    context: Context,
    private val factory: TermuxManagedStartableFactory,
    private val coordinator: ManagedExecutionCoordinator,
) {
    private val legacyTool: Tool = termuxRunCommandTool(context.applicationContext)

    suspend fun start(
        spec: OwnerLocalServiceSpec,
        context: ToolExecutionContext,
    ): Result<ManagedExecutionSnapshot> = runCatching {
        require(spec.runtime == "TERMUX")
        val args = buildJsonObject {
            spec.command?.let { put("command", it) }
            spec.executable?.let { put("executable", it) }
            put("arguments", kotlinx.serialization.json.buildJsonArray {
                spec.arguments.forEach { add(it) }
            })
            put("working_dir", spec.workingDirectory)
            put("background", true)
            put("interactive", false)
            put("timeout_seconds", 0)
        }
        val handle = factory.create(legacyTool).start(args, context)
        handle.awaitResult()
        val caller = ManagedExecutionCaller(
            assistantId = context.assistantId,
            conversationId = context.conversationId.toString(),
            runId = context.runId.toString(),
            origin = context.callOrigin,
            allowedRuntimes = setOf(ManagedExecutionRuntime.TERMUX),
        )
        val status = coordinator.dispatch(ManagedExecutionRequest.Status(caller, handle.executionId))
        (status as? ManagedExecutionResult.Snapshot)?.execution
            ?.takeIf { it.alive } ?: error("termux_service_start_unconfirmed")
    }

    suspend fun probe(service: HostLocalServiceEntity): OwnerTermuxProbeResult {
        val executionId = service.executionId
            ?: return OwnerTermuxProbeResult.Unreachable("SERVICE_EXECUTION_MISSING")
        val caller = caller(service)
            ?: return OwnerTermuxProbeResult.Unreachable("SERVICE_OWNER_CONTEXT_MISSING")
        return ownerTermuxProbeResult(
            coordinator.dispatch(ManagedExecutionRequest.Status(caller, executionId)),
        )
    }

    suspend fun stop(service: HostLocalServiceEntity, force: Boolean): ManagedExecutionSnapshot? {
        val executionId = service.executionId ?: return null
        val caller = caller(service) ?: return null
        return when (val result = coordinator.dispatch(ManagedExecutionRequest.Stop(caller, executionId, force))) {
            is ManagedExecutionResult.Stopped -> result.execution
            is ManagedExecutionResult.Snapshot -> result.execution
            else -> (probe(service) as? OwnerTermuxProbeResult.Reachable)?.snapshot
        }
    }

    fun context(service: HostLocalServiceEntity): ToolExecutionContext? {
        val manifest = manifest(service) ?: return null
        val assistantId = manifest["assistant_id"]?.toStringValue() ?: return null
        val conversationId = manifest["conversation_id"]?.toStringValue()?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        } ?: return null
        val origin = manifest["origin"]?.toStringValue()?.let {
            ToolCallOrigin.entries.firstOrNull { origin -> origin.name == it }
        } ?: ToolCallOrigin.LocalChat
        return ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = conversationId,
            assistantId = assistantId,
            callOrigin = origin,
            toolCallId = "owner-service-restart:${service.serviceId.take(80)}",
            capabilitySubject = me.rerere.rikkahub.data.capability.CapabilitySubject(
                id = service.authoritySubjectId,
                type = me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = conversationId.toString(),
            ),
            selectedPrivilegedConversation = true,
        )
    }

    private fun caller(service: HostLocalServiceEntity): ManagedExecutionCaller? {
        val context = context(service) ?: return null
        return ManagedExecutionCaller(
            assistantId = context.assistantId,
            conversationId = context.conversationId.toString(),
            runId = context.runId.toString(),
            origin = context.callOrigin,
            allowedRuntimes = setOf(ManagedExecutionRuntime.TERMUX),
        )
    }

    private fun manifest(service: HostLocalServiceEntity) = runCatching {
        JsonInstant.parseToJsonElement(service.manifestJson) as kotlinx.serialization.json.JsonObject
    }.getOrNull()
    private fun kotlinx.serialization.json.JsonElement.toStringValue() =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

internal fun ownerTermuxProbeResult(result: ManagedExecutionResult): OwnerTermuxProbeResult = when (result) {
    is ManagedExecutionResult.Snapshot -> OwnerTermuxProbeResult.Reachable(result.execution)
    is ManagedExecutionResult.Error -> OwnerTermuxProbeResult.Unreachable(result.code.take(120))
    else -> OwnerTermuxProbeResult.Unreachable("TERMUX_STATUS_UNAVAILABLE")
}
