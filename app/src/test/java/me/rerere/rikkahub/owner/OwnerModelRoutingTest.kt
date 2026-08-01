package me.rerere.rikkahub.owner

import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class OwnerModelRoutingTest {
    @Test
    fun `replacement type reports chat and image routes separately`() {
        val chat = Uuid.random()
        val image = Uuid.random()
        val settings = Settings.dummy().copy(
            chatModelId = chat,
            imageGenerationModelId = image,
        )

        assertEquals(setOf(ModelType.CHAT), settings.ownerReferencedModelTypes(setOf(chat)))
        assertEquals(setOf(ModelType.IMAGE), settings.ownerReferencedModelTypes(setOf(image)))
        assertEquals(setOf(ModelType.CHAT, ModelType.IMAGE), settings.ownerReferencedModelTypes(setOf(chat, image)))
    }

    @Test
    fun `replacement rewrites active routes and assistant overrides atomically`() {
        val removed = Uuid.random()
        val replacement = Uuid.random()
        val settings = Settings.dummy().copy(
            chatModelId = removed,
            fastModelId = removed,
            favoriteModels = listOf(removed),
            assistants = Settings.dummy().assistants.map { assistant ->
                assistant.copy(chatModelId = removed, subAgentModelId = removed)
            },
        )

        val updated = settings.ownerReplaceModelReferences(setOf(removed), replacement)

        assertEquals(replacement, updated.chatModelId)
        assertEquals(replacement, updated.fastModelId)
        assertEquals(emptyList<Uuid>(), updated.favoriteModels)
        updated.assistants.forEach { assistant ->
            assertEquals(replacement, assistant.chatModelId)
            assertEquals(replacement, assistant.subAgentModelId)
        }
    }
}
