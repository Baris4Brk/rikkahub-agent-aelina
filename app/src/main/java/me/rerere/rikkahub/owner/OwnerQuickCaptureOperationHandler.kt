package me.rerere.rikkahub.owner

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.quickcapture.QuickCaptureInvocationRegistry
import me.rerere.rikkahub.quickcapture.QuickCaptureOverlayService

/** Opens the existing trusted capture surface; Android consent remains an OS-owned boundary. */
class OwnerQuickCaptureOperationHandler(
    context: Context,
) : OwnerOperationHandler {
    private val appContext = context.applicationContext

    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.QUICK_CAPTURE && action.type == "quick_capture_trigger"

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = when {
        action.arguments.isNotEmpty() -> invalid("OWNER_UNSUPPORTED_FIELD", "quick_capture_trigger does not accept arguments.")
        !Settings.canDrawOverlays(appContext) -> invalid("NEEDS_USER_ACTION", "Android overlay permission is required.")
        else -> OwnerActionValidation(true, "QUICK_CAPTURE_TRIGGER_VALID", "Trusted capture overlay can be opened.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        QuickCaptureOverlayService.start(appContext)
        OwnerAppliedAction(
            OwnerActionResult(index, action.type, true, "QUICK_CAPTURE_OPENING", "Trusted Quick Capture overlay is opening."),
            OverlayStarted,
        )
    }.getOrElse {
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, "QUICK_CAPTURE_START_FAILED", "Quick Capture overlay could not be started."))
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val visible = withTimeoutOrNull(3_000L) {
            while (!QuickCaptureInvocationRegistry.hasVisibleOverlay()) delay(25L)
            true
        } == true
        return if (visible) OwnerActionValidation(true, "QUICK_CAPTURE_VISIBLE", "Trusted capture overlay visibility was verified.")
        else invalid("QUICK_CAPTURE_NOT_VISIBLE", "Capture surface did not become visible; check backend readiness and system permissions.")
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = runCatching {
        appContext.startService(QuickCaptureOverlayService.stopIntent(appContext))
        OwnerCompensationResult(true, "QUICK_CAPTURE_OVERLAY_STOPPED")
    }.getOrElse { OwnerCompensationResult(false, "QUICK_CAPTURE_STOP_FAILED") }

    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private data object OverlayStarted
}
