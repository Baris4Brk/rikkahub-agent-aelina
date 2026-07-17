package me.rerere.rikkahub.privilege

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

sealed interface PrivilegedManagementRequest {
    data class StateGet(val section: String?) : PrivilegedManagementRequest
    data class ConversationCreate(val assistantId: Uuid, val title: String) : PrivilegedManagementRequest
    data class ConversationUpdate(
        val conversationId: Uuid,
        val title: String?,
        val pinned: Boolean?,
        val customSystemPrompt: String?,
    ) : PrivilegedManagementRequest
    data class ConversationDelete(val conversationId: Uuid) : PrivilegedManagementRequest
    data class AssistantUpdate(
        val assistantId: Uuid,
        val name: String?,
        val systemPrompt: String?,
        val chatModelId: Uuid?,
        val clearChatModel: Boolean,
        val workspaceId: Uuid?,
        val clearWorkspace: Boolean,
        val enableMemory: Boolean?,
        val useGlobalMemory: Boolean?,
        val enableRecentChatsReference: Boolean?,
        val streamOutput: Boolean?,
        val fastPathRouterEnabled: Boolean?,
    ) : PrivilegedManagementRequest
    data class AssistantToggleTool(
        val assistantId: Uuid,
        val toolType: String,
        val enabled: Boolean,
    ) : PrivilegedManagementRequest
    data class AssistantUpdateSkills(
        val assistantId: Uuid,
        val operation: CollectionOperation,
        val names: Set<String>,
    ) : PrivilegedManagementRequest
    data class AssistantUpdateMcpServers(
        val assistantId: Uuid,
        val operation: CollectionOperation,
        val serverIds: Set<Uuid>,
    ) : PrivilegedManagementRequest
    data class LorebookCreate(
        val name: String,
        val description: String,
        val enabled: Boolean,
        val entryContent: String?,
        val keywords: List<String>,
    ) : PrivilegedManagementRequest
    data class LorebookUpdate(
        val lorebookId: Uuid,
        val name: String?,
        val description: String?,
        val enabled: Boolean?,
        val entryContent: String?,
        val keywords: List<String>?,
    ) : PrivilegedManagementRequest
    data class LorebookDelete(val lorebookId: Uuid) : PrivilegedManagementRequest
    data class ModeInjectionUpdate(
        val operation: MutationOperation,
        val injectionId: Uuid?,
        val name: String?,
        val content: String?,
        val enabled: Boolean?,
        val priority: Int?,
        val position: String?,
        val role: String?,
    ) : PrivilegedManagementRequest
    data class AppSettingsUpdate(
        val dynamicColor: Boolean?,
        val themeId: String?,
        val developerMode: Boolean?,
        val enableWebSearch: Boolean?,
        val chatModelId: Uuid?,
        val fastModelId: Uuid?,
        val titleModelId: Uuid?,
        val clearTitleModel: Boolean,
        val enableSuggestion: Boolean?,
        val suggestionModelId: Uuid?,
        val clearSuggestionModel: Boolean,
        val webServerEnabled: Boolean?,
        val webServerPort: Int?,
        val webServerJwtEnabled: Boolean?,
        val webServerLocalhostOnly: Boolean?,
        val aiLogLevel: String?,
    ) : PrivilegedManagementRequest
}

enum class CollectionOperation { ADD, REMOVE, REPLACE }
enum class MutationOperation { CREATE, UPDATE, DELETE }

data class PrivilegedManagementResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val data: JsonObject? = null,
) {
    companion object {
        fun success(code: String, message: String, data: JsonObject? = null) =
            PrivilegedManagementResult(true, code, message, data)

        fun failure(code: String, message: String, data: JsonObject? = null) =
            PrivilegedManagementResult(false, code, message, data)

        fun idData(name: String, id: Any) = buildJsonObject { put(name, id.toString()) }
    }
}

fun interface PrivilegedManagementBackend {
    suspend fun execute(
        request: PrivilegedManagementRequest,
        context: PrivilegedSessionContext,
    ): PrivilegedManagementResult
}
