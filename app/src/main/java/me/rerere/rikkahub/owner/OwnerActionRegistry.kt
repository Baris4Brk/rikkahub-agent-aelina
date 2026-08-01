package me.rerere.rikkahub.owner

/**
 * Single model-facing source of truth for Owner tool families and actions.
 *
 * Handlers may impose deeper domain validation, but a model-visible action cannot exist outside
 * this registry. Schema generation, risk metadata, compact argument help and coverage tests all
 * consume the same immutable snapshot.
 */
data class OwnerActionSpec(
    val type: String,
    val risk: OwnerOperationRisk,
    val argumentGuide: String = "",
)

data class OwnerFamilySpec(
    val family: OwnerToolFamily,
    val description: String,
    val actions: List<OwnerActionSpec>,
) {
    init {
        require(actions.isNotEmpty()) { "Owner family ${family.name} must expose at least one action" }
        require(actions.map { it.type }.distinct().size == actions.size) {
            "Owner family ${family.name} contains duplicate action names"
        }
    }

    val operations: Map<String, OwnerOperationRisk> = actions.associate { it.type to it.risk }
}

object OwnerActionRegistry {
    val families: List<OwnerFamilySpec> = listOf(
        family(
            OwnerToolFamily.ASSISTANT,
            "Create, clone, update, delete or select ordinary assistants and update this Owner's model/TTS bindings. Authority fields are permanently excluded.",
            action("assistant_create", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id?, name?, system_prompt?, model_id?"),
            action("assistant_clone", OwnerOperationRisk.REVERSIBLE_WRITE, "source_assistant_id, assistant_id?, name?"),
            action("assistant_update", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id, name?, system_prompt?, chat_model_id?, workspace_id?, enable_memory?, use_global_memory?, enable_recent_chats_reference?, stream_output?, fast_path_router_enabled?, enable_web_search?"),
            action("assistant_delete", OwnerOperationRisk.IRREVERSIBLE, "assistant_id"),
            action("assistant_set_default", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id"),
            action("assistant_toggle_tool", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id, tool_type, enabled"),
            action("assistant_update_skills", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id, operation, skill_names"),
            action("assistant_update_mcp_servers", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id, operation, server_ids"),
            action("assistant_switch_model", OwnerOperationRisk.REVERSIBLE_WRITE, "assistant_id, model_id"),
            action("assistant_switch_tts", OwnerOperationRisk.REVERSIBLE_WRITE, "tts_provider_id"),
        ),
        family(
            OwnerToolFamily.CONVERSATION,
            "Create, branch, archive, restore, rename, search, export, open or delete ordinary conversations. The protected Owner conversation remains immutable to deletion.",
            action("conversation_create", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id?, assistant_id, title?"),
            action("conversation_branch", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id, new_conversation_id?, title?"),
            action("conversation_archive", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id"),
            action("conversation_restore", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id"),
            action("conversation_update", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id, title?, pinned?, custom_system_prompt?"),
            action("conversation_search", OwnerOperationRisk.READ_ONLY, "query?, limit?"),
            action("conversation_export", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "conversation_id"),
            action("conversation_open", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id"),
            action("conversation_delete", OwnerOperationRisk.IRREVERSIBLE, "conversation_id"),
        ),
        family(
            OwnerToolFamily.PROVIDER,
            "Manage OpenAI-compatible, Google and Claude Provider records, models, Vault references, tests and active selection.",
            action("provider_list", OwnerOperationRisk.READ_ONLY),
            action("provider_create", OwnerOperationRisk.REVERSIBLE_WRITE, "provider_id?, provider_type, name?, base_url?, vault_slot_id?"),
            action("provider_update", OwnerOperationRisk.REVERSIBLE_WRITE, "provider_id, name?, base_url?, enabled?"),
            action("provider_delete", OwnerOperationRisk.IRREVERSIBLE, "provider_id"),
            action("provider_refresh_models", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "provider_id"),
            action("provider_test", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "provider_id"),
            action("provider_set_default", OwnerOperationRisk.REVERSIBLE_WRITE, "model_id, assistant_id?"),
        ),
        family(
            OwnerToolFamily.SECRET,
            "Manage Vault metadata/bindings and a user-enabled remote plaintext session. Secret values are accepted only by explicitly sensitive actions and are never persisted.",
            action("secret_vault_list", OwnerOperationRisk.READ_ONLY),
            action("secret_vault_create_slot", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id, label, purpose"),
            action("secret_vault_set_binding", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id, kind, target_id, enabled"),
            action("secret_vault_test_binding", OwnerOperationRisk.READ_ONLY, "slot_id, kind, target_id"),
            action("secret_session_status", OwnerOperationRisk.READ_ONLY),
            action("secret_provider_credentials_reveal", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "provider_ids? (omit for all Providers; max 32)"),
            action("secret_plaintext_reveal", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "slot_id"),
            action("secret_replace", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id, find, replacement"),
            action("secret_trim", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id"),
            action("secret_remove_prefix", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id, prefix"),
            action("secret_remove_quotes", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id"),
            action("secret_remove_newlines", OwnerOperationRisk.REVERSIBLE_WRITE, "slot_id"),
        ),
        family(
            OwnerToolFamily.TTS,
            "Manage all TTS types including Generic HTTP, synthesis/playback, cached artifacts, playback speed and default selection.",
            action("tts_list", OwnerOperationRisk.READ_ONLY),
            action("tts_create_generic_http", OwnerOperationRisk.REVERSIBLE_WRITE, "tts_provider_id?, name?, endpoint, method?, body_encoding?, body_template?, headers?, response_mode?, response_json_path?, audio_format?, voice?, language?, allow_private_network?, max_response_bytes?, vault_slot_id?"),
            action("tts_update", OwnerOperationRisk.REVERSIBLE_WRITE, "tts_provider_id, same optional Generic HTTP fields as create"),
            action("tts_delete", OwnerOperationRisk.IRREVERSIBLE, "tts_provider_id"),
            action("tts_test", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "tts_provider_id, text?"),
            action("tts_play", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "artifact_id? or text?"),
            action("tts_set_default", OwnerOperationRisk.REVERSIBLE_WRITE, "tts_provider_id"),
            action("tts_get_playback_speed", OwnerOperationRisk.READ_ONLY),
            action("tts_set_playback_speed", OwnerOperationRisk.REVERSIBLE_WRITE, "speed (0.5..2.0)"),
        ),
        family(
            OwnerToolFamily.SERVICE,
            "Register and supervise Workspace/Termux local services using the execution ledger, health probes and restart backoff.",
            action("service_list", OwnerOperationRisk.READ_ONLY),
            action("service_register", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "service_id?, runtime, workspace_id?, name?, command? or executable+arguments, cwd?, keep_awake?, restart_policy?, health_url?"),
            action("service_start", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "service_id"),
            action("service_stop", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "service_id, force?"),
            action("service_restart", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "service_id"),
            action("service_status", OwnerOperationRisk.READ_ONLY, "service_id"),
            action("service_delete", OwnerOperationRisk.IRREVERSIBLE, "service_id"),
            action("emotion_tts_setup", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "service fields plus tts_endpoint, tts_name?, tts_method?, tts_body_encoding?, tts_body_template?, tts_headers?, tts_response_mode?, tts_response_json_path?, tts_audio_format?, tts_voice?, tts_language?, vault_slot_id?, test_texts?, set_default?"),
        ),
        family(
            OwnerToolFamily.MCP,
            "Discover, install at a pinned version/hash, test, update, bind or remove MCP servers through existing MCP storage.",
            action("mcp_list", OwnerOperationRisk.READ_ONLY),
            action("mcp_discover", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "source_url, pin"),
            action("mcp_install", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "mcp_id?, name, transport, url, pin, source_url? (required for content hash), enabled?, headers?, wait_seconds?"),
            action("mcp_update", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "mcp_id, name, transport, url, pin, source_url? (required for content hash), enabled?, headers?, wait_seconds?"),
            action("mcp_delete", OwnerOperationRisk.IRREVERSIBLE, "mcp_id"),
            action("mcp_bind", OwnerOperationRisk.REVERSIBLE_WRITE, "mcp_id, assistant_id"),
            action("mcp_unbind", OwnerOperationRisk.REVERSIBLE_WRITE, "mcp_id, assistant_id"),
            action("mcp_test", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "mcp_id, wait_seconds?"),
        ),
        family(
            OwnerToolFamily.SKILL,
            "Install from pinned HTTPS/Git content, update, test, bind, unbind or uninstall Skills with rollback.",
            action("skill_list", OwnerOperationRisk.READ_ONLY),
            action("skill_install", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "skill_name?, one of source_url/git_url/archive_url, pin"),
            action("skill_update", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "skill_name, one of source_url/git_url/archive_url, pin"),
            action("skill_uninstall", OwnerOperationRisk.IRREVERSIBLE, "skill_name"),
            action("skill_bind", OwnerOperationRisk.REVERSIBLE_WRITE, "skill_name, assistant_id"),
            action("skill_unbind", OwnerOperationRisk.REVERSIBLE_WRITE, "skill_name, assistant_id"),
            action("skill_test", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "skill_name"),
        ),
        family(
            OwnerToolFamily.WORKFLOW,
            "Create, update, enable, run or delete Workflows. Interactive runs use Owner authority; automated runs use a frozen capability snapshot.",
            action("workflow_list", OwnerOperationRisk.READ_ONLY),
            action("workflow_create", OwnerOperationRisk.REVERSIBLE_WRITE, "definition"),
            action("workflow_update", OwnerOperationRisk.REVERSIBLE_WRITE, "definition"),
            action("workflow_delete", OwnerOperationRisk.IRREVERSIBLE, "workflow_id"),
            action("workflow_set_enabled", OwnerOperationRisk.REVERSIBLE_WRITE, "workflow_id, enabled"),
            action("workflow_run", OwnerOperationRisk.EXTERNAL_SIDE_EFFECT, "workflow_id"),
        ),
        family(
            OwnerToolFamily.UI,
            "Navigate RikkaHub to typed pages or conversations without raw Intent or private-Activity access.",
            action("ui_navigate", OwnerOperationRisk.REVERSIBLE_WRITE, "screen"),
            action("ui_open_conversation", OwnerOperationRisk.REVERSIBLE_WRITE, "conversation_id"),
            action("ui_open_provider", OwnerOperationRisk.REVERSIBLE_WRITE, "provider_id"),
            action("ui_open_tts", OwnerOperationRisk.REVERSIBLE_WRITE, "tts_provider_id"),
            action("ui_open_settings", OwnerOperationRisk.REVERSIBLE_WRITE, "screen"),
        ),
        family(
            OwnerToolFamily.DOCTOR,
            "Read redacted Owner runtime diagnostics and run only safe repair/reconcile operations.",
            action("rikkahub_state_get", OwnerOperationRisk.READ_ONLY),
            action("doctor_check", OwnerOperationRisk.READ_ONLY),
            action("doctor_repair", OwnerOperationRisk.REVERSIBLE_WRITE, "repair"),
            action("doctor_recover_operation", OwnerOperationRisk.REVERSIBLE_WRITE, "request_id"),
        ),
    )

    private val byFamily = families.associateBy { it.family }
    private val byAction = families.flatMap { family ->
        family.actions.map { action -> (family.family to action.type) to action }
    }.toMap()

    init {
        require(byFamily.size == OwnerToolFamily.entries.size) {
            "Every OwnerToolFamily must be registered exactly once"
        }
        require(byAction.size == families.sumOf { it.actions.size }) {
            "Owner action registry contains duplicate family/action keys"
        }
    }

    fun family(family: OwnerToolFamily): OwnerFamilySpec =
        requireNotNull(byFamily[family]) { "Owner family ${family.name} is not registered" }

    fun action(family: OwnerToolFamily, type: String): OwnerActionSpec? = byAction[family to type]

    fun actionCount(): Int = byAction.size

    private fun action(type: String, risk: OwnerOperationRisk, guide: String = "") =
        OwnerActionSpec(type, risk, guide)

    private fun family(
        family: OwnerToolFamily,
        description: String,
        vararg actions: OwnerActionSpec,
    ) = OwnerFamilySpec(family, description, actions.toList())
}
