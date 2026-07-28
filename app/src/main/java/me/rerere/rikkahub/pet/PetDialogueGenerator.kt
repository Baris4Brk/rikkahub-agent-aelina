package me.rerere.rikkahub.pet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

@Serializable
data class PetModelHandoff(
    val needed: Boolean = false,
    val title: String = "",
    val request: String = "",
)

@Serializable
data class PetModelResponse(
    val text: String = "",
    val action: String = PetAction.IDLE.name,
    val handoff: PetModelHandoff = PetModelHandoff(),
)

sealed interface PetGenerationResult {
    data class Success(
        val text: String,
        val action: PetAction,
        val handoff: PetHandoffDraft?,
    ) : PetGenerationResult

    data object LocalAnimationOnly : PetGenerationResult
    data class Failure(val code: String) : PetGenerationResult
}

class PetDialogueGenerator(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun generate(
        persona: PetPersonaProjection,
        history: List<PetDialogueTurnEntityView>,
        input: String,
        handoffMode: PetHandoffMode,
    ): PetGenerationResult = try {
        if (input.codePointCount(0, input.length) > MAX_PET_INPUT_CODE_POINTS) {
            return PetGenerationResult.Failure("pet_input_too_long")
        }
        val settings = settingsStore.settingsFlow.first { !it.init }
        val assistant = settings.assistants.firstOrNull { it.id == persona.assistantId }
            ?: return PetGenerationResult.Failure("pet_assistant_missing")
        val model = settings.findModelById(settings.fastModelId)
            ?: settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return PetGenerationResult.Failure("pet_model_missing")
        val providerSetting = model.findProvider(settings.providers)
            ?: return PetGenerationResult.Failure("pet_provider_missing")
        if (!providerSetting.enabled) return PetGenerationResult.Failure("pet_provider_disabled")
        val provider = providerManager.getProviderByType(providerSetting)
        val historyText = history.takeLast(MAX_PET_DIALOGUE_ROUNDS).joinToString("\n") { turn ->
            "用户：${turn.userInput}\n桌宠：${turn.assistantText.orEmpty()}"
        }
        val response = withTimeout(PET_GENERATION_TIMEOUT_MS) {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage.system(buildPetSystemPrompt(persona, handoffMode)),
                    UIMessage.user("<recent_dialogue>\n$historyText\n</recent_dialogue>\n<current_input>\n$input\n</current_input>"),
                ),
                params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature ?: 0.7f,
                    maxTokens = 512,
                    tools = emptyList(),
                    reasoningLevel = if (ModelAbility.REASONING in model.abilities) {
                        ReasoningLevel.LOW
                    } else {
                        ReasoningLevel.OFF
                    },
                    omitReasoningConfigurationWhenOff = true,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }
        val raw = response.choices.firstOrNull()?.message?.toText().orEmpty()
        val parsed = parseResponse(raw) ?: return PetGenerationResult.LocalAnimationOnly
        val safeText = PetBubbleSanitizer.sanitize(parsed.text)
        val action = runCatching { PetAction.valueOf(parsed.action.uppercase()) }.getOrDefault(PetAction.IDLE)
        val handoff = parsed.handoff.takeIf { it.needed && handoffMode != PetHandoffMode.SUGGEST_ONLY }
            ?.let {
                PetHandoffDraft(
                    mode = handoffMode,
                    title = PetBubbleSanitizer.sanitize(it.title).take(160),
                    request = PetBubbleSanitizer.sanitizeDraft(it.request).take(2_000),
                )
            }
        PetGenerationResult.Success(safeText, action, handoff)
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) {
            PetGenerationResult.Failure("pet_generation_timeout")
        } else {
            throw cancelled
        }
    } catch (_: Throwable) {
        PetGenerationResult.LocalAnimationOnly
    }

    private fun parseResponse(raw: String): PetModelResponse? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { json.decodeFromString<PetModelResponse>(trimmed) }.getOrNull()
    }

    private fun buildPetSystemPrompt(persona: PetPersonaProjection, mode: PetHandoffMode): String = """
        你是与第二用户绑定的桌宠短会话侧车。只进行简短角色互动，不拥有任何工具、记忆检索、屏幕内容或主会话历史。
        人物设定（仅作角色表达）：
        ${persona.personaPrompt}

        仅输出 JSON：{"text":"不超过96个Unicode字符，可为空","action":"白名单动作","handoff":{"needed":false,"title":"","request":""}}
        action 只能是：${PetAction.entries.joinToString { it.name }}。
        如用户明确要求办事，只生成安全任务草稿，不声称已经执行。当前转交模式：${mode.name}。
        不复述密码、令牌、验证码、路径、通知正文或其他敏感原文。
    """.trimIndent()

    private companion object {
        const val PET_GENERATION_TIMEOUT_MS = 60_000L
    }
}

data class PetDialogueTurnEntityView(
    val userInput: String,
    val assistantText: String?,
)

object PetBubbleSanitizer {
    private val controls = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]")
    private val urlQuery = Regex("https?://\\S+\\?\\S+", RegexOption.IGNORE_CASE)
    private val windowsPath = Regex("[A-Za-z]:\\\\[^\\s]+")
    private val unixPath = Regex("(?<![A-Za-z0-9])/(?:[^\\s/]+/)+[^\\s]+")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")
    private val secretLabel = Regex("(?i)(token|password|secret|api[_ -]?key)\\s*[:=]\\s*\\S+")

    fun sanitize(text: String): String = truncateCodePoints(redact(text).replace(Regex("\\s+"), " ").trim(), MAX_PET_RESPONSE_CODE_POINTS)

    fun sanitizeDraft(text: String): String = redact(text).trim()

    private fun redact(text: String): String = text
        .replace(controls, "")
        .replace(urlQuery, "[链接已隐藏]")
        .replace(windowsPath, "[路径已隐藏]")
        .replace(unixPath, "[路径已隐藏]")
        .replace(jwt, "[凭据已隐藏]")
        .replace(secretLabel, "[凭据已隐藏]")

    private fun truncateCodePoints(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max))
    }
}
