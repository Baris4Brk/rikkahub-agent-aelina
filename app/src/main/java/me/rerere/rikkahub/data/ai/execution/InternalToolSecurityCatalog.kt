package me.rerere.rikkahub.data.ai.execution

/**
 * Application-owned model tools that are intentionally outside [CapabilityCatalog].
 * Keeping the names here prevents a trusted-but-uncatalogued surface (memory, skills, setup,
 * privileged management) from becoming an accidental unknown-tool bypass.
 */
object InternalToolSecurityCatalog {
    val READ_ONLY: Set<String> = setOf(
        "recent_chats",
        "conversation_search",
        "memory_query",
        "skill_get_content",
        "use_skill",
        "rikkahub_state_get",
        "setup_plan",
        "setup_verify",
        "display_session_list",
        "display_session_status",
        "execution_list",
        "execution_status",
        "execution_logs",
    )

    val MUTATING: Set<String> = setOf(
        "conversation_send_message",
        "conversation_create",
        "conversation_update",
        "conversation_delete",
        "assistant_update",
        "assistant_toggle_tool",
        "assistant_update_skills",
        "assistant_update_mcp_servers",
        "lorebook_create",
        "lorebook_update",
        "lorebook_delete",
        "mode_injection_update",
        "app_settings_update",
        "setup_apply",
        "display_session_create",
        "display_session_close",
        "execution_stop",
    )

    val ARGUMENT_DEPENDENT: Set<String> = setOf("memory_tool")
    val ALL: Set<String> = READ_ONLY + MUTATING + ARGUMENT_DEPENDENT
}
