package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import kotlin.uuid.Uuid

/** Typed navigation requests only; raw Intents and private Activity names are never accepted. */
class OwnerUiOperationHandler : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.UI && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported UI action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "UI action contains an unsupported field.")
        }
        if (action.type == "ui_open_conversation" && action.arguments.uuid("conversation_id") == null) {
            return invalid("CONVERSATION_ID_REQUIRED", "conversation_id must be a UUID.")
        }
        if (action.type == "ui_open_provider" && action.arguments.uuid("provider_id") == null) {
            return invalid("PROVIDER_ID_REQUIRED", "provider_id must be a UUID.")
        }
        val route = when (action.type) {
            "ui_navigate", "ui_open_settings" -> action.arguments.string("screen")?.lowercase()
            else -> null
        }
        if (route != null && route !in ROUTES) return invalid("UI_ROUTE_UNSUPPORTED", "Requested UI route is not exposed to Owner tools.")
        return OwnerActionValidation(true, "UI_ACTION_VALID", "UI navigation validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val target = when (action.type) {
            "ui_open_conversation" -> OwnerNavigationTarget.Conversation(action.arguments.string("conversation_id")!!)
            "ui_open_provider" -> OwnerNavigationTarget.Screen("provider", action.arguments.string("provider_id"))
            "ui_open_tts" -> OwnerNavigationTarget.Screen("tts", action.arguments.string("tts_provider_id"))
            "ui_open_settings", "ui_navigate" -> OwnerNavigationTarget.Screen(action.arguments.string("screen")?.lowercase() ?: "settings")
            else -> return failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported UI action.")
        }
        OwnerNavigationMailbox.offer(target)
        return success(index, action.type, "UI_NAVIGATION_REQUESTED", "Typed in-app navigation requested.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ) = if (applied.result.ok) OwnerActionValidation(true, "UI_REQUEST_QUEUED", "Navigation request is queued for the foreground UI.")
    else invalid(applied.result.code, applied.result.message)

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ) = OwnerCompensationResult(true, "UI_NO_COMPENSATION_REQUIRED")

    private fun success(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, buildJsonObject { put("queued", true) }))
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message)
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.uuid(key: String) = string(key)?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }

    private companion object {
        val FIELDS = mapOf(
            "ui_navigate" to setOf("screen"),
            "ui_open_conversation" to setOf("conversation_id"),
            "ui_open_provider" to setOf("provider_id"),
            "ui_open_tts" to setOf("tts_provider_id"),
            "ui_open_settings" to setOf("screen"),
        )
        val ROUTES = setOf("settings", "provider", "tts", "mcp", "skills", "workflows", "owner_runtime", "vault", "doctor")
    }
}
