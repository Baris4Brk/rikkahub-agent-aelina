package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.quickcapture.QuickCaptureCoordinator
import me.rerere.rikkahub.quickcapture.QuickCaptureMediaProjectionService
import me.rerere.rikkahub.quickcapture.QuickCaptureOverlayService
import me.rerere.rikkahub.quickcapture.QuickCapturePreview
import me.rerere.rikkahub.quickcapture.QuickCaptureProjectionSession
import me.rerere.rikkahub.quickcapture.QuickCaptureStartEligibility

class QuickCaptureSettingsViewModel(
    context: Context,
    private val settingsStore: SettingsStore,
    private val coordinator: QuickCaptureCoordinator,
) : ViewModel() {
    private val appContext = context.applicationContext
    val settings: StateFlow<Settings> = settingsStore.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        Settings.dummy(),
    )
    val projectionState = QuickCaptureProjectionSession.state
    private val _eligibility = MutableStateFlow<QuickCaptureStartEligibility?>(null)
    val eligibility = _eligibility.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _preview = MutableStateFlow<QuickCapturePreview?>(null)
    val preview = _preview.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settingsFlow.collectLatest { current ->
                if (!current.init) refreshEligibility()
            }
        }
    }

    fun update(transform: (me.rerere.rikkahub.quickcapture.QuickCaptureSettings) ->
        me.rerere.rikkahub.quickcapture.QuickCaptureSettings) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(quickCaptureSettings = transform(current.quickCaptureSettings).normalized())
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                when (val checked = coordinator.preflightStart()) {
                    is QuickCaptureStartEligibility.Ready -> {
                        settingsStore.update { current ->
                            current.copy(quickCaptureSettings = current.quickCaptureSettings.copy(enabled = true))
                        }
                        QuickCaptureOverlayService.start(appContext)
                        _notice.value = null
                    }
                    is QuickCaptureStartEligibility.Blocked -> _notice.value = checked.code
                }
            } else {
                settingsStore.update { current ->
                    current.copy(quickCaptureSettings = current.quickCaptureSettings.copy(enabled = false))
                }
                appContext.startService(QuickCaptureOverlayService.stopIntent(appContext))
            }
        }
    }

    fun installProjection(resultCode: Int, data: Intent) {
        QuickCaptureMediaProjectionService.install(appContext, resultCode, data)
        _notice.value = null
    }

    fun testCapture() {
        viewModelScope.launch {
            val result = coordinator.capturePreview()
            _notice.value = result.fold(
                onSuccess = { preview ->
                    replacePreview(preview)
                    "preview:${preview.width}x${preview.height}:${preview.backend.name}"
                },
                onFailure = { failure ->
                    clearPreview()
                    failure.message ?: "preview_failed"
                },
            )
        }
    }

    fun refreshEligibility() {
        viewModelScope.launch {
            _eligibility.value = coordinator.preflightStart()
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Preview pixels are memory-only and are released as soon as the page leaves. */
    fun clearPreview() {
        val previous = _preview.value
        _preview.value = null
        previous?.bitmap?.takeUnless { it.isRecycled }?.recycle()
    }

    private fun replacePreview(next: QuickCapturePreview) {
        val previous = _preview.value
        _preview.value = next
        previous?.bitmap?.takeUnless { it.isRecycled }?.recycle()
    }

    override fun onCleared() {
        clearPreview()
        super.onCleared()
    }

    fun diagnostics(): String {
        val quick = settings.value.quickCaptureSettings.normalized()
        val target = (_eligibility.value as? QuickCaptureStartEligibility.Ready)?.target
        return buildString {
            appendLine("quick_capture_enabled=${quick.enabled}")
            appendLine("target_mode=${quick.targetMode.name}")
            appendLine("backend=${quick.backend.name}")
            appendLine("area_mode=${quick.areaMode.name}")
            appendLine("overlay_permission=${android.provider.Settings.canDrawOverlays(appContext)}")
            appendLine("accessibility_online=${me.rerere.rikkahub.service.RikkaAccessibilityService.instance != null}")
            appendLine("projection_state=${projectionState.value::class.simpleName}")
            target?.let {
                appendLine("assistant=${it.assistantName}:${it.assistantId.toString().take(8)}")
                appendLine("second_user_conversation=${it.conversationTitle}:${it.conversationId.toString().take(8)}")
            }
            (_eligibility.value as? QuickCaptureStartEligibility.Blocked)?.let {
                appendLine("start_block=${it.code}")
            }
        }.trim()
    }
}
