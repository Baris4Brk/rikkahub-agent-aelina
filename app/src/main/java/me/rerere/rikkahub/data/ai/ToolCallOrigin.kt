package me.rerere.rikkahub.data.ai

/**
 * Identifies the origin of a tool invocation. Every tool call must carry one of these so the
 * [ToolExecutionGate] can apply origin-specific policies (e.g. "Telegram may not call
 * install_apk").
 *
 * When adding a new entry, update [ToolExecutionGate] and [AgentSafetySettings] accordingly.
 */
enum class ToolCallOrigin {
    /** User typed a message in the chat UI. Full local tool surface is available. */
    LocalChat,

    /** The user submitted text from RikkaHub's visible, unlocked system-assistant session. */
    SystemAssistant,

    /** A system-assistant invocation that began while keyguard was active. No tools are allowed. */
    SystemAssistantKeyguard,

    /** A visible, unlocked QuickCapture overlay bound to one fixed second-user conversation. */
    QuickCapture,

    /** A trusted local workflow is running. Same tool surface as LocalChat but may have
     *  reduced approval prompts (the workflow already had a creation-time approval gate). */
    TrustedWorkflow,

    /** Telegram bot dispatches a command. Restricted surface — no silent install/uninstall,
     *  no Device Admin, no phone calls. */
    Telegram,

    /** WebServer API call. Restricted surface — same restrictions as Telegram plus all
     *  privacy reads must return redacted results. */
    WebServer,

    /** An MCP server relayed a tool call. The server's opaque tool surface is executed
     *  through the same execution gate but with TTL-limited per-call approval. */
    MCP,

    /** External Intent / ExternalAutomationActivity / ExternalAutomationReceiver.
     *  Restricted surface — no file writes to the agent workspace, no keystore ops,
     *  no notification posts (the user chose the app via intent, not the assistant). */
    ExternalIntent,
}
