package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.memory.MemoryAutoSaveMode
import me.rerere.rikkahub.memory.MemoryCaptureOrigin
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantMemoryV2SerializationTest {
    @Test
    fun `an upgraded assistant keeps automatic memory off with safe local origins`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"id":"00000000-0000-0000-0000-000000000001"}""",
        )

        assertEquals(MemoryAutoSaveMode.OFF, assistant.memoryAutoSaveMode)
        assertEquals(
            setOf(MemoryCaptureOrigin.APP_UI, MemoryCaptureOrigin.SYSTEM_ASSISTANT),
            assistant.memoryCaptureOrigins,
        )
        assertEquals(10, assistant.memoryIdleDelayMinutes)
        assertEquals(5, assistant.memoryImmediateCaptureThreshold)
    }
}
