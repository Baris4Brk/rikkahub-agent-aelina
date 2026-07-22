package me.rerere.rikkahub.memory

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import kotlin.uuid.Uuid

/**
 * Names used in readable Memory V2 text. The persistence protocol still uses stable role tokens
 * such as USER and ASSISTANT, but those tokens must not leak into a memory card or proposal.
 */
data class MemoryNarrativeIdentity(
    val selfName: String,
    val companionName: String,
)

fun interface MemoryNarrativeIdentityResolver {
    fun resolve(assistantId: String): MemoryNarrativeIdentity
}

/** Reads the current per-assistant display-name setting immediately before extraction. */
class SettingsMemoryNarrativeIdentityResolver(
    private val settingsStore: SettingsStore,
) : MemoryNarrativeIdentityResolver {
    override fun resolve(assistantId: String): MemoryNarrativeIdentity {
        val assistant = runCatching { Uuid.parse(assistantId) }
            .getOrNull()
            ?.let(settingsStore.settingsFlow.value::getAssistantById)
        return resolveMemoryNarrativeIdentity(
            configuredSelfName = assistant?.memoryNarrativeUserName.orEmpty(),
            configuredCompanionName = assistant?.memoryNarrativeCompanionName.orEmpty(),
            assistantName = assistant?.name.orEmpty(),
        )
    }
}

/**
 * Keeps UI and extractor fallbacks predictable even when an Assistant has been deleted while a
 * capture was waiting in the queue. Configured names are evaluated at processing time, so a name
 * change also affects captures that have not been extracted yet.
 */
fun resolveMemoryNarrativeIdentity(
    configuredSelfName: String,
    configuredCompanionName: String,
    assistantName: String,
    selfFallback: String = DEFAULT_MEMORY_NARRATIVE_SELF_NAME,
    companionFallback: String = DEFAULT_MEMORY_NARRATIVE_COMPANION_NAME,
): MemoryNarrativeIdentity = MemoryNarrativeIdentity(
    selfName = configuredSelfName.normalizedNarrativeName().ifBlank { selfFallback },
    companionName = configuredCompanionName.normalizedNarrativeName()
        .ifBlank { assistantName.normalizedNarrativeName() }
        .ifBlank { companionFallback },
)

private fun String.normalizedNarrativeName(): String =
    trim().filterNot(Char::isISOControl).take(MAX_MEMORY_NARRATIVE_NAME_CHARS)

const val DEFAULT_MEMORY_NARRATIVE_SELF_NAME = "你"
const val DEFAULT_MEMORY_NARRATIVE_COMPANION_NAME = "对话对象"
const val MAX_MEMORY_NARRATIVE_NAME_CHARS = 80
