package me.rerere.rikkahub.owner

import me.rerere.rikkahub.data.ai.tools.ParsedRequest
import me.rerere.rikkahub.data.ai.tools.parseManagementRequest
import me.rerere.rikkahub.privilege.PrivilegedManagementBackend
import me.rerere.rikkahub.privilege.PrivilegedManagementRequest
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

data class OwnerActionValidation(
    val ok: Boolean,
    val code: String,
    val message: String,
)

data class OwnerAppliedAction(
    val result: OwnerActionResult,
    /** Process-local only. Implementations must never serialize this receipt. */
    val compensationReceipt: Any? = null,
)

data class OwnerCompensationResult(
    val compensated: Boolean,
    val code: String,
)

interface OwnerOperationHandler {
    fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean

    suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation

    suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction

    suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation

    suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult
}

/**
 * Compatibility handler for the already-typed host backend. It gives the new compact tools an
 * immediate one-call path while domain-specific handlers can add snapshots and verification.
 */
class ExistingHostOwnerOperationHandler(
    private val backend: PrivilegedManagementBackend,
    private val selfPreservation: OwnerSelfPreservationGuard = OwnerSelfPreservationGuard(),
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        action.type in allowedOperations(request.family)

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!supports(request, action)) {
            return rejected("OWNER_ACTION_UNSUPPORTED", "Action is not supported by this owner tool.")
        }
        return when (val parsed = parseManagementRequest(action.type, action.arguments)) {
            is ParsedRequest.Error -> rejected(parsed.code, parsed.message)
            is ParsedRequest.Value -> {
                selfPreservation.validate(action)
                    ?: selfPreservation.validate(parsed.request)
                    ?: OwnerActionValidation(true, "OWNER_ACTION_VALID", "Action validated.")
            }
        }
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val parsed = parseManagementRequest(action.type, action.arguments)
        if (parsed !is ParsedRequest.Value) {
            val error = parsed as ParsedRequest.Error
            return OwnerAppliedAction(OwnerActionResult(index, action.type, false, error.code, error.message))
        }
        val result = backend.execute(parsed.request, context)
        return OwnerAppliedAction(
            OwnerActionResult(
                index = index,
                type = action.type,
                ok = result.ok,
                code = result.code,
                message = result.message.take(500),
                data = result.data,
            ),
        )
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = if (applied.result.ok) {
        OwnerActionValidation(true, "OWNER_ACTION_VERIFIED", "Typed backend accepted the action.")
    } else {
        rejected(applied.result.code, applied.result.message)
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = OwnerCompensationResult(
        compensated = false,
        code = "COMPENSATION_REQUIRES_DOMAIN_SNAPSHOT",
    )

    private fun rejected(code: String, message: String) =
        OwnerActionValidation(false, code, message.take(500))
}

class CompositeOwnerOperationHandler(
    private vararg val handlers: OwnerOperationHandler,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        handlers.any { it.supports(request, action) }

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = handler(request, action)?.validate(request, action, context)
        ?: OwnerActionValidation(false, "OWNER_ACTION_UNSUPPORTED", "No typed handler supports this action.")

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = handler(request, action)?.apply(index, request, action, context)
        ?: OwnerAppliedAction(
            OwnerActionResult(index, action.type, false, "OWNER_ACTION_UNSUPPORTED", "No typed handler supports this action."),
        )

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = handler(request, action)?.verify(request, action, applied, context)
        ?: OwnerActionValidation(false, "OWNER_ACTION_UNSUPPORTED", "No typed handler supports this action.")

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = handler(request, action)?.compensate(request, action, applied, context)
        ?: OwnerCompensationResult(false, "COMPENSATION_UNAVAILABLE")

    private fun handler(request: OwnerOperationRequest, action: OwnerAction): OwnerOperationHandler? =
        handlers.firstOrNull { it.supports(request, action) }
}

internal fun allowedOperations(family: OwnerToolFamily): Set<String> = when (family) {
    OwnerToolFamily.ASSISTANT -> setOf(
        "assistant_update",
        "assistant_toggle_tool",
        "assistant_update_skills",
        "assistant_update_mcp_servers",
    )
    OwnerToolFamily.CONVERSATION -> setOf(
        "conversation_create",
        "conversation_update",
        "conversation_delete",
    )
    OwnerToolFamily.SECRET -> setOf(
        "secret_vault_list",
        "secret_vault_create_slot",
        "secret_vault_set_binding",
        "secret_vault_test_binding",
    )
    OwnerToolFamily.DOCTOR -> setOf("rikkahub_state_get")
    OwnerToolFamily.PROVIDER,
    OwnerToolFamily.TTS,
    OwnerToolFamily.SERVICE,
    OwnerToolFamily.MCP,
    OwnerToolFamily.SKILL,
    OwnerToolFamily.WORKFLOW,
    OwnerToolFamily.UI,
    OwnerToolFamily.RUN,
    OwnerToolFamily.QUICK_CAPTURE,
    OwnerToolFamily.PLUGIN,
    OwnerToolFamily.MEMORY,
    OwnerToolFamily.PROMPT_LIBRARY,
    OwnerToolFamily.ASR,
    OwnerToolFamily.CHANNEL,
    OwnerToolFamily.SEARCH,
    OwnerToolFamily.BACKUP_STORAGE,
    OwnerToolFamily.APP_SETTINGS,
    OwnerToolFamily.RUNTIME,
    OwnerToolFamily.SAFETY,
    OwnerToolFamily.PET,
    -> emptySet()
}
