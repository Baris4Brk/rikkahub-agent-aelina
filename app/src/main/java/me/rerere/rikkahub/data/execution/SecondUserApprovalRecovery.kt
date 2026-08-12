package me.rerere.rikkahub.data.execution

import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.assistant.SecondUserAuthorityResolution
import me.rerere.rikkahub.assistant.SecondUserAuthorityService
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository

data class ApprovalRecoverySummary(
    val restored: Int,
    val invalidated: Int,
    val retained: Int,
)

/** Reconciles the redacted projection against the message graph without ever replaying a tool. */
class SecondUserApprovalRecovery(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val approvalDao: PendingToolApprovalDao,
    private val lifecycle: SecondUserApprovalLifecycle,
    private val authorityService: SecondUserAuthorityService,
) {
    suspend fun runRecovery(): ApprovalRecoverySummary {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val resolvedAuthority = authorityService.resolve()
        val activeSnapshot = (resolvedAuthority as? SecondUserAuthorityResolution.Active)?.snapshot
        val targetAssistant = activeSnapshot?.let { snapshot ->
            settings.assistants.firstOrNull { it.id == snapshot.assistantId }
        }
        val targetConversationId = activeSnapshot?.conversationId?.toString()
        var restored = 0
        var invalidated = 0
        var retained = 0

        val allPending = approvalDao.getAllPending()
        for (projection in allPending.filter { it.conversationId != targetConversationId }) {
            val graph = runCatching {
                conversationRepository.getConversationById(kotlin.uuid.Uuid.parse(projection.conversationId))
            }.getOrNull()
            lifecycle.invalidateProjection(
                projection = projection,
                conversation = graph,
                reasonCode = "second_user_target_changed",
                orphaned = false,
                source = ExecutionStateSource.RECOVERY,
            )
            invalidated++
        }
        if (targetAssistant == null || targetConversationId == null) {
            return ApprovalRecoverySummary(restored, invalidated, retained)
        }

        val conversationId = checkNotNull(activeSnapshot).conversationId
        val loadedConversation = conversationRepository.getConversationById(conversationId)
        if (loadedConversation == null || loadedConversation.assistantId != targetAssistant.id) {
            for (projection in approvalDao.getPendingForConversation(targetConversationId)) {
                lifecycle.invalidateProjection(
                    projection = projection,
                    conversation = loadedConversation,
                    reasonCode = "second_user_target_missing",
                    orphaned = true,
                    source = ExecutionStateSource.RECOVERY,
                )
                invalidated++
            }
            return ApprovalRecoverySummary(restored, invalidated, retained)
        }
        var conversation = checkNotNull(loadedConversation)

        val pendingByTool = approvalDao.getPendingForConversation(targetConversationId)
            .associateBy { it.toolCallId }
            .toMutableMap()

        // A durable approval is an epoch-bound admission artifact.  A v38/old-epoch pending
        // approval must not become approvable merely because a new second user now owns the
        // same conversation after a process restart.
        for (projection in pendingByTool.values.filter { it.subjectId != activeSnapshot.subjectId }) {
            conversation = lifecycle.invalidateProjection(
                projection = projection,
                conversation = conversation,
                reasonCode = "second_user_authority_stale",
                orphaned = true,
                source = ExecutionStateSource.RECOVERY,
            ) ?: conversation
            invalidated++
            pendingByTool.remove(projection.toolCallId)
        }

        val pendingTools = conversation.messageNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isPending }
            .distinctBy { it.toolCallId }

        for (projection in pendingByTool.values.filter { existing ->
            pendingTools.none { it.toolCallId == existing.toolCallId }
        }) {
            conversation = lifecycle.invalidateProjection(
                projection = projection,
                conversation = conversation,
                reasonCode = "approval_payload_missing",
                orphaned = true,
                source = ExecutionStateSource.RECOVERY,
            ) ?: conversation
            invalidated++
            pendingByTool.remove(projection.toolCallId)
        }

        val owner = PendingApprovalOwner(
            runId = recoveryRunId(targetConversationId),
            commandId = null,
            conversationId = targetConversationId,
            subjectId = activeSnapshot.subjectId,
            subjectType = SubjectType.LOCAL_SECOND_USER,
            origin = ToolCallOrigin.SystemAssistant,
        )
        for (tool in pendingTools) {
            val parsed = runCatching { Json.parseToJsonElement(tool.input) as? JsonObject }.getOrNull()
            val existing = pendingByTool[tool.toolCallId]
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest("recovery-unknown-schema\u0000${tool.toolName}".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            if (parsed == null) {
                val projection = existing ?: lifecycle.persistPendingBarrier(
                    conversation = conversation,
                    owner = owner,
                    tools = listOf(PendingApprovalTool(
                        tool.toolCallId,
                        tool.toolName,
                        JsonObject(emptyMap()),
                        fingerprint,
                    )),
                ).single()
                conversation = lifecycle.invalidateProjection(
                    projection = projection,
                    conversation = conversation,
                    reasonCode = "approval_payload_corrupt",
                    orphaned = true,
                    source = ExecutionStateSource.RECOVERY,
                ) ?: conversation
                invalidated++
            } else if (existing == null) {
                lifecycle.persistPendingBarrier(
                    conversation = conversation,
                    owner = owner,
                    tools = listOf(PendingApprovalTool(
                        tool.toolCallId,
                        tool.toolName,
                        parsed,
                        fingerprint,
                    )),
                )
                restored++
            } else {
                retained++
            }
        }
        return ApprovalRecoverySummary(restored, invalidated, retained)
    }

    private fun recoveryRunId(conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(conversationId.toByteArray())
        return "approval-recovery:" + digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
