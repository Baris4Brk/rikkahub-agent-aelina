package me.rerere.rikkahub.pet

import kotlin.uuid.Uuid
import me.rerere.rikkahub.assistant.SecondUserAuthorityConfig
import me.rerere.rikkahub.assistant.SecondUserAuthorityState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetOverlaySelectionTest {
    @Test
    fun `changing package clears an inherited visual profile`() {
        assertNull(
            resolvePetProfileForPackage(
                previousPackageId = "pet.nahida",
                previousProfileId = "profile.nahida",
                nextPackageId = "pet.klee",
            ),
        )
    }

    @Test
    fun `same package keeps profile while explicit replacement profile wins`() {
        assertEquals(
            "profile.nahida",
            resolvePetProfileForPackage("pet.nahida", "profile.nahida", "pet.nahida"),
        )
        assertEquals(
            "profile.klee.alt",
            resolvePetProfileForPackage(
                previousPackageId = "pet.nahida",
                previousProfileId = "profile.nahida",
                nextPackageId = "pet.klee",
                requestedProfileId = "profile.klee.alt",
            ),
        )
    }

    @Test
    fun `one legacy enabled pet migrates conservatively`() {
        val conversation = Uuid.random()
        val assistant = Assistant(
            id = Uuid.random(),
            petEnabled = true,
            privilegedConversationId = conversation,
            petPackageId = "codex.sample",
            petScale = 1.4f,
        )

        val resolved = Settings(
            assistants = listOf(assistant),
            secondUserAuthority = SecondUserAuthorityConfig(
                assistantId = assistant.id,
                conversationId = conversation,
                authorityEpoch = 1L,
                state = SecondUserAuthorityState.ACTIVE,
            ),
        ).resolvePetOverlaySelection()

        assertTrue(resolved?.migratedFromLegacy == true)
        assertEquals(assistant.id, resolved?.selection?.ownerAssistantId)
        assertEquals(conversation, resolved?.selection?.privilegedConversationId)
        assertEquals("codex.sample", resolved?.selection?.packageId)
    }

    @Test
    fun `ambiguous legacy pets never choose an owner`() {
        val first = Assistant(id = Uuid.random(), petEnabled = true, privilegedConversationId = Uuid.random())
        val second = Assistant(id = Uuid.random(), petEnabled = true, privilegedConversationId = Uuid.random())

        assertNull(Settings(assistants = listOf(first, second)).resolvePetOverlaySelection())
    }

    @Test
    fun `explicit global choice fails closed when conversation is reassigned`() {
        val assistant = Assistant(id = Uuid.random(), privilegedConversationId = Uuid.random(), petEnabled = true)
        val wrongConversation = Uuid.random()
        val settings = Settings(
            assistants = listOf(assistant),
            secondUserAuthority = SecondUserAuthorityConfig(
                assistantId = assistant.id,
                conversationId = assistant.privilegedConversationId,
                authorityEpoch = 1L,
                state = SecondUserAuthorityState.ACTIVE,
            ),
            petOverlaySelection = PetOverlaySelection(
                ownerAssistantId = assistant.id,
                privilegedConversationId = wrongConversation,
                enabled = true,
            ),
        )

        assertNull(settings.resolvePetOverlaySelection())
    }

    @Test
    fun `selection normalizes untrusted visual values`() {
        val normalized = PetOverlaySelection(
            ownerAssistantId = Uuid.random(),
            privilegedConversationId = Uuid.random(),
            scale = 99f,
            animationFps = 99,
            normalizedX = 3f,
            normalizedY = -1f,
        ).normalized()

        assertEquals(3f, normalized.scale)
        assertEquals(12, normalized.animationFps)
        assertEquals(1f, normalized.normalizedX)
        assertEquals(0f, normalized.normalizedY)
        assertFalse(normalized.idlePoolEnabled)

        val minimum = normalized.copy(scale = 0.001f).normalized()
        assertEquals(0.05f, minimum.scale)
    }
}
