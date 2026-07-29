package me.rerere.rikkahub.data.ai.tools

import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.capability.AccessGrant
import me.rerere.rikkahub.data.capability.CapabilityGrantRepository
import me.rerere.rikkahub.data.capability.CapabilityKey
import me.rerere.rikkahub.data.capability.GrantScope
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionRequest
import me.rerere.rikkahub.execution.ManagedExecutionResult
import me.rerere.rikkahub.execution.ManagedExecutionRuntime
import me.rerere.rikkahub.execution.ExecutionTokenProvider
import me.rerere.rikkahub.data.execution.CancellationCoordinator
import me.rerere.rikkahub.data.execution.CancellationOutcome

private val SECOND_USER_LINUX_CAPABILITIES = listOf(
    "phone.shared.read" to ("file_root" to ToolCapabilityResolver.SHARED_STORAGE_ROOT),
    "phone.shared.write" to ("file_root" to ToolCapabilityResolver.SHARED_STORAGE_ROOT),
    "linux.execute" to ("workspace" to "*"),
    "linux.background" to ("workspace" to "*"),
    "linux.package_install" to ("workspace" to "*"),
)

/** Persistent grants for the selected second-user conversation. Grant/revoke always prompts. */
fun secondUserLinuxGrantTools(
    invocation: ToolInvocationContext,
    repository: CapabilityGrantRepository,
    coordinator: ManagedExecutionCoordinator,
    sessionController: me.rerere.rikkahub.data.ai.tools.local.TermuxSessionEmergencyController,
    executionTokenProvider: ExecutionTokenProvider,
    cancellationCoordinator: CancellationCoordinator,
): List<Tool> {
    val privilege = invocation.privilege ?: return emptyList()
    val assistantId = invocation.callerAssistantId?.takeIf(String::isNotBlank) ?: return emptyList()
    val conversationId = invocation.callerConversationId?.takeIf(String::isNotBlank) ?: return emptyList()
    val origin = invocation.callOrigin ?: return emptyList()
    if (!privilege.expandLocalTools ||
        origin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER
    ) return emptyList()
    val subjectId = "$assistantId:$conversationId"

    val request = Tool(
        name = "linux_grant_request",
        description = "Explicitly enable persistent Linux and full shared-storage autonomy for this selected second-user conversation. The grant remains until linux_grant_revoke is approved.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
        needsApproval = { true },
        execute = {
            val allowedOrigins = InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER
            SECOND_USER_LINUX_CAPABILITIES.forEach { (key, resource) ->
                repository.upsert(
                    AccessGrant(
                        id = stableGrantId(subjectId, key),
                        subjectId = subjectId,
                        subjectType = SubjectType.LOCAL_SECOND_USER,
                        capability = CapabilityKey.of(key),
                        resourceKind = resource.first,
                        resourceIdentifier = resource.second,
                        allowedOrigins = allowedOrigins,
                        scope = GrantScope.CONVERSATION,
                    ),
                )
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("granted", true)
                put("subject", subjectId)
                put("shared_storage_root", ToolCapabilityResolver.SHARED_STORAGE_ROOT)
                put("capabilities", buildJsonArray {
                    SECOND_USER_LINUX_CAPABILITIES.forEach { add(it.first) }
                })
            }.toString()))
        },
    )
    val list = Tool(
        name = "linux_grant_list",
        description = "List active Linux/shared-storage grants for this selected second-user conversation.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
        needsApproval = { false },
        execute = {
            val grants = repository.current().filter {
                it.subjectId == subjectId && it.subjectType == SubjectType.LOCAL_SECOND_USER
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("subject", subjectId)
                put("grants", buildJsonArray {
                    grants.forEach { grant -> add(buildJsonObject {
                        put("id", grant.id)
                        put("capability", grant.capability.value)
                        put("resource", "${grant.resourceKind}:${grant.resourceIdentifier}")
                        put("allowed_origins", buildJsonArray {
                            grant.allowedOrigins.forEach { add(it.name) }
                        })
                    }) }
                })
            }.toString()))
        },
    )
    val revoke = Tool(
        name = "linux_grant_revoke",
        description = "Revoke all Linux/shared-storage grants for this selected conversation and immediately stop its managed Termux/workspace tasks.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
        needsApproval = { true },
        execute = {
            repository.current()
                .filter { it.subjectId == subjectId && it.subjectType == SubjectType.LOCAL_SECOND_USER }
                .forEach { repository.revoke(it.id) }
            val caller = invocation.toManagedExecutionCaller(
                listOf(LocalToolOption.Termux),
            )
            var stopped = 0
            if (caller != null) {
                val expanded = caller.copy(
                    allowedRuntimes = setOf(ManagedExecutionRuntime.TERMUX, ManagedExecutionRuntime.WORKSPACE),
                    workspaceId = invocation.callerWorkspaceId,
                )
                val active = coordinator.dispatch(ManagedExecutionRequest.List(expanded))
                if (active is ManagedExecutionResult.Executions) {
                    active.executions.filter { it.alive }.forEach { execution ->
                        when (cancellationCoordinator.cancelAndAwait(execution.executionId)) {
                            is CancellationOutcome.Cancelled,
                            is CancellationOutcome.AlreadyTerminal,
                            -> stopped++
                            CancellationOutcome.Missing,
                            is CancellationOutcome.Unconfirmed,
                            -> Unit
                        }
                    }
                }
            }
            val ownerPrefix = "rk_su_" + executionTokenProvider.ownerTokenFor(
                domain = "termux_owner",
                assistantId = assistantId,
                conversationId = conversationId,
                origin = origin.name,
            )
            val stoppedSessions = sessionController.stopOwnedSessions(ownerPrefix)
            // Compatibility cleanup is scoped by the same full owner tuple that authorized
            // this revoke; the 32-bit prefix is never accepted as an identity on its own.
            val legacyOwnerPrefix = "rk_su_" + Integer.toHexString(
                "$assistantId:$conversationId".hashCode(),
            )
            val stoppedLegacySessions = sessionController.stopOwnedSessions(legacyOwnerPrefix)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("revoked", true)
                put("stopped_executions", stopped)
                put("stopped_sessions", stoppedSessions.stoppedCount + stoppedLegacySessions.stoppedCount)
                put("session_termination_confirmed", stoppedSessions.ok && stoppedLegacySessions.ok)
            }.toString()))
        },
    )
    return listOf(request, list, revoke)
}

private fun stableGrantId(subjectId: String, capability: String): String =
    UUID.nameUUIDFromBytes("$subjectId|$capability".toByteArray()).toString()
