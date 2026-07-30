package me.rerere.rikkahub.diagnostics

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.context.ApproximateContextTokenEstimator
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val REQUEST_BREAKDOWN_DIRECTORY = "diagnostics"
private const val REQUEST_BREAKDOWN_FILE = "last_request_breakdown.json"
private const val MESSAGE_FRAMING_TOKENS = 8
private val requestBreakdownJson = Json { prettyPrint = true }

data class RequestBreakdownSection(
    val name: String,
    val characters: Long,
    val utf8Bytes: Long,
    val estimatedTokens: Int,
)

data class RequestBreakdownDiagnostic(
    val recordedAtEpochMs: Long,
    val generationHash: String,
    val providerCallIndex: Int,
    val modelId: String,
    val providerType: String,
    val requestMode: String,
    val messageCount: Int,
    val toolCount: Int,
    /** Full eligible catalogue count before the second-user progressive selection. */
    val toolCatalogCandidateCount: Int? = null,
    /** Number of real selected schemas, excluding the fixed catalogue helpers. */
    val toolCatalogSelectedSchemaCount: Int? = null,
    /** Privacy-safe phase of the progressive second-user directory. */
    val toolCatalogStage: String? = null,
    val memoryCount: Int,
    val enabledSkillNames: List<String>,
    val toolNames: List<String>,
    val wireSections: List<RequestBreakdownSection>,
    val systemSourceHints: List<RequestBreakdownSection>,
    val estimatedMessageTokens: Int,
    val estimatedToolSchemaTokens: Int,
    val estimatedRequestTokens: Int,
    val providerPromptTokens: Int? = null,
    val providerCachedTokens: Int? = null,
    val providerCompletionTokens: Int? = null,
) {
    fun withProviderUsage(
        promptTokens: Int,
        cachedTokens: Int,
        completionTokens: Int,
    ): RequestBreakdownDiagnostic = copy(
        recordedAtEpochMs = System.currentTimeMillis(),
        providerPromptTokens = promptTokens.takeIf { it > 0 },
        providerCachedTokens = cachedTokens.takeIf { it > 0 },
        providerCompletionTokens = completionTokens.takeIf { it > 0 },
    )

    fun redactedSummary(): String = buildString {
        append("requestBreakdown(call=").append(providerCallIndex)
        append(", estimated=").append(estimatedRequestTokens)
        append(", messages=").append(estimatedMessageTokens)
        append(", schemas=").append(estimatedToolSchemaTokens)
        append(", tools=").append(toolCount)
        toolCatalogCandidateCount?.let { append(", catalogCandidates=").append(it) }
        toolCatalogSelectedSchemaCount?.let { append(", catalogSelected=").append(it) }
        toolCatalogStage?.let { append(", catalogStage=").append(it) }
        append(", memories=").append(memoryCount)
        providerPromptTokens?.let { append(", providerPrompt=").append(it) }
        providerCachedTokens?.let { append(", cached=").append(it) }
        append(')')
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("recorded_at_epoch_ms", recordedAtEpochMs)
        put("generation_hash", generationHash)
        put("provider_call_index", providerCallIndex)
        put("model_id", modelId.take(120))
        put("provider_type", providerType.take(80))
        put("request_mode", requestMode.take(80))
        put("message_count", messageCount)
        put("tool_count", toolCount)
        toolCatalogCandidateCount?.let { put("tool_catalog_candidate_count", it) }
        toolCatalogSelectedSchemaCount?.let { put("tool_catalog_selected_schema_count", it) }
        toolCatalogStage?.let { put("tool_catalog_stage", it.take(32)) }
        put("memory_count", memoryCount)
        put("enabled_skill_names", JsonArray(enabledSkillNames.map(::JsonPrimitive)))
        put("tool_names", JsonArray(toolNames.map(::JsonPrimitive)))
        put("estimated_message_tokens", estimatedMessageTokens)
        put("estimated_tool_schema_tokens", estimatedToolSchemaTokens)
        put("estimated_request_tokens", estimatedRequestTokens)
        providerPromptTokens?.let { put("provider_prompt_tokens", it) }
        providerCachedTokens?.let { put("provider_cached_tokens", it) }
        providerCompletionTokens?.let { put("provider_completion_tokens", it) }
        put("wire_sections", wireSections.toJson())
        put("system_source_hints", systemSourceHints.toJson())
        put(
            "privacy_note",
            "Counts and names only; no prompt, message, memory, notification, tool argument, or tool output text.",
        )
    }

    companion object {
        fun create(
            generationId: String,
            providerCallIndex: Int,
            modelId: String,
            providerType: String,
            requestMode: String,
            finalMessages: List<UIMessage>,
            tools: List<Tool>,
            assistantPrompt: String,
            userIdentityPrompt: String,
            toolSystemPrompts: List<String>,
            memoryPrompt: String,
            recentChatsPrompt: String,
            dynamicSystemAddendum: String?,
            memoryCount: Int,
            enabledSkillNames: Collection<String>,
            toolCatalogCandidateCount: Int? = null,
            toolCatalogSelectedSchemaCount: Int? = null,
            toolCatalogStage: String? = null,
        ): RequestBreakdownDiagnostic {
            val systemMessages = finalMessages.filter { it.role == MessageRole.SYSTEM }
            val nonSystemMessages = finalMessages.filterNot { it.role == MessageRole.SYSTEM }
            val toolSchemaSection = tools.toToolSchemaSection()
            val systemSection = systemMessages.toMessageSection("system_messages")
            val nonSystemSection = nonSystemMessages.toMessageSection("non_system_messages")
            val wireSections = listOf(systemSection, nonSystemSection, toolSchemaSection)
            val estimatedMessageTokens = systemSection.estimatedTokens + nonSystemSection.estimatedTokens

            return RequestBreakdownDiagnostic(
                recordedAtEpochMs = System.currentTimeMillis(),
                generationHash = generationId.sha256Prefix(),
                providerCallIndex = providerCallIndex.coerceAtLeast(1),
                modelId = modelId,
                providerType = providerType,
                requestMode = requestMode,
                messageCount = finalMessages.size,
                toolCount = tools.size,
                toolCatalogCandidateCount = toolCatalogCandidateCount,
                toolCatalogSelectedSchemaCount = toolCatalogSelectedSchemaCount,
                toolCatalogStage = toolCatalogStage,
                memoryCount = memoryCount.coerceAtLeast(0),
                enabledSkillNames = enabledSkillNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
                toolNames = tools.map(Tool::name).distinct().sorted(),
                wireSections = wireSections,
                systemSourceHints = listOf(
                    assistantPrompt.toTextSection("assistant_system_prompt"),
                    userIdentityPrompt.toTextSection("user_identity_prompt"),
                    toolSystemPrompts.toTextSection("tool_system_prompts"),
                    memoryPrompt.toTextSection("memory_prompt"),
                    recentChatsPrompt.toTextSection("recent_chats_prompt"),
                    dynamicSystemAddendum.orEmpty().toTextSection("dynamic_system_addendum"),
                ),
                estimatedMessageTokens = estimatedMessageTokens,
                estimatedToolSchemaTokens = toolSchemaSection.estimatedTokens,
                estimatedRequestTokens = estimatedMessageTokens + toolSchemaSection.estimatedTokens,
            )
        }
    }
}

object RequestBreakdownDiagnosticsStore {
    @Synchronized
    fun write(filesDir: File, diagnostic: RequestBreakdownDiagnostic) {
        runCatching {
            val directory = File(filesDir, REQUEST_BREAKDOWN_DIRECTORY).apply { mkdirs() }
            val destination = File(directory, REQUEST_BREAKDOWN_FILE)
            val temporary = File(directory, ".$REQUEST_BREAKDOWN_FILE.tmp")
            val payload = requestBreakdownJson.encodeToString(
                JsonObject.serializer(),
                diagnostic.toJson(),
            )
            temporary.writeText(payload)
            if (!temporary.renameTo(destination)) {
                destination.writeText(payload)
                temporary.delete()
            }
        }
    }

    fun outputFile(filesDir: File): File =
        File(File(filesDir, REQUEST_BREAKDOWN_DIRECTORY), REQUEST_BREAKDOWN_FILE)
}

private data class PayloadSize(val characters: Long = 0, val utf8Bytes: Long = 0) {
    operator fun plus(other: PayloadSize): PayloadSize = PayloadSize(
        characters = characters + other.characters,
        utf8Bytes = utf8Bytes + other.utf8Bytes,
    )
}

private fun List<UIMessage>.toMessageSection(name: String): RequestBreakdownSection {
    val size = asSequence()
        .flatMap { it.parts.asSequence() }
        .fold(PayloadSize()) { total, part -> total + part.payloadSize() }
    return RequestBreakdownSection(
        name = name,
        characters = size.characters,
        utf8Bytes = size.utf8Bytes,
        estimatedTokens = sumOf(ApproximateContextTokenEstimator::estimate),
    )
}

private fun List<Tool>.toToolSchemaSection(): RequestBreakdownSection {
    var size = PayloadSize()
    var tokens = 0
    forEach { tool ->
        val schema = runCatching {
            tool.parameters()?.let { requestBreakdownJson.encodeToString(InputSchema.serializer(), it) }.orEmpty()
        }.getOrDefault("")
        val fragments = listOf(tool.name, tool.description, schema)
        size += fragments.fold(PayloadSize()) { total, text -> total + text.payloadSize() }
        tokens += fragments.sumOf(::estimateTextTokens)
        tokens += 12
    }
    return RequestBreakdownSection("tool_schemas", size.characters, size.utf8Bytes, tokens)
}

private fun String.toTextSection(name: String): RequestBreakdownSection = listOf(this).toTextSection(name)

private fun List<String>.toTextSection(name: String): RequestBreakdownSection {
    val size = fold(PayloadSize()) { total, text -> total + text.payloadSize() }
    return RequestBreakdownSection(
        name = name,
        characters = size.characters,
        utf8Bytes = size.utf8Bytes,
        estimatedTokens = sumOf(::estimateTextTokens),
    )
}

private fun List<RequestBreakdownSection>.toJson(): JsonArray = buildJsonArray {
    this@toJson.forEach { section ->
        add(buildJsonObject {
            put("name", section.name)
            put("characters", section.characters)
            put("utf8_bytes", section.utf8Bytes)
            put("estimated_tokens", section.estimatedTokens)
        })
    }
}

private fun UIMessagePart.payloadSize(): PayloadSize = when (this) {
    is UIMessagePart.Text -> text.payloadSize()
    is UIMessagePart.Reasoning -> reasoning.payloadSize()
    is UIMessagePart.Image -> url.payloadSize()
    is UIMessagePart.Video -> url.payloadSize()
    is UIMessagePart.Audio -> url.payloadSize()
    is UIMessagePart.Document -> url.payloadSize() + fileName.payloadSize() + mime.payloadSize()
    is UIMessagePart.Tool -> toolName.payloadSize() + input.toString().payloadSize() +
        output.fold(PayloadSize()) { total, part -> total + part.payloadSize() }
    is UIMessagePart.ToolCall -> toolName.payloadSize() + arguments.payloadSize()
    is UIMessagePart.ToolResult -> toolName.payloadSize() + content.toString().payloadSize() +
        arguments.toString().payloadSize()
    UIMessagePart.Search -> PayloadSize()
}

private fun String.payloadSize(): PayloadSize = PayloadSize(length.toLong(), utf8Length())

private fun String.utf8Length(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.code <= 0x7f -> bytes += 1
            char.code <= 0x7ff -> bytes += 2
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                bytes += 4
                index += 1
            }
            else -> bytes += 3
        }
        index += 1
    }
    return bytes
}

private fun estimateTextTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val framed = ApproximateContextTokenEstimator.estimate(
        UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(text))),
    )
    return (framed - MESSAGE_FRAMING_TOKENS).coerceAtLeast(0)
}

private fun String.sha256Prefix(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.take(12).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
