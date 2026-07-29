package me.rerere.rikkahub.pet.behavior

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.pet.action.PetActionProfile

/** Process-local, redacted renderer diagnostics for Doctor. Never stores a package path or text. */
data class PetRuntimeDiagnosticSnapshot(
    val profileId: String? = null,
    val rendererType: String? = null,
    val supportedActionCount: Int = 0,
    val operationalActionId: String? = null,
    val activeOneShotActionId: String? = null,
    val displayedActionId: String? = null,
    val fallbackApplied: Boolean = false,
    val resourceValid: Boolean? = null,
)

class PetRuntimeDiagnostics {
    private val _snapshot = MutableStateFlow(PetRuntimeDiagnosticSnapshot())
    val snapshot: StateFlow<PetRuntimeDiagnosticSnapshot> = _snapshot.asStateFlow()

    fun updateProfile(profile: PetActionProfile) {
        _snapshot.value = _snapshot.value.copy(
            profileId = profile.profileId,
            rendererType = profile.rendererType,
            supportedActionCount = profile.capabilities.supportedActions.size,
            resourceValid = true,
        )
    }

    fun updateBehavior(state: PetBehaviorState) {
        _snapshot.value = _snapshot.value.copy(
            operationalActionId = state.operationalAction?.requestedAction?.value,
            activeOneShotActionId = state.activeOneShot?.requestedAction?.value,
            displayedActionId = state.displayedAction.resolvedAction.value,
            fallbackApplied = state.displayedAction.requestedAction != state.displayedAction.resolvedAction,
        )
    }

    fun markResourceInvalid() {
        _snapshot.value = _snapshot.value.copy(resourceValid = false)
    }

    fun clearRenderer() {
        _snapshot.value = PetRuntimeDiagnosticSnapshot()
    }
}
