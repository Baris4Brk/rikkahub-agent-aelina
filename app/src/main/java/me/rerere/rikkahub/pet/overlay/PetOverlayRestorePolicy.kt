package me.rerere.rikkahub.pet.overlay

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.pet.PetOverlaySelection

/**
 * An explicit app launch is enough to restore a pet the user already enabled.
 *
 * This is intentionally separate from the opt-in boot receiver: Android boot remains disabled
 * by default, while opening RikkaHub is a direct foreground user action.
 */
object PetOverlayRestorePolicy {
    fun shouldRestoreOnAppForeground(
        selection: PetOverlaySelection?,
        overlayPermissionGranted: Boolean,
    ): Boolean = overlayPermissionGranted && selection?.enabled == true

    /** Legacy helper kept for callers/tests that have not yet loaded a global selection. */
    fun shouldRestoreOnAppForeground(
        assistants: List<Assistant>,
        overlayPermissionGranted: Boolean,
    ): Boolean = overlayPermissionGranted && assistants.any { assistant ->
        assistant.petEnabled && assistant.privilegedConversationId != null
    }
}
