package me.rerere.rikkahub.owner

import me.rerere.rikkahub.data.ai.tools.ownerActionGuideCoverageGaps
import me.rerere.rikkahub.data.ai.tools.ownerToolSchemaUtf8Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerActionRegistryTest {
    @Test
    fun `registry preserves every legacy family and action while exposing playback speed`() {
        assertEquals(OwnerToolFamily.entries.toSet(), OwnerActionRegistry.families.map { it.family }.toSet())
        assertEquals(140, OwnerActionRegistry.actionCount())
        assertTrue(OwnerActionRegistry.action(OwnerToolFamily.TTS, "tts_get_playback_speed") != null)
        assertTrue(OwnerActionRegistry.action(OwnerToolFamily.TTS, "tts_set_playback_speed") != null)
        assertTrue(ownerActionGuideCoverageGaps().isEmpty())
    }

    @Test
    fun `direct owner schemas stay inside the fixed token-cost byte budget`() {
        val sizes = ownerToolSchemaUtf8Bytes()
        assertTrue(sizes.entries.joinToString { "${it.key}=${it.value}" }, sizes.values.all { it <= 12 * 1024 })
        assertTrue("total=${sizes.values.sum()}", sizes.values.sum() <= 64 * 1024)
    }
}
