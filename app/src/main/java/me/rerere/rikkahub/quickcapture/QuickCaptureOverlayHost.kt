package me.rerere.rikkahub.quickcapture

import android.graphics.Bitmap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The only UI seam the coordinator needs. The overlay implementation hides every visible
 * component for two frames before capture and owns/recycles the source bitmap during crop choice.
 */
interface QuickCaptureOverlayHost {
    suspend fun hideForCapture()

    suspend fun restoreAfterCapture()

    /** Returns an owned cropped bitmap or null. This method always consumes [source]. */
    suspend fun selectRegion(source: Bitmap): Bitmap?
}

/**
 * Shares the capture-visibility invariant between an actual submission and the settings preview.
 * The concrete overlay waits two frames in [QuickCaptureOverlayHost.hideForCapture]; this helper
 * guarantees its restore path remains paired with every success, error, and coroutine cancel.
 */
internal suspend fun <T> withQuickCaptureOverlayHidden(
    host: QuickCaptureOverlayHost?,
    block: suspend () -> T,
): T = try {
    host?.hideForCapture()
    block()
} finally {
    withContext(NonCancellable) {
        runCatching { host?.restoreAfterCapture() }
    }
}
