package me.rerere.rikkahub.assistant

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SecondUserTargetResolverTest {
    @Test
    fun `unselected target is reported without reading conversations`() = runBlocking {
        var conversationReads = 0
        val resolver = SecondUserTargetResolver(
            settingsReader = SecondUserTargetSettingsReader {
                Settings(systemAssistantTargetAssistantId = null)
            },
            conversationReader = SecondUserTargetConversationReader {
                conversationReads++
                null
            },
        )

        assertEquals(SecondUserTargetResolution.TargetNotSelected, resolver.resolve())
        assertEquals(0, conversationReads)
    }

    @Test
    fun `missing configured assistant is reported`() = runBlocking {
        val assistantId = Uuid.random()
        val result = resolver(
            Settings(
                systemAssistantTargetAssistantId = assistantId,
                assistants = emptyList(),
            )
        ).resolve()

        assertEquals(SecondUserTargetResolution.AssistantNotFound(assistantId), result)
    }

    @Test
    fun `assistant without privileged conversation is reported`() = runBlocking {
        val assistant = Assistant(privilegedConversationId = null)
        val result = resolver(
            Settings(
                systemAssistantTargetAssistantId = assistant.id,
                assistants = listOf(assistant),
            )
        ).resolve()

        assertEquals(
            SecondUserTargetResolution.PrivilegedConversationNotConfigured(assistant.id),
            result,
        )
    }

    @Test
    fun `missing privileged conversation is reported`() = runBlocking {
        val conversationId = Uuid.random()
        val assistant = Assistant(privilegedConversationId = conversationId)
        val result = resolver(
            settings = Settings(
                systemAssistantTargetAssistantId = assistant.id,
                assistants = listOf(assistant),
            ),
        ).resolve()

        assertEquals(
            SecondUserTargetResolution.ConversationNotFound(assistant.id, conversationId),
            result,
        )
    }

    @Test
    fun `conversation owned by another assistant is reported`() = runBlocking {
        val conversationId = Uuid.random()
        val assistant = Assistant(privilegedConversationId = conversationId)
        val actualAssistantId = Uuid.random()
        val result = resolver(
            settings = Settings(
                systemAssistantTargetAssistantId = assistant.id,
                assistants = listOf(assistant),
            ),
            conversationOwners = mapOf(conversationId to actualAssistantId),
        ).resolve()

        assertEquals(
            SecondUserTargetResolution.ConversationAssistantMismatch(
                assistantId = assistant.id,
                conversationId = conversationId,
                actualAssistantId = actualAssistantId,
            ),
            result,
        )
    }

    @Test
    fun `valid target routes to privileged conversation but displays trimmed owner name`() = runBlocking {
        val conversationId = Uuid.random()
        val assistant = Assistant(
            privilegedConversationId = conversationId,
            privilegedIdentityName = "Seven",
        )
        val result = resolver(
            settings = Settings(
                systemAssistantTargetAssistantId = assistant.id,
                assistants = listOf(assistant),
                displaySetting = DisplaySetting(userNickname = "  Stuie  "),
            ),
            conversationOwners = mapOf(conversationId to assistant.id),
        ).resolve()

        assertEquals(
            SecondUserTargetResolution.Resolved(
                assistantId = assistant.id,
                conversationId = conversationId,
                displayName = "Stuie",
                assistantName = assistant.name,
            ),
            result,
        )
    }

    @Test
    fun `blank owner name falls back without exposing the privileged identity`() = runBlocking {
        val conversationId = Uuid.random()
        val assistant = Assistant(
            privilegedConversationId = conversationId,
            privilegedIdentityName = "Seven",
        )
        val result = resolver(
            settings = Settings(
                systemAssistantTargetAssistantId = assistant.id,
                assistants = listOf(assistant),
                displaySetting = DisplaySetting(userNickname = "   "),
            ),
            conversationOwners = mapOf(conversationId to assistant.id),
        ).resolve()

        assertEquals(
            DEFAULT_SYSTEM_ASSISTANT_OWNER_DISPLAY_NAME,
            (result as SecondUserTargetResolution.Resolved).displayName,
        )
    }

    private fun resolver(
        settings: Settings,
        conversationOwners: Map<Uuid, Uuid> = emptyMap(),
    ) = SecondUserTargetResolver(
        settingsReader = SecondUserTargetSettingsReader { settings },
        conversationReader = SecondUserTargetConversationReader { id -> conversationOwners[id] },
    )
}
