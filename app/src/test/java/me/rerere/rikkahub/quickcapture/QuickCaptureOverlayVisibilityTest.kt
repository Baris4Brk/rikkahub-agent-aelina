package me.rerere.rikkahub.quickcapture

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCaptureOverlayVisibilityTest {
    @Test
    fun `visibility is restored after a successful capture`() = runBlocking {
        val host = RecordingHost()

        val result = withQuickCaptureOverlayHidden(host) {
            host.events += "capture"
            "done"
        }

        assertEquals("done", result)
        assertEquals(listOf("hide", "capture", "restore"), host.events)
    }

    @Test
    fun `visibility is restored after a capture failure or cancellation`() = runBlocking {
        val failureHost = RecordingHost()
        runCatching {
            withQuickCaptureOverlayHidden(failureHost) {
                failureHost.events += "capture"
                error("capture failed")
            }
        }
        assertEquals(listOf("hide", "capture", "restore"), failureHost.events)

        val cancelledHost = RecordingHost(suspendRestoration = true)
        try {
            withQuickCaptureOverlayHidden(cancelledHost) {
                cancelledHost.events += "capture"
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
            // Expected: restoration must not turn cancellation into a normal completion.
        }
        assertEquals(listOf("hide", "capture", "restore"), cancelledHost.events)
    }

    private class RecordingHost(
        private val suspendRestoration: Boolean = false,
    ) : QuickCaptureOverlayHost {
        val events = mutableListOf<String>()

        override suspend fun hideForCapture() {
            events += "hide"
        }

        override suspend fun restoreAfterCapture() {
            if (suspendRestoration) delay(1)
            events += "restore"
        }

        override suspend fun selectRegion(source: Bitmap): Bitmap? = null
    }
}
