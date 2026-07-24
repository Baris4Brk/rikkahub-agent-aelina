package me.rerere.rikkahub.quickcapture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureManagerTest {
    @Test
    fun `accessibility official display and secure window failures stay distinct`() {
        assertEquals(CaptureFailureCode.INVALID_DISPLAY, "invalid_display".toCaptureFailureCode())
        assertEquals(CaptureFailureCode.INVALID_WINDOW, "invalid_window".toCaptureFailureCode())
        assertEquals(CaptureFailureCode.SECURE_WINDOW, "secure_window".toCaptureFailureCode())
        assertEquals(CaptureFailureCode.RATE_LIMITED, "rate_limited".toCaptureFailureCode())
    }

    @Test
    fun `auto falls back only for unavailable internal or timeout accessibility failures`() = runBlocking {
        val accessibility = FakeBackend(
            ScreenCaptureBackendKind.ACCESSIBILITY,
            ScreenCaptureResult.Failure(CaptureFailureCode.TIMED_OUT),
        )
        val projection = FakeBackend(
            ScreenCaptureBackendKind.MEDIA_PROJECTION,
            ScreenCaptureResult.Failure(CaptureFailureCode.MEDIA_PROJECTION_NOT_AUTHORIZED),
        )
        val result = ScreenCaptureManager(accessibility, projection).capture(QuickCaptureBackendPreference.AUTO)

        assertEquals(1, accessibility.calls)
        assertEquals(1, projection.calls)
        assertEquals(CaptureFailureCode.MEDIA_PROJECTION_NOT_AUTHORIZED, (result as ScreenCaptureResult.Failure).code)
    }

    @Test
    fun `auto never replaces a secure display failure with another backend`() = runBlocking {
        val accessibility = FakeBackend(
            ScreenCaptureBackendKind.ACCESSIBILITY,
            ScreenCaptureResult.Failure(CaptureFailureCode.SECURE_WINDOW),
        )
        val projection = FakeBackend(
            ScreenCaptureBackendKind.MEDIA_PROJECTION,
            ScreenCaptureResult.Failure(CaptureFailureCode.INTERNAL_ERROR),
        )
        val result = ScreenCaptureManager(accessibility, projection).capture(QuickCaptureBackendPreference.AUTO)

        assertEquals(1, accessibility.calls)
        assertEquals(0, projection.calls)
        assertEquals(CaptureFailureCode.SECURE_WINDOW, (result as ScreenCaptureResult.Failure).code)
    }

    private class FakeBackend(
        override val kind: ScreenCaptureBackendKind,
        private val result: ScreenCaptureResult,
    ) : ScreenCaptureBackend {
        var calls = 0
        override suspend fun capture(displayId: Int): ScreenCaptureResult {
            calls++
            return result
        }
    }
}
