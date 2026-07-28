package me.rerere.rikkahub.pet.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TrustedApprovalSurfaceVisibility {
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()

    fun setVisible(value: Boolean) {
        mutableVisible.value = value
    }
}
