package me.rerere.rikkahub.pet

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import me.rerere.rikkahub.assistant.SecondUserAuthorityState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

/**
 * The one global desktop-pet binding. Per-assistant pet fields remain readable only to migrate
 * older installs; a resolved selection always names both the assistant and fixed conversation.
 */
@Serializable
data class PetOverlaySelection(
    val ownerAssistantId: Uuid,
    val privilegedConversationId: Uuid,
    val enabled: Boolean = true,
    val packageId: String? = null,
    val profileId: String? = null,
    val normalizedX: Float? = null,
    val normalizedY: Float? = null,
    val scale: Float = 1f,
    val animationFps: Int = 6,
    val headBoundary: Float = 0.34f,
    val bodyBoundary: Float = 0.76f,
    val idlePoolEnabled: Boolean = false,
) {
    fun normalized(): PetOverlaySelection = copy(
        packageId = packageId?.takeIf { it.matches(SAFE_PACKAGE_ID) },
        profileId = profileId?.takeIf { it.matches(SAFE_PROFILE_ID) },
        normalizedX = normalizedX?.coerceIn(0f, 1f),
        normalizedY = normalizedY?.coerceIn(0f, 1f),
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        animationFps = animationFps.coerceIn(MIN_FPS, MAX_FPS),
        headBoundary = headBoundary.coerceIn(0.15f, 0.55f),
        bodyBoundary = bodyBoundary.coerceIn((headBoundary + 0.10f).coerceAtMost(0.85f), 0.95f),
    )

    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 3.0f
        const val MIN_FPS = 4
        const val MAX_FPS = 12
        private val SAFE_PACKAGE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        private val SAFE_PROFILE_ID = Regex("[a-z][a-z0-9._-]{0,63}")

        fun fromLegacy(assistant: Assistant): PetOverlaySelection? {
            val conversationId = assistant.privilegedConversationId ?: return null
            return PetOverlaySelection(
                ownerAssistantId = assistant.id,
                privilegedConversationId = conversationId,
                enabled = assistant.petEnabled,
                packageId = assistant.petPackageId,
                scale = assistant.petScale,
                animationFps = assistant.petAnimationFps,
                headBoundary = assistant.petHeadBoundary,
                bodyBoundary = assistant.petBodyBoundary,
                idlePoolEnabled = assistant.petIdlePoolEnabled,
            ).normalized()
        }
    }
}

data class ResolvedPetOverlaySelection(
    val selection: PetOverlaySelection,
    val assistant: Assistant,
    val migratedFromLegacy: Boolean,
)

/** Fail closed for a malformed saved choice; never silently choose a different assistant. */
fun Settings.resolvePetOverlaySelection(): ResolvedPetOverlaySelection? {
    val authority = secondUserAuthority.normalized()
        .takeIf { it.state == SecondUserAuthorityState.ACTIVE }
        ?: return null
    val explicit = petOverlaySelection?.normalized()
    if (explicit != null) {
        val assistant = assistants.firstOrNull { it.id == explicit.ownerAssistantId }
            ?: return null
        if (!explicit.enabled ||
            explicit.ownerAssistantId != authority.assistantId ||
            explicit.privilegedConversationId != authority.conversationId
        ) return null
        return ResolvedPetOverlaySelection(explicit, assistant, migratedFromLegacy = false)
    }
    val candidates = assistants.mapNotNull { assistant ->
        PetOverlaySelection.fromLegacy(assistant)
            ?.takeIf {
                it.ownerAssistantId == authority.assistantId &&
                    it.privilegedConversationId == authority.conversationId
            }
            ?.takeIf { it.enabled }
            ?.let { selection -> ResolvedPetOverlaySelection(selection, assistant, migratedFromLegacy = true) }
    }
    // Multiple legacy candidates were formerly ambiguous. Preserve all data and require a user
    // choice instead of guessing which assistant owns the one global overlay.
    return candidates.singleOrNull()
}
