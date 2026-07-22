package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.memory.MemoryAttribution
import me.rerere.rikkahub.memory.MemoryNarrativeIdentity
import me.rerere.rikkahub.memory.MemoryQueryRecord
import me.rerere.rikkahub.memory.normalizeMemoryNarrativeText
import me.rerere.rikkahub.memory.resolveMemoryNarrativeIdentity
import me.rerere.rikkahub.utils.JsonInstant

/** Human-facing aliases for Memory V2's internal attribution markers. */
data class MemoryNarrativeNames(
    val selfName: String,
    val companionName: String,
    val sharedNameFormat: String = "%1\$s / %2\$s",
) {
    private val identity: MemoryNarrativeIdentity
        get() = MemoryNarrativeIdentity(selfName = selfName, companionName = companionName)

    fun attributionName(raw: String): String? = when (
        runCatching { MemoryAttribution.valueOf(raw) }.getOrNull()
    ) {
        MemoryAttribution.USER -> selfName
        MemoryAttribution.ASSISTANT -> companionName
        MemoryAttribution.SHARED -> sharedNameFormat.format(selfName, companionName)
        else -> null
    }

    fun participantsName(rawJson: String): String? = runCatching {
        JsonInstant.decodeFromString<List<String>>(rawJson)
    }.getOrDefault(emptyList())
        .map(::participantName)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" · ")
        .ifBlank { null }

    private fun participantName(value: String): String = when (value.trim().uppercase()) {
        "USER" -> selfName
        "ASSISTANT" -> companionName
        else -> ""
    }

    fun readableText(raw: String): String = normalizeMemoryNarrativeText(raw, identity)
}

/**
 * The recall-test surface is still a user-visible memory surface. Preserve the record's origin
 * so Global records use the same names as the library and review tabs instead of leaking role
 * words from legacy or provider-authored text.
 */
internal fun MemoryQueryRecord.readableFor(
    narrativeNamesForOrigin: (String?) -> MemoryNarrativeNames,
): MemoryQueryRecord = readableFor(narrativeNamesForOrigin(originAssistantId))

private fun MemoryQueryRecord.readableFor(names: MemoryNarrativeNames): MemoryQueryRecord = copy(
    title = title?.let(names::readableText),
    content = names.readableText(content),
    tags = tags.map(names::readableText),
    matchedTerms = matchedTerms.map(names::readableText),
    reason = names.readableText(reason),
)

internal fun Assistant.memoryNarrativeNames(
    defaultSelfName: String,
    defaultCompanionName: String,
    sharedNameFormat: String = "%1\$s / %2\$s",
) = resolveMemoryNarrativeIdentity(
    configuredSelfName = memoryNarrativeUserName,
    configuredCompanionName = memoryNarrativeCompanionName,
    assistantName = name,
    selfFallback = defaultSelfName,
    companionFallback = defaultCompanionName,
).let { identity ->
    MemoryNarrativeNames(
        selfName = identity.selfName,
        companionName = identity.companionName,
        sharedNameFormat = sharedNameFormat,
    )
}

internal fun List<Assistant>.memoryNarrativeNamesFor(
    originAssistantId: String?,
    fallbackAssistant: Assistant,
    defaultSelfName: String,
    defaultCompanionName: String,
    sharedNameFormat: String = "%1\$s / %2\$s",
): MemoryNarrativeNames {
    // A legacy record has no origin and therefore belongs to the currently opened conversation.
    // A record with a deleted origin must not be relabelled as a different conversation partner.
    val sourceAssistant = if (originAssistantId == null) {
        fallbackAssistant
    } else {
        firstOrNull { it.id.toString() == originAssistantId }
    }
    return sourceAssistant?.memoryNarrativeNames(
        defaultSelfName = defaultSelfName,
        defaultCompanionName = defaultCompanionName,
        sharedNameFormat = sharedNameFormat,
    ) ?: MemoryNarrativeNames(
        selfName = defaultSelfName,
        companionName = defaultCompanionName,
        sharedNameFormat = sharedNameFormat,
    )
}
