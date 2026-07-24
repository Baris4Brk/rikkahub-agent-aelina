package me.rerere.rikkahub.quickcapture

import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.RikkaAccessibilityService

enum class ScreenCaptureBackendKind {
    ACCESSIBILITY,
    MEDIA_PROJECTION,
}

enum class CaptureFailureCode {
    API_TOO_LOW,
    ACCESSIBILITY_UNAVAILABLE,
    MEDIA_PROJECTION_NOT_AUTHORIZED,
    RATE_LIMITED,
    INVALID_DISPLAY,
    INVALID_WINDOW,
    SECURE_WINDOW,
    INTERNAL_ERROR,
    BITMAP_DECODE_FAILED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN,
}

sealed interface ScreenCaptureResult {
    data class Success(val capture: ManagedScreenCapture) : ScreenCaptureResult
    data class Failure(
        val code: CaptureFailureCode,
        val detail: String? = null,
    ) : ScreenCaptureResult
}

/** The caller owns [bitmap] and must recycle it after persistence or crop selection. */
data class ManagedScreenCapture(
    val bitmap: Bitmap,
    val backend: ScreenCaptureBackendKind,
    val displayId: Int,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

interface ScreenCaptureBackend {
    val kind: ScreenCaptureBackendKind

    suspend fun capture(displayId: Int): ScreenCaptureResult
}

class AccessibilityScreenCaptureBackend : ScreenCaptureBackend {
    override val kind: ScreenCaptureBackendKind = ScreenCaptureBackendKind.ACCESSIBILITY

    override suspend fun capture(displayId: Int): ScreenCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenCaptureResult.Failure(CaptureFailureCode.API_TOO_LOW)
        }
        val service = RikkaAccessibilityService.instance
            ?: return ScreenCaptureResult.Failure(CaptureFailureCode.ACCESSIBILITY_UNAVAILABLE)
        return when (val outcome = withTimeoutOrNull<RikkaAccessibilityService.ScreenshotOutcome>(
            ScreenCaptureManager.CAPTURE_TIMEOUT_MS,
        ) {
            service.captureScreenshot(displayId)
        }) {
            null -> ScreenCaptureResult.Failure(CaptureFailureCode.TIMED_OUT)
            is RikkaAccessibilityService.ScreenshotOutcome.Success -> ScreenCaptureResult.Success(
                ManagedScreenCapture(
                    bitmap = outcome.bitmap,
                    backend = kind,
                    displayId = displayId,
                )
            )
            is RikkaAccessibilityService.ScreenshotOutcome.Failure -> ScreenCaptureResult.Failure(
                code = outcome.reason.toCaptureFailureCode(),
                detail = outcome.reason,
            )
        }
    }
}

class MediaProjectionScreenCaptureBackend(
    private val session: QuickCaptureProjectionSession = QuickCaptureProjectionSession,
) : ScreenCaptureBackend {
    override val kind: ScreenCaptureBackendKind = ScreenCaptureBackendKind.MEDIA_PROJECTION

    override suspend fun capture(displayId: Int): ScreenCaptureResult =
        session.capture(displayId)
}

class ScreenCaptureManager(
    private val accessibility: ScreenCaptureBackend = AccessibilityScreenCaptureBackend(),
    private val mediaProjection: ScreenCaptureBackend = MediaProjectionScreenCaptureBackend(),
) {
    fun isBackendAvailable(preference: QuickCaptureBackendPreference): Boolean = when (preference) {
        QuickCaptureBackendPreference.ACCESSIBILITY -> isAccessibilityAvailable()
        QuickCaptureBackendPreference.MEDIA_PROJECTION -> isMediaProjectionAvailable()
        QuickCaptureBackendPreference.AUTO -> isAccessibilityAvailable() || isMediaProjectionAvailable()
    }

    fun isAccessibilityAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && RikkaAccessibilityService.instance != null

    fun isMediaProjectionAvailable(): Boolean =
        QuickCaptureProjectionSession.state.value is QuickCaptureProjectionState.Ready

    suspend fun capture(
        preference: QuickCaptureBackendPreference,
        displayId: Int = DEFAULT_DISPLAY_ID,
    ): ScreenCaptureResult = when (preference) {
        QuickCaptureBackendPreference.ACCESSIBILITY -> accessibility.capture(displayId)
        QuickCaptureBackendPreference.MEDIA_PROJECTION -> mediaProjection.capture(displayId)
        QuickCaptureBackendPreference.AUTO -> captureAutomatic(displayId)
    }

    private suspend fun captureAutomatic(displayId: Int): ScreenCaptureResult {
        val accessibilityResult = accessibility.capture(displayId)
        if (accessibilityResult is ScreenCaptureResult.Success) return accessibilityResult
        val failure = accessibilityResult as ScreenCaptureResult.Failure
        return if (failure.code in AUTO_FALLBACK_CODES) {
            mediaProjection.capture(displayId)
        } else {
            failure
        }
    }

    companion object {
        const val DEFAULT_DISPLAY_ID: Int = 0
        internal const val CAPTURE_TIMEOUT_MS: Long = 12_000L
        private val AUTO_FALLBACK_CODES = setOf(
            CaptureFailureCode.API_TOO_LOW,
            CaptureFailureCode.ACCESSIBILITY_UNAVAILABLE,
            CaptureFailureCode.INTERNAL_ERROR,
            CaptureFailureCode.BITMAP_DECODE_FAILED,
            CaptureFailureCode.TIMED_OUT,
        )
    }
}

fun String.toCaptureFailureCode(): CaptureFailureCode = when (this) {
    "api_too_low" -> CaptureFailureCode.API_TOO_LOW
    "no_access" -> CaptureFailureCode.ACCESSIBILITY_UNAVAILABLE
    "rate_limited" -> CaptureFailureCode.RATE_LIMITED
    "invalid_display" -> CaptureFailureCode.INVALID_DISPLAY
    "invalid_window" -> CaptureFailureCode.INVALID_WINDOW
    "secure_window" -> CaptureFailureCode.SECURE_WINDOW
    "internal_error" -> CaptureFailureCode.INTERNAL_ERROR
    "bitmap_decode_failed" -> CaptureFailureCode.BITMAP_DECODE_FAILED
    else -> if (startsWith("exception:")) CaptureFailureCode.INTERNAL_ERROR else CaptureFailureCode.UNKNOWN
}
