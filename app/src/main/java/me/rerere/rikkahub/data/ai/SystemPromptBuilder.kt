package me.rerere.rikkahub.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationHandler and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (user identity, memory, recent chats,
 * per-call addendum) that change between turns. [GenerationHandler] can place the volatile
 * section after persisted history for Chat Completions providers, preserving the long reusable
 * prefix; providers that only read one system instruction still receive the combined prompt.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: user identity + memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        userIdentityPrompt: String = "",
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }
        }.trim()

        val volatile = buildString {
            fun appendSection(section: String?) {
                if (section.isNullOrBlank()) return
                if (isNotEmpty()) appendLine()
                append(section)
            }
            appendSection(userIdentityPrompt)
            appendSection(memoryPrompt)
            appendSection(recentChatsPrompt)
            appendSection(systemAddendum)
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not support a separate provider-only runtime message. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
        userIdentityPrompt: String = "",
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt,
            memoryPrompt,
            recentChatsPrompt,
            toolPrompts,
            systemAddendum,
            userIdentityPrompt,
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}
