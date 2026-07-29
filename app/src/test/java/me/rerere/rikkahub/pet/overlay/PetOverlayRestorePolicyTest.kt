package me.rerere.rikkahub.pet.overlay

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetOverlayRestorePolicyTest {
    @Test
    fun `opening the app restores an enabled configured pet without enabling boot restore`() {
        val assistant = Assistant(
            id = Uuid.random(),
            name = "Second user",
            privilegedConversationId = Uuid.random(),
            petEnabled = true,
            petBootRestoreEnabled = false,
        )

        assertTrue(PetOverlayRestorePolicy.shouldRestoreOnAppForeground(listOf(assistant), true))
        assertFalse(PetOverlayRestorePolicy.shouldRestoreOnAppForeground(listOf(assistant), false))
        assertFalse(
            PetOverlayRestorePolicy.shouldRestoreOnAppForeground(
                listOf(assistant.copy(privilegedConversationId = null)),
                true,
            ),
        )
    }
}
