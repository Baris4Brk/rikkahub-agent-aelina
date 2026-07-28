package me.rerere.rikkahub.pet

import kotlin.uuid.Uuid
import me.rerere.rikkahub.assistant.SecondUserPresentationStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.pet.render.PetFrameClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.ToolCallOrigin

class PetPolicyTest {
    @Test
    fun `safety states always map above local idle`() {
        assertEquals(PetAction.FAILED, PetPresentationMapper.action(SecondUserPresentationStatus.SAFETY_BLOCKED))
        assertEquals(PetAction.REVIEW, PetPresentationMapper.action(SecondUserPresentationStatus.WAITING_APPROVAL))
        assertEquals(PetAction.WAITING, PetPresentationMapper.action(SecondUserPresentationStatus.STALE))
    }

    @Test
    fun `bubble sanitizer redacts credentials paths and bounds code points`() {
        val result = PetBubbleSanitizer.sanitize("token=abc C:\\private\\file.txt " + "猫".repeat(120))
        assertFalse(result.contains("abc"))
        assertFalse(result.contains("private"))
        assertTrue(result.codePointCount(0, result.length) <= MAX_PET_RESPONSE_CODE_POINTS)
    }

    @Test
    fun `persona projection has stable budget and diagnostics`() {
        val assistant = Assistant(
            id = Uuid.random(),
            name = "Pet",
            systemPrompt = "a".repeat(9_000),
            petSupplement = "tail",
        )
        val projection = PetPersonaSource.buildProjection(assistant)
        assertEquals(MAX_PET_PERSONA_CHARS, projection.personaPrompt.length)
        assertTrue(projection.truncated)
    }

    @Test
    fun `frame clock uses bounded loop`() {
        val clock = PetFrameClock(20)
        assertEquals(0, clock.frameIndex(0, 6))
        assertEquals(1, clock.frameIndex(50, 6))
        assertEquals(0, clock.frameIndex(300, 6))
    }

    @Test
    fun `session policy archives before round 21 and rolls dates without empty diary`() {
        assertEquals(PetSessionRollAction.ARCHIVE_CAPACITY, PetSessionPolicy.beforeAppend("2026-07-29", "2026-07-29", 20))
        assertEquals(PetSessionRollAction.ARCHIVE_DAILY, PetSessionPolicy.beforeAppend("2026-07-28", "2026-07-29", 3))
        assertEquals(PetSessionRollAction.ROLL_EMPTY_DATE, PetSessionPolicy.beforeAppend("2026-07-28", "2026-07-29", 0))
    }

    @Test
    fun `auto handoff is rate limited and diary origins fail closed`() {
        assertFalse(PetAutoHandoffPolicy.canSubmit(100_000, 99_000, false))
        assertFalse(PetAutoHandoffPolicy.canSubmit(2_000_000, null, true))
        assertTrue(PetAutoHandoffPolicy.canSubmit(2_000_000, 1_000, false))
        assertTrue(PetDiaryAccessPolicy.allowsOrigin(ToolCallOrigin.LocalChat))
        assertFalse(PetDiaryAccessPolicy.allowsOrigin(ToolCallOrigin.Telegram))
        assertFalse(PetDiaryAccessPolicy.allowsOrigin(ToolCallOrigin.PetInteraction))
    }
}
