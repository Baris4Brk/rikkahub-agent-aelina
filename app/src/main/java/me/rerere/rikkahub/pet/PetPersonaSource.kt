package me.rerere.rikkahub.pet

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant

class PetPersonaSource(
    private val settingsStore: SettingsStore,
) {
    fun observe(assistantId: Uuid): Flow<PetPersonaProjection> = settingsStore.settingsFlow
        .mapNotNull { settings ->
            val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                ?: return@mapNotNull null
            buildProjection(
                assistant = assistant,
                modeContents = settings.modeInjections
                    .filter { it.enabled && it.id in assistant.modeInjectionIds }
                    .sortedByDescending { it.priority }
                    .map { it.content },
                loreContents = settings.lorebooks
                    .filter { it.enabled && it.id in assistant.lorebookIds }
                    .flatMap { lorebook ->
                        lorebook.entries.filter { it.enabled && it.constantActive }
                            .sortedByDescending { it.priority }
                            .map { it.content }
                    },
            )
        }
        .distinctUntilChanged()

    companion object {
        fun buildProjection(
            assistant: Assistant,
            modeContents: List<String> = emptyList(),
            loreContents: List<String> = emptyList(),
        ): PetPersonaProjection {
            val sections = buildList {
                add(assistant.systemPrompt)
                assistant.petSupplement?.let(::add)
                assistant.presetMessages.mapTo(this) { it.toText() }
                addAll(modeContents)
                addAll(loreContents)
            }.map(String::trim).filter(String::isNotEmpty)
            val full = sections.joinToString("\n\n")
            val truncated = full.length > MAX_PET_PERSONA_CHARS
            val prompt = full.take(MAX_PET_PERSONA_CHARS)
            val revisionMaterial = assistant.name + '\u0000' + prompt + '\u0000' + assistant.petSupplement
            return PetPersonaProjection(
                assistantId = assistant.id,
                assistantName = assistant.name,
                personaPrompt = prompt,
                petSupplement = assistant.petSupplement,
                revision = revisionMaterial.hashCode().toLong() and 0xffffffffL,
                truncated = truncated,
            )
        }
    }
}
