package me.rerere.rikkahub.data.repository

import androidx.core.net.toUri
import me.rerere.rikkahub.assistant.SecondUserAuthorityService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar

sealed interface AssistantRemovalResult {
    data object Removed : AssistantRemovalResult
    data object RetainedSecondUser : AssistantRemovalResult
    data object NotFound : AssistantRemovalResult
}

/** Deletes dependent data only after the global second-user protection has been checked. */
class AssistantRemovalService(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversations: ConversationRepository,
    private val filesManager: FilesManager,
    private val authority: SecondUserAuthorityService,
) {
    suspend fun remove(assistant: Assistant): AssistantRemovalResult {
        val settings = settingsStore.settingsFlow.value
        if (settings.assistants.none { it.id == assistant.id }) return AssistantRemovalResult.NotFound
        if (authority.isAssistantDeletionProtected(assistant.id)) {
            return AssistantRemovalResult.RetainedSecondUser
        }
        val deletedConversations = conversations.deleteConversationOfAssistant(assistant.id)
        if (deletedConversations.retained.isNotEmpty()) return AssistantRemovalResult.RetainedSecondUser
        cleanupAssistantFiles(assistant)
        memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
        settingsStore.update { current ->
            current.copy(assistants = current.assistants.filter { it.id != assistant.id })
        }
        return AssistantRemovalResult.Removed
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }
        if (uris.isNotEmpty()) filesManager.deleteChatFiles(uris)
    }
}
