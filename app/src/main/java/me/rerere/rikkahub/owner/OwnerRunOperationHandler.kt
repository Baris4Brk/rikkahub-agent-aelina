package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import kotlin.uuid.Uuid

data class OwnerRunSnapshot(
    val exists: Boolean,
    val runtimeState: String,
    val activeCommandId: Uuid?,
    val pendingCommandIds: Set<Uuid>,
)

data class OwnerRunSubmission(
    val accepted: Boolean,
    val code: String,
    val commandId: Uuid? = null,
)

interface OwnerRunController {
    suspend fun snapshot(conversationId: Uuid): OwnerRunSnapshot
    suspend fun cancel(conversationId: Uuid, commandId: Uuid?): OwnerRunSubmission
    suspend fun retryLastAssistant(conversationId: Uuid): OwnerRunSubmission
}

/**
 * Controls the real ConversationRuntime instead of maintaining an Owner-only shadow queue.
 * Cancelling the command currently executing this very tool is rejected because its result could
 * never be committed; pending commands in the same conversation remain cancellable.
 */
class OwnerRunOperationHandler(
    private val controller: OwnerRunController,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.RUN && action.type in ACTIONS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val unknown = action.arguments.keys - FIELDS.getValue(action.type)
        if (unknown.isNotEmpty()) return invalid("OWNER_UNSUPPORTED_FIELD", "Unsupported run fields: ${unknown.sorted().joinToString()}.")
        val target = action.arguments.uuid("conversation_id")
            ?: return invalid("CONVERSATION_ID_REQUIRED", "conversation_id must be a UUID.")
        if (!controller.snapshot(target).exists) return invalid("CONVERSATION_NOT_FOUND", "Target conversation does not exist.")
        if (action.arguments["command_id"] != null && action.arguments.uuid("command_id") == null) {
            return invalid("COMMAND_ID_INVALID", "command_id must be a UUID when supplied.")
        }
        return OwnerActionValidation(true, "OWNER_RUN_ACTION_VALID", "Live run action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val target = requireNotNull(action.arguments.uuid("conversation_id"))
        val before = controller.snapshot(target)
        val requestedCommand = action.arguments.uuid("command_id")
        if (action.type == "run_cancel" && target.toString() == request.conversationId &&
            (requestedCommand == null || requestedCommand == before.activeCommandId)
        ) {
            return failure(
                index,
                action,
                "OWNER_ACTIVE_CALL_SELF_CANCEL_BLOCKED",
                "The active Owner tool call cannot cancel itself before committing its result; a pending command_id in this conversation can still be cancelled.",
            )
        }
        val submission = when (action.type) {
            "run_cancel" -> controller.cancel(target, requestedCommand)
            "run_retry" -> controller.retryLastAssistant(target)
            else -> OwnerRunSubmission(false, "OWNER_ACTION_UNSUPPORTED")
        }
        if (!submission.accepted) return failure(index, action, submission.code, "Conversation runtime rejected the requested run control.")
        return success(
            index = index,
            action = action,
            code = if (action.type == "run_cancel") "RUN_CANCEL_SUBMITTED" else "RUN_RETRY_SUBMITTED",
            message = if (action.type == "run_cancel") "Cancellation was submitted to the real conversation runtime." else "Regeneration was submitted to the real conversation runtime.",
            data = buildJsonObject {
                put("conversation_id", target.toString())
                submission.commandId?.let { put("control_command_id", it.toString()) }
            },
            receipt = Receipt(target, requestedCommand, submission.commandId, action.type),
        )
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val receipt = applied.compensationReceipt as? Receipt
            ?: return invalid("OWNER_RUN_RECEIPT_MISSING", "Run-control receipt is missing.")
        val after = controller.snapshot(receipt.conversationId)
        if (!after.exists) return invalid("CONVERSATION_NOT_FOUND", "Conversation disappeared during run control.")
        val verified = when (receipt.type) {
            "run_cancel" -> receipt.targetCommandId?.let { it != after.activeCommandId && it !in after.pendingCommandIds }
                ?: after.runtimeState !in setOf("Running", "Cancelling")
            "run_retry" -> receipt.controlCommandId != null
            else -> false
        }
        return if (verified) OwnerActionValidation(true, "OWNER_RUN_STATE_VERIFIED", "Conversation runtime state was read back.")
        else invalid("OWNER_RUN_VERIFY_FAILED", "Conversation runtime has not accepted the requested state transition.")
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = OwnerCompensationResult(false, "LIVE_RUN_CONTROL_NOT_REVERSIBLE")

    private fun success(
        index: Int,
        action: OwnerAction,
        code: String,
        message: String,
        data: JsonObject,
        receipt: Receipt,
    ) = OwnerAppliedAction(OwnerActionResult(index, action.type, true, code, message, data), receipt)

    private fun failure(index: Int, action: OwnerAction, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, code, message.take(500)))

    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))

    private data class Receipt(
        val conversationId: Uuid,
        val targetCommandId: Uuid?,
        val controlCommandId: Uuid?,
        val type: String,
    )

    private companion object {
        val ACTIONS = setOf("run_cancel", "run_retry")
        val FIELDS = mapOf(
            "run_cancel" to setOf("conversation_id", "command_id"),
            "run_retry" to setOf("conversation_id"),
        )
    }
}

private fun JsonObject.uuid(name: String): Uuid? = this[name]?.jsonPrimitive?.contentOrNull
    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
