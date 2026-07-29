package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CapabilityGrantOriginMigrationTest {
    @Test
    fun `exact legacy second user origins gain confirmed local surfaces`() {
        val upgraded = normalizeStoredGrantOrigins(
            subjectType = SubjectType.LOCAL_SECOND_USER,
            origins = setOf(ToolCallOrigin.LocalChat, ToolCallOrigin.SystemAssistant),
        )

        assertEquals(InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER, upgraded)
        assertFalse(ToolCallOrigin.PetHandoffAuto in upgraded)
    }

    @Test
    fun `custom and non second user grants are never broadened`() {
        val custom = setOf(ToolCallOrigin.LocalChat)
        assertEquals(
            custom,
            normalizeStoredGrantOrigins(SubjectType.LOCAL_SECOND_USER, custom),
        )
        val ordinaryLegacy = setOf(ToolCallOrigin.LocalChat, ToolCallOrigin.SystemAssistant)
        assertEquals(
            ordinaryLegacy,
            normalizeStoredGrantOrigins(SubjectType.LOCAL_ASSISTANT, ordinaryLegacy),
        )
    }
}
