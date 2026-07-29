package me.rerere.rikkahub.pet

import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.assistant.SecondUserPresentationStatus
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.pet.render.PetFrameClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `default frame clock keeps short idle animation calm`() {
        val clock = PetFrameClock()
        assertEquals(0, clock.frameIndex(0, 6))
        assertEquals(1, clock.frameIndex(166, 6))
        assertEquals(0, clock.frameIndex(996, 6))
    }

    @Test
    fun `pet response parser accepts a whitelisted json object wrapped in model prose`() {
        val parsed = parsePetModelResponse(
            "说明：\n```json\n{\"text\":\"你好 {朋友}\",\"action\":\"WAVING\",\"handoff\":{\"needed\":false}}\n```",
        )
        assertEquals("你好 {朋友}", parsed?.text)
        assertEquals("WAVING", parsed?.action)
        assertNull(parsePetModelResponse("模型没有输出约定结构"))
        assertNull(parsePetModelResponse("{}"))
    }

    @Test
    fun `pet generation errors are safe and actionable`() {
        assertEquals("模型回应格式无效，请重试", petGenerationErrorMessage("pet_response_invalid"))
        assertEquals(
            "桌宠模型调用失败，请检查 Fast Model 与服务商",
            petGenerationErrorMessage("provider_secret_detail_must_not_escape"),
        )
    }

    @Test
    fun `credential-less auto fast model falls back to assistant chat model`() {
        val fallbackProviderId = Uuid.random()
        val fallbackModel = Model(modelId = "configured-chat", id = Uuid.random())
        val assistant = Assistant(
            id = Uuid.random(),
            name = "Pet",
            chatModelId = fallbackModel.id,
        )
        val settings = Settings(
            fastModelId = DEFAULT_AUTO_MODEL_ID,
            chatModelId = fallbackModel.id,
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "RikkaHub Auto",
                    apiKey = "",
                    models = listOf(Model(modelId = "auto", id = DEFAULT_AUTO_MODEL_ID)),
                ),
                ProviderSetting.OpenAI(
                    id = fallbackProviderId,
                    name = "Configured",
                    apiKey = "configured-key",
                    models = listOf(fallbackModel),
                ),
            ),
            assistants = listOf(assistant),
        )

        val selection = selectPetGenerationModel(settings, assistant)

        assertEquals(fallbackModel.id, selection?.model?.id)
        assertEquals(fallbackProviderId, selection?.provider?.id)
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
