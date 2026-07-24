package me.rerere.rikkahub.quickcapture

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class QuickCaptureModelsTest {
    @Test
    fun `settings default to disabled one tap full screen capture`() {
        val settings = QuickCaptureSettings()

        assertFalse(settings.enabled)
        assertEquals(QuickCaptureTargetMode.FOLLOW_SYSTEM_ASSISTANT, settings.targetMode)
        assertTrue(settings.autoSend)
        assertEquals(QuickCaptureBackendPreference.AUTO, settings.backend)
        assertEquals(QuickCaptureAreaMode.FULL_SCREEN, settings.areaMode)
        assertEquals(56, settings.bubbleSizeDp)
        assertEquals(0.9f, settings.bubbleOpacity)
        assertEquals(QuickCaptureBubbleEdge.RIGHT, settings.bubbleEdge)
    }

    @Test
    fun `settings json round trip preserves independent quick capture values`() {
        val original = QuickCaptureSettings(
            enabled = true,
            targetMode = QuickCaptureTargetMode.FIXED_ASSISTANT,
            prompt = "inspect this screen",
            backend = QuickCaptureBackendPreference.MEDIA_PROJECTION,
            areaMode = QuickCaptureAreaMode.SELECT_REGION,
            bubbleSizeDp = 72,
            bubbleOpacity = 0.65f,
            bubbleEdge = QuickCaptureBubbleEdge.LEFT,
            bubbleYFraction = 0.3f,
        )

        val decoded = JsonInstant.decodeFromString<QuickCaptureSettings>(
            JsonInstant.encodeToString(original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `normalization clamps only quick capture ranges`() {
        val normalized = QuickCaptureSettings(
            prompt = "x".repeat(QuickCaptureSettings.MAX_PROMPT_CHARS + 50),
            bubbleSizeDp = 999,
            bubbleOpacity = 0f,
            bubbleYFraction = 4f,
        ).normalized()

        assertEquals(QuickCaptureSettings.MAX_PROMPT_CHARS, normalized.prompt.length)
        assertEquals(QuickCaptureSettings.MAX_BUBBLE_SIZE_DP, normalized.bubbleSizeDp)
        assertEquals(QuickCaptureSettings.MIN_BUBBLE_OPACITY, normalized.bubbleOpacity)
        assertEquals(1f, normalized.bubbleYFraction)
    }

    @Test
    fun `batch admits at most eight images and thirty two mib`() {
        val attachments = (0 until QUICK_CAPTURE_MAX_IMAGES).map { index ->
            QuickCaptureAttachment(index.toLong(), "file:///quick-$index", 1, 1, 1, 0)
        }
        assertEquals(
            QuickCaptureBatchDecision.TooManyImages,
            decideQuickCaptureBatch(attachments, 1),
        )
        assertEquals(
            QuickCaptureBatchDecision.TooLarge,
            decideQuickCaptureBatch(emptyList(), QUICK_CAPTURE_MAX_TOTAL_BYTES + 1),
        )
        assertEquals(
            QuickCaptureBatchDecision.Accepted(1, 10),
            decideQuickCaptureBatch(emptyList(), 10),
        )
    }

    @Test
    fun `unaccepted failures are cleaned while accepted commands and drafts retain attachments`() {
        val attachment = QuickCaptureAttachment(1L, "file:///quick.png", 1, 1, 1, 0)

        assertTrue(
            shouldDiscardQuickCaptureAttachments(
                QuickCaptureUiState(stage = QuickCaptureStage.FAILED, attachments = listOf(attachment)),
            ),
        )
        assertTrue(
            shouldDiscardQuickCaptureAttachments(
                QuickCaptureUiState(stage = QuickCaptureStage.PERSISTING, attachments = listOf(attachment)),
            ),
        )
        assertFalse(
            shouldDiscardQuickCaptureAttachments(
                QuickCaptureUiState(
                    stage = QuickCaptureStage.QUEUED,
                    commandId = Uuid.random(),
                    attachments = listOf(attachment),
                ),
            ),
        )
        assertFalse(
            shouldDiscardQuickCaptureAttachments(
                QuickCaptureUiState(stage = QuickCaptureStage.COMPLETED, attachments = listOf(attachment)),
            ),
        )
    }
}
