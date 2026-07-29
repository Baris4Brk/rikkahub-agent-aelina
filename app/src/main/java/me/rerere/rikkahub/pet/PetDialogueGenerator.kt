package me.rerere.rikkahub.pet

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant

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
        val selection = selectPetGenerationModel(settings, assistant)
            ?: return PetGenerationResult.Failure("pet_provider_unavailable")
        val model = selection.model
        val providerSetting = selection.provider
        val provider = providerManager.getProviderByType(providerSetting)
        val historyText = history.takeLast(MAX_PET_DIALOGUE_ROUNDS).joinToString("\n") { turn ->
            "用户：${turn.userInput}\n桌宠：${turn.assistantText.orEmpty()}"
        }
        val messages = listOf(
            UIMessage.system(buildPetSystemPrompt(persona, handoffMode)),
            UIMessage.user("<recent_dialogue>\n$historyText\n</recent_dialogue>\n<current_input>\n$input\n</current_input>"),
        )
        val raw = withTimeout(PET_GENERATION_TIMEOUT_MS) {
            suspend fun request(maxTokens: Int): String = provider.generateText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = assistant.temperature ?: 0.7f,
                    maxTokens = maxTokens,
                    tools = emptyList(),
                    // Pet dialogue needs a tiny structured answer, not a reasoning-only response.
                    reasoningLevel = ReasoningLevel.OFF,
                    omitReasoningConfigurationWhenOff = true,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).choices.firstOrNull()?.message?.toText().orEmpty()

            request(maxTokens = 768).ifBlank {
                // A retry is safe because pet requests expose no tools or side effects. Some
                // reasoning models consume a short completion budget without emitting text.
                request(maxTokens = 1_536)
            }
        }
        if (raw.isBlank()) return PetGenerationResult.Failure("pet_empty_response")
        val parsed = parsePetModelResponse(raw, json)
            ?: return PetGenerationResult.Failure("pet_response_invalid")
        buildPetGenerationSuccess(parsed, input, handoffMode)
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException) {
            PetGenerationResult.Failure("pet_generation_timeout")
        } else {
            throw cancelled
        }
    } catch (error: Throwable) {
        // Do not log provider messages: they can contain request or account details.
        Log.w(TAG, "Pet generation failed: ${error.javaClass.simpleName}")
        PetGenerationResult.Failure("pet_generation_failed")
    }

    private fun buildPetSystemPrompt(persona: PetPersonaProjection, mode: PetHandoffMode): String = """
        你是与第二用户绑定的桌宠短会话侧车。只进行简短角色互动，不拥有任何工具、记忆检索、屏幕内容或主会话历史。
        人物设定（仅作角色表达）：
        ${persona.personaPrompt}

        仅输出 JSON：{"text":"不超过96个Unicode字符且不能为空","action":"白名单动作","handoff":{"needed":false,"title":"","request":""}}
        action 只能是：${PetAction.entries.joinToString { it.name }}。
        每次触摸或文字都必须给出一句简短回应。用户明确要求查询、修改、提醒或完成事情时，handoff.needed 必须为 true，title 和 request 必须完整；你只生成安全任务草稿，不声称已经执行。当前转交模式：${mode.name}。
        不复述密码、令牌、验证码、路径、通知正文或其他敏感原文。
    """.trimIndent()

    private companion object {
        const val TAG = "PetDialogueGenerator"
        const val PET_GENERATION_TIMEOUT_MS = 60_000L
    }
}

internal fun buildPetGenerationSuccess(
    parsed: PetModelResponse,
    input: String,
    handoffMode: PetHandoffMode,
): PetGenerationResult.Success {
    val wantsHandoff = parsed.handoff.needed && handoffMode != PetHandoffMode.SUGGEST_ONLY
    val safeInput = PetBubbleSanitizer.sanitizeDraft(input).take(2_000)
    val handoff = if (wantsHandoff && safeInput.isNotBlank()) {
        PetHandoffDraft(
            mode = handoffMode,
            title = PetBubbleSanitizer.sanitize(parsed.handoff.title)
                .ifBlank { PetBubbleSanitizer.sanitize(input) }
                .take(160),
            request = PetBubbleSanitizer.sanitizeDraft(parsed.handoff.request)
                .ifBlank { safeInput }
                .take(2_000),
        )
    } else {
        null
    }
    val text = PetBubbleSanitizer.sanitize(parsed.text).ifBlank {
        if (handoff != null) "我把这件事整理好啦，可以交给第二用户处理。" else "我在呢。"
    }
    val action = runCatching { PetAction.valueOf(parsed.action.uppercase()) }.getOrDefault(PetAction.IDLE)
    return PetGenerationResult.Success(text, action, handoff)
}

internal data class PetGenerationModelSelection(
    val model: Model,
    val provider: ProviderSetting,
)

/**
 * Prefer Fast Model, but do not strand pet dialogue on the credential-less built-in Auto model.
 * The fallback is the bound assistant's normal chat model and still runs through the pet-only,
 * zero-tool request above.
 */
internal fun selectPetGenerationModel(
    settings: Settings,
    assistant: Assistant,
): PetGenerationModelSelection? {
    val candidateIds = listOf(
        settings.fastModelId,
        assistant.chatModelId ?: settings.chatModelId,
    ).distinct()
    return candidateIds.firstNotNullOfOrNull { modelId ->
        val model = settings.findModelById(modelId) ?: return@firstNotNullOfOrNull null
        val provider = model.findProvider(settings.providers) ?: return@firstNotNullOfOrNull null
        if (!isPetGenerationProviderUsable(model, provider)) return@firstNotNullOfOrNull null
        PetGenerationModelSelection(model, provider)
    }
}

internal fun isPetGenerationProviderUsable(
    model: Model,
    provider: ProviderSetting,
): Boolean {
    if (!provider.enabled) return false
    return !(model.id == DEFAULT_AUTO_MODEL_ID &&
        provider is ProviderSetting.OpenAI &&
        provider.apiKey.isBlank())
}

internal fun parsePetModelResponse(raw: String, json: Json = Json { ignoreUnknownKeys = true }): PetModelResponse? {
    val trimmed = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val candidates = listOfNotNull(trimmed, extractFirstJsonObject(raw)).distinct()
    return candidates.firstNotNullOfOrNull { candidate ->
        runCatching {
            val objectValue = json.parseToJsonElement(candidate).jsonObject
            if ("text" !in objectValue) return@runCatching null
            json.decodeFromJsonElement<PetModelResponse>(objectValue)
        }.getOrNull()
    }
}

/** Extracts a balanced JSON object while respecting quoted braces and escapes. */
internal fun extractFirstJsonObject(raw: String): String? {
    var start = -1
    var depth = 0
    var quoted = false
    var escaped = false
    raw.forEachIndexed { index, char ->
        if (start < 0) {
            if (char == '{') {
                start = index
                depth = 1
            }
            return@forEachIndexed
        }
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
            return@forEachIndexed
        }
        when (char) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return raw.substring(start, index + 1)
            }
        }
    }
    return null
}

internal fun petGenerationErrorMessage(code: String): String = when (code) {
    "pet_provider_unavailable" -> "Fast Model 不可用，且第二用户主模型无法回退"
    "pet_model_missing" -> "未找到可用的 Fast Model"
    "pet_provider_missing", "pet_provider_disabled" -> "Fast Model 的服务商不可用"
    "pet_generation_timeout" -> "桌宠回应超时，请重试"
    "pet_empty_response" -> "模型返回了空回应，请重试"
    "pet_response_invalid" -> "模型回应格式无效，请重试"
    "pet_input_too_long" -> "输入内容过长"
    else -> "桌宠模型调用失败，请检查 Fast Model 与服务商"
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
