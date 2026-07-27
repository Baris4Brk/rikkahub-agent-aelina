package me.rerere.rikkahub.data.execution

import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
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
) {
    suspend fun runRecovery(): ApprovalRecoverySummary {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val targetAssistant = settings.systemAssistantTargetAssistantId?.let { selected ->
            settings.assistants.firstOrNull { it.id == selected }
        }
        val targetConversationId = targetAssistant?.privilegedConversationId?.toString()
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

        val conversationId = checkNotNull(targetAssistant.privilegedConversationId)
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

        val pendingTools = conversation.messageNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isPending }
            .distinctBy { it.toolCallId }
        val pendingByTool = approvalDao.getPendingForConversation(targetConversationId)
            .associateBy { it.toolCallId }
            .toMutableMap()

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
            subjectId = "${targetAssistant.id}:$targetConversationId",
            subjectType = SubjectType.LOCAL_SECOND_USER,
            origin = ToolCallOrigin.SystemAssistant,
        )
        for (tool in pendingTools) {
            val parsed = runCatching { Json.parseToJsonElement(tool.input) as? JsonObject }.getOrNull()
            val existing = pendingByTool[tool.toolCallId]
            if (parsed == null) {
                val projection = existing ?: lifecycle.persistPendingBarrier(
                    conversation = conversation,
                    owner = owner,
                    tools = listOf(PendingApprovalTool(tool.toolCallId, tool.toolName, JsonObject(emptyMap()))),
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
                    tools = listOf(PendingApprovalTool(tool.toolCallId, tool.toolName, parsed)),
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
