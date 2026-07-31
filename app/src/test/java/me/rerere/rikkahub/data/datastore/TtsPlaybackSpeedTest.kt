package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackSpeedTest {
    @Test
    fun `playback speed is bounded and rounded to one decimal step`() {
        assertEquals(0.5f, 0.1f.normalizedTtsPlaybackSpeed())
        assertEquals(2.0f, 3.0f.normalizedTtsPlaybackSpeed())
        assertEquals(1.3f, 1.26f.normalizedTtsPlaybackSpeed())
        assertEquals(1.0f, Float.NaN.normalizedTtsPlaybackSpeed())
    }
}
