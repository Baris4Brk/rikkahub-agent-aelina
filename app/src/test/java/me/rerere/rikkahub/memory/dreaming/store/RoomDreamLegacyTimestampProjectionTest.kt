package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomDreamLegacyTimestampProjectionTest {
    @Test
    fun `legacy zero updated timestamp projects to created timestamp`() {
        val memory = memory(
            sourceType = "LEGACY",
            createdAtMs = 1_784_366_264_542L,
            updatedAtMs = 0L,
        )

        assertEquals(memory.createdAtMs, memory.dreamUpdatedAtEpochMsOrNull())
    }

    @Test
    fun `normal timestamp is preserved`() {
        val memory = memory(
            sourceType = "AUTO_EXTRACTION",
            createdAtMs = 100L,
            updatedAtMs = 150L,
        )

        assertEquals(150L, memory.dreamUpdatedAtEpochMsOrNull())
    }

    @Test
    fun `modern timestamp inversion remains rejected`() {
        val memory = memory(
            sourceType = "AUTO_EXTRACTION",
            createdAtMs = 100L,
            updatedAtMs = 0L,
        )

        assertNull(memory.dreamUpdatedAtEpochMsOrNull())
    }

    @Test
    fun `legacy nonzero timestamp inversion remains rejected`() {
        val memory = memory(
            sourceType = "LEGACY",
            createdAtMs = 100L,
            updatedAtMs = 50L,
        )

        assertNull(memory.dreamUpdatedAtEpochMsOrNull())
    }

    private fun memory(
        sourceType: String,
        createdAtMs: Long,
        updatedAtMs: Long,
    ) = MemoryEntity(
        id = 1,
        assistantId = "__global__",
        content = "legacy memory",
        sourceType = sourceType,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}
