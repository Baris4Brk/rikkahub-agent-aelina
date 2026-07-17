package me.rerere.rikkahub.assistant

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

enum class SystemAssistantHostKind {
    VOICE_SESSION,
    ACTIVITY_OVERLAY,
}

enum class InvocationSurfacePresence {
    FULL_CHAT,
    OVERLAY_VISIBLE,
    RUNNING_AFTER_OVERLAY_CLOSED,
    VOICE_SESSION_VISIBLE,
    REMOTE_OR_WORKFLOW,
}

data class InvocationSurfaceContext(
    val origin: ToolCallOrigin,
    val hostKind: SystemAssistantHostKind?,
    val presence: InvocationSurfacePresence,
    val conversationId: Uuid,
    val commandId: Uuid?,
    val unlockedOwner: Boolean,
)

fun interface InvocationSurfaceContextProvider {
    fun currentContext(
        origin: ToolCallOrigin,
        conversationId: Uuid,
        commandId: Uuid?,
    ): InvocationSurfaceContext
}

fun InvocationSurfaceContext.toProviderAddendum(): String = when (presence) {
    InvocationSurfacePresence.FULL_CHAT ->
        "Invocation surface: RikkaHub full chat. Full in-app interaction cards are available."
    InvocationSurfacePresence.OVERLAY_VISIBLE ->
        "Invocation surface: Honor AI-key overlay is visible. Keep the response compact."
    InvocationSurfacePresence.RUNNING_AFTER_OVERLAY_CLOSED ->
        "Invocation surface: the AI-key overlay is closed, but this task is continuing in its original conversation. Do not claim that the user can still see the overlay."
    InvocationSurfacePresence.VOICE_SESSION_VISIBLE ->
        "Invocation surface: Android VoiceInteraction session is visible and has no Activity host."
    InvocationSurfacePresence.REMOTE_OR_WORKFLOW ->
        "Invocation surface: background, remote, or workflow origin (${origin.name}). Do not assume an interactive Activity is visible."
}
