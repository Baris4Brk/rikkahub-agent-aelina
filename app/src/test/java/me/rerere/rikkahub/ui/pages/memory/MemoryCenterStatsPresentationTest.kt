package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.db.dao.MemoryCaptureStatusCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryCenterStatsPresentationTest {
    @Test
    fun `queue statistics keep processed no-signal and failure outcomes distinct`() {
        val stats = memoryCenterStats(
            active = 7,
            archived = 2,
            pendingReview = 4,
            captures = MemoryCaptureStatusCounts(
                pendingCaptures = 3,
                processingCaptures = 1,
                processedCaptures = 12,
                noLongTermSignalCaptures = 8,
                failedCaptures = 2,
                pausedCaptures = 5,
                discardedCaptures = 1,
            ),
            lastProcessedAtMs = 9_000L,
        )

        assertEquals(3, stats.pendingCaptures)
        assertEquals(1, stats.processingCaptures)
        assertEquals(12, stats.processedCaptures)
        assertEquals(8, stats.noLongTermSignalCaptures)
        assertEquals(2, stats.failedCaptures)
        assertEquals(5, stats.pausedCaptures)
        assertEquals(1, stats.discardedCaptures)
        assertEquals(9_000L, stats.lastProcessedAtMs)
    }
}
