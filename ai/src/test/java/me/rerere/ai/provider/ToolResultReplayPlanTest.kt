package me.rerere.ai.provider

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.EncodedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultReplayPlanTest {
    private val output = listOf(
        UIMessagePart.Text("before"),
        UIMessagePart.Image("file://tool-image.png"),
        UIMessagePart.Text("after"),
    )

    @Test
    fun `vision model keeps text and encoded image in original order`() {
        val plan = ToolResultReplayPlan.create(
            output = output,
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            encodeImage = { Result.success(EncodedImage(DATA_URL, "image/png")) },
        )

        assertEquals(
            listOf(
                ToolResultReplayItem.Text("before"),
                ToolResultReplayItem.Image(DATA_URL, "image/png", "AA=="),
                ToolResultReplayItem.Text("after"),
            ),
            plan.items,
        )
        assertTrue(plan.hasImages)
    }

    @Test
    fun `text model replaces image at the same position with an omission`() {
        val plan = ToolResultReplayPlan.create(
            output = output,
            inputModalities = listOf(Modality.TEXT),
            encodeImage = { error("text-only planning must not encode images") },
        )

        assertEquals(
            listOf(
                ToolResultReplayItem.Text("before"),
                ToolResultReplayItem.Text(ToolResultReplayPlan.IMAGE_OMITTED_TEXT),
                ToolResultReplayItem.Text("after"),
            ),
            plan.items,
        )
        assertFalse(plan.hasImages)
    }

    @Test
    fun `encoding failure becomes a stable text item without exception details`() {
        val plan = ToolResultReplayPlan.create(
            output = output,
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            encodeImage = { Result.failure(IllegalStateException("private file path")) },
        )

        assertEquals(
            ToolResultReplayItem.Text(ToolResultReplayPlan.IMAGE_ENCODING_FAILED_TEXT),
            plan.items[1],
        )
        assertFalse(plan.items.joinToString().contains("private file path"))
        assertFalse(plan.hasImages)
    }

    private companion object {
        const val DATA_URL = "data:image/png;base64,AA=="
    }
}
