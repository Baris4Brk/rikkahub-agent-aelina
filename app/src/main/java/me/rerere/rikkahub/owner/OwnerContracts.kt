package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

/** Stable risk metadata used for redacted audit and recovery decisions, never for approval. */
enum class OwnerOperationRisk {
    READ_ONLY,
    REVERSIBLE_WRITE,
    EXTERNAL_SIDE_EFFECT,
    IRREVERSIBLE,
}

/** Durable state of one host-owned, one-call operation. */
enum class OwnerOperationState {
    VALIDATING,
    APPLYING,
    VERIFYING,
    COMMITTED,
    COMPENSATING,
    ROLLED_BACK,
    PARTIAL,
    NEEDS_ATTENTION,
    FAILED,
}

enum class OwnerToolFamily(val toolName: String) {
    ASSISTANT("owner_assistant_manage"),
    CONVERSATION("owner_conversation_manage"),
    PROVIDER("owner_provider_manage"),
    SECRET("owner_secret_manage"),
    TTS("owner_tts_manage"),
    SERVICE("owner_service_manage"),
    MCP("owner_mcp_manage"),
    SKILL("owner_skill_manage"),
    WORKFLOW("owner_workflow_manage"),
    UI("owner_ui"),
    DOCTOR("owner_doctor"),
    RUN("owner_run_manage"),
    QUICK_CAPTURE("owner_quick_capture_manage"),
    PLUGIN("owner_plugin_manage"),
    MEMORY("owner_memory_manage"),
    PROMPT_LIBRARY("owner_prompt_library_manage"),
    ASR("owner_asr_manage"),
    CHANNEL("owner_channel_manage"),
    SEARCH("owner_search_manage"),
    BACKUP_STORAGE("owner_backup_storage_manage"),
    APP_SETTINGS("owner_app_settings_manage"),
    RUNTIME("owner_runtime_manage"),
    SAFETY("owner_safety_manage"),
    PET("owner_pet_manage"),
}

/**
 * Model-facing actions are compact but still typed by a finite operation name per family.
 * Arguments are parsed and validated by the family handler before any state is changed.
 */
data class OwnerAction(
    val type: String,
    val arguments: JsonObject,
    val risk: OwnerOperationRisk,
)

data class OwnerOperationRequest(
    val requestId: String,
    val family: OwnerToolFamily,
    val actions: List<OwnerAction>,
    val authoritySubjectId: String,
    val authorityEpoch: Long,
    val assistantId: String,
    val conversationId: String,
    val modelId: String?,
    val providerId: String?,
    /** Process-local snapshot used to validate composed workflow actions. Never persisted. */
    val availableToolNames: Set<String> = emptySet(),
    /** Exact current-turn implementations for interactive workflow_run. Never persisted. */
    val availableTools: List<Tool> = emptyList(),
)

data class OwnerActionResult(
    val index: Int,
    val type: String,
    val ok: Boolean,
    val code: String,
    val message: String,
    /** Must already be redacted and bounded. */
    val data: JsonObject? = null,
)

data class OwnerOperationResult(
    val ok: Boolean,
    val requestId: String,
    val state: OwnerOperationState,
    val code: String,
    val message: String,
    val actions: List<OwnerActionResult> = emptyList(),
    val replayed: Boolean = false,
)

/** Execution seam used by the compact owner tools. */
fun interface OwnerOperationGateway {
    suspend fun execute(
        request: OwnerOperationRequest,
        context: PrivilegedSessionContext,
    ): OwnerOperationResult
}
