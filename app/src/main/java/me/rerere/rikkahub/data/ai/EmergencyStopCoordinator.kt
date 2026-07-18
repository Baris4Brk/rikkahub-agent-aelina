package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridge
import me.rerere.rikkahub.data.ai.tools.local.TermuxSessionEmergencyController
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.privilege.PrivilegedCommandResult
import me.rerere.rikkahub.research.ResearchCoordinator
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.workflow.execution.WorkflowEmergencyController
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceStopAllResult

data class EmergencyStopResult(
    val bridgeResult: PrivilegedCommandResult? = null,
    val workspaceResult: WorkspaceStopAllResult? = null,
    val bridgeError: String? = null,
    val workspaceError: String? = null,
    val participants: Map<String, EmergencyStopParticipantReport> = emptyMap(),
) {
    val ok: Boolean
        get() = bridgeError == null && workspaceError == null &&
            bridgeResult?.ok != false && workspaceResult?.ok != false &&
            participants.values.all { it.error == null && it.result?.ok != false }
}

data class EmergencyStopParticipantResult(
    val participantId: String,
    val ok: Boolean,
    val code: String,
    val message: String,
    val affectedCount: Int = 0,
)

data class EmergencyStopParticipantReport(
    val result: EmergencyStopParticipantResult? = null,
    val error: String? = null,
)

interface EmergencyStopParticipant {
    val id: String
    suspend fun stop(): EmergencyStopParticipantResult
}

internal fun emergencyStopParticipant(
    id: String,
    stop: suspend () -> EmergencyStopParticipantResult,
): EmergencyStopParticipant = object : EmergencyStopParticipant {
    override val id: String = id
    override suspend fun stop(): EmergencyStopParticipantResult = stop()
}

/** Persists the safety gate before independently stopping both privileged execution backends. */
class EmergencyStopCoordinator(
    private val safetySettings: AgentSafetySettings,
    private val externalPrivilegeBridge: ExternalPrivilegeBridge,
    private val workspaceProcessManager: WorkspaceProcessManager,
    private val workspaceRepository: WorkspaceRepository,
    private val chatService: ChatService,
    private val termuxSessionController: TermuxSessionEmergencyController,
    private val subAgentRegistry: SubAgentRegistry,
    private val researchCoordinator: ResearchCoordinator,
    private val workflowEmergencyController: WorkflowEmergencyController,
) {
    suspend fun setStopped(stopped: Boolean): EmergencyStopResult? {
        if (!stopped) {
            safetySettings.setEmergencyStop(false)
            researchCoordinator.resumeNewRuns()
            workflowEmergencyController.resumeNewRuns()
            return null
        }
        return activateEmergencyStop(
            persistStop = { safetySettings.setEmergencyStop(true) },
            cancelCommands = externalPrivilegeBridge::cancelAllCommands,
            stopWorkspaceProcesses = {
                workspaceProcessManager.reconcileEmergencyStop(
                    workspaceRepository.getAll().associate { it.id to it.root },
                )
            },
            additionalParticipants = listOf(
                emergencyStopParticipant("chat_runtimes") {
                    val result = chatService.stopAllActiveRuntimesForEmergency()
                    EmergencyStopParticipantResult(
                        participantId = "chat_runtimes",
                        ok = result.ok,
                        code = if (result.ok) "STOPPED" else "PARTIAL_FAILURE",
                        message = if (result.ok) {
                            "Stopped ${result.runtimeCount} active chat runtimes and cleared their queues."
                        } else {
                            "Some chat runtimes could not be stopped or cleared: ${result.failures.keys.joinToString()}."
                        },
                        affectedCount = result.runtimeCount,
                    )
                },
                emergencyStopParticipant("termux_sessions") {
                    val result = termuxSessionController.stopAllAgentSessions()
                    EmergencyStopParticipantResult(
                        participantId = "termux_sessions",
                        ok = result.ok,
                        code = result.code,
                        message = result.message,
                        affectedCount = result.requestedCount,
                    )
                },
                emergencyStopParticipant("sub_agents") {
                    val affected = subAgentRegistry.cancelAllActive()
                    EmergencyStopParticipantResult(
                        participantId = "sub_agents",
                        ok = true,
                        code = "CANCEL_REQUESTED",
                        message = "Cancellation requested for $affected active sub-agents.",
                        affectedCount = affected,
                    )
                },
                emergencyStopParticipant("research") {
                    val affected = researchCoordinator.cancelAllActive()
                    EmergencyStopParticipantResult(
                        participantId = "research",
                        ok = true,
                        code = "CANCEL_REQUESTED",
                        message = "Cancellation requested for $affected active research runs.",
                        affectedCount = affected,
                    )
                },
                emergencyStopParticipant("workflows") {
                    val result = workflowEmergencyController.pauseAndCancelAll()
                    EmergencyStopParticipantResult(
                        participantId = "workflows",
                        ok = result.ok,
                        code = result.code,
                        message = result.message,
                        affectedCount = result.affectedCount,
                    )
                },
            ),
        )
    }
}

internal suspend fun activateEmergencyStop(
    persistStop: suspend () -> Unit,
    cancelCommands: suspend () -> PrivilegedCommandResult,
    stopWorkspaceProcesses: suspend () -> WorkspaceStopAllResult,
    additionalParticipants: List<EmergencyStopParticipant> = emptyList(),
): EmergencyStopResult = withContext(NonCancellable) {
    persistStop()
    // After the persisted gate is visible, every backend is stopped independently. A slow or
    // failed integration must not delay dispatch to the others.
    coroutineScope {
        val bridgeDeferred = async { captureEmergencyAction(cancelCommands) }
        val workspaceDeferred = async { captureEmergencyAction(stopWorkspaceProcesses) }
        val participantDeferred = additionalParticipants.map { participant ->
            async { participant.id to captureEmergencyAction(participant::stop) }
        }
        val bridge = bridgeDeferred.await()
        val workspace = workspaceDeferred.await()
        val participantReports = linkedMapOf<String, EmergencyStopParticipantReport>()
        participantDeferred.awaitAll().forEach { (id, outcome) ->
            participantReports[id] = EmergencyStopParticipantReport(
                result = outcome.getOrNull(),
                error = outcome.exceptionOrNull().toEmergencyMessage(),
            )
        }
        EmergencyStopResult(
            bridgeResult = bridge.getOrNull(),
            workspaceResult = workspace.getOrNull(),
            bridgeError = bridge.exceptionOrNull().toEmergencyMessage(),
            workspaceError = workspace.exceptionOrNull().toEmergencyMessage(),
            participants = participantReports,
        )
    }
}

private fun Throwable?.toEmergencyMessage(): String? =
    this?.message ?: this?.javaClass?.simpleName

private suspend fun <T> captureEmergencyAction(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Throwable) {
    Result.failure(error)
}
