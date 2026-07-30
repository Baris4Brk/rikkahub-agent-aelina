package me.rerere.rikkahub.quickcapture

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.assistant.SecondUserTargetConversationReader
import me.rerere.rikkahub.assistant.SecondUserTargetConversationTitleReader
import me.rerere.rikkahub.assistant.SecondUserTargetResolver
import me.rerere.rikkahub.assistant.SecondUserTargetSettingsReader
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

class QuickCaptureTargetResolverTest {
    @Test
    fun `global second user wins over temporary fixed and system compatibility targets`() = runBlocking {
        val first = target("Temporary")
        val fixed = target("Fixed")
        val system = target("System")
        val settings = settings(
            assistants = listOf(first.assistant, fixed.assistant, system.assistant),
            systemTarget = system.assistant.id,
            quick = QuickCaptureSettings(
                targetMode = QuickCaptureTargetMode.FIXED_ASSISTANT,
                fixedAssistantId = fixed.assistant.id,
            ),
            models = listOf(first.model, fixed.model, system.model),
        )
        val resolver = resolver(settings, listOf(first, fixed, system))

        withAuthority(first) {
            val result = resolver.resolve(first.assistant.id) as QuickCaptureTargetResolution.Resolved

            assertEquals(first.assistant.id, result.target.assistantId)
            assertEquals(QuickCaptureTargetSource.TEMPORARY, result.target.source)
        }
    }

    @Test
    fun `stale fixed assistant cannot redirect a capture away from global second user`() = runBlocking {
        val configured = target("Configured")
        val unrelated = target("Unrelated")
        val settings = settings(
            assistants = listOf(configured.assistant, unrelated.assistant),
            systemTarget = null,
            quick = QuickCaptureSettings(
                targetMode = QuickCaptureTargetMode.FIXED_ASSISTANT,
                fixedAssistantId = Uuid.random(),
            ),
            models = listOf(configured.model, unrelated.model),
        ).copy(assistantId = unrelated.assistant.id)

        withAuthority(configured) {
            val result = resolver(settings, listOf(configured, unrelated)).resolve()
                as QuickCaptureTargetResolution.Resolved

            assertEquals(configured.assistant.id, result.target.assistantId)
        }
    }

    @Test
    fun `text-only model requires an enabled visual ocr model`() = runBlocking {
        val destination = target("Text only", imageCapable = false)
        val ocr = Model(inputModalities = listOf(Modality.IMAGE))
        val settings = settings(
            assistants = listOf(destination.assistant),
            systemTarget = destination.assistant.id,
            quick = QuickCaptureSettings(),
            models = listOf(destination.model, ocr),
            ocrModelId = ocr.id,
        )

        withAuthority(destination) {
            val result = resolver(settings, listOf(destination)).resolve()

            assertTrue(result is QuickCaptureTargetResolution.Resolved)
        }
    }

    @Test
    fun `snapshot validation rejects a changed second user conversation`() = runBlocking {
        val target = target("Assistant")
        val settings = settings(
            assistants = listOf(target.assistant),
            systemTarget = target.assistant.id,
            quick = QuickCaptureSettings(),
            models = listOf(target.model),
        )
        val resolver = resolver(settings, listOf(target))
        val resolved = withAuthority(target) {
            resolver.resolve() as QuickCaptureTargetResolution.Resolved
        }
        val changedAssistant = target.assistant.copy(privilegedConversationId = Uuid.random())
        val changedSettings = settings.copy(assistants = listOf(changedAssistant))

        val result = resolver(changedSettings, listOf(target.copy(assistant = changedAssistant)))
            .validateTargetSnapshot(resolved.target)

        assertEquals(
            QuickCaptureTargetFailure.TARGET_NOT_SELECTED,
            (result as QuickCaptureTargetResolution.Unavailable).reason,
        )
    }

    private data class Destination(val assistant: Assistant, val model: Model)

    private fun target(name: String, imageCapable: Boolean = true): Destination {
        val conversationId = Uuid.random()
        val model = Model(inputModalities = if (imageCapable) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT))
        return Destination(
            assistant = Assistant(name = name, privilegedConversationId = conversationId, chatModelId = model.id),
            model = model,
        )
    }

    private fun settings(
        assistants: List<Assistant>,
        systemTarget: Uuid?,
        quick: QuickCaptureSettings,
        models: List<Model>,
        ocrModelId: Uuid? = null,
    ): Settings = Settings(
        assistants = assistants,
        assistantId = assistants.first().id,
        systemAssistantTargetAssistantId = systemTarget,
        quickCaptureSettings = quick,
        chatModelId = models.first().id,
        ocrModelId = ocrModelId ?: Uuid.random(),
        providers = listOf(ProviderSetting.OpenAI(models = models)),
    )

    private fun resolver(settings: Settings, destinations: List<Destination>): QuickCaptureTargetResolver {
        val owners = destinations.associate { destination ->
            destination.assistant.privilegedConversationId!! to destination.assistant.id
        }
        val secondUser = SecondUserTargetResolver(
            settingsReader = SecondUserTargetSettingsReader { settings },
            conversationReader = SecondUserTargetConversationReader { owners[it] },
            conversationTitleReader = SecondUserTargetConversationTitleReader { "Second user" },
        )
        return QuickCaptureTargetResolver(
            settingsReader = QuickCaptureSettingsReader { settings },
            secondUserResolver = secondUser,
        )
    }

    private suspend fun <T> withAuthority(destination: Destination, block: suspend () -> T): T {
        val conversationId = destination.assistant.privilegedConversationId!!
        SecondUserAuthorityRegistry.install(
            SecondUserAdmissionSnapshot.create(
                assistantId = destination.assistant.id,
                conversationId = conversationId,
                authorityEpoch = 1L,
                origin = ToolCallOrigin.QuickCapture,
            ),
        )
        return try {
            block()
        } finally {
            SecondUserAuthorityRegistry.install(null)
        }
    }
}
