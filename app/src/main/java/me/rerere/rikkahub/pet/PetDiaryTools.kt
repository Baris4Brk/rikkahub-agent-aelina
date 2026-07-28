package me.rerere.rikkahub.pet

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.db.dao.PetDialogueDao
import me.rerere.rikkahub.data.db.entity.PetDialogueSessionEntity

class PetDiaryToolProvider(
    private val dao: PetDialogueDao,
    private val repository: PetDialogueRepository,
) {
    fun tools(context: ToolInvocationContext): List<Tool> {
        val owner = owner(context) ?: return emptyList()
        return listOf(
            currentTool(owner),
            listTool(owner),
            readTool(owner),
            updateMetadataTool(owner),
            softDeleteTool(owner),
            restoreTool(owner),
        )
    }

    private fun currentTool(owner: PetDiaryOwner) = Tool(
        name = "pet_dialogue_current",
        description = "Read the current short pet dialogue for this selected second-user conversation.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val session = dao.getActiveSession(owner.assistantId, owner.conversationId)
                ?: return@Tool response(error("PET_DIALOGUE_EMPTY"))
            val turns = dao.getTurns(session.sessionId)
            response(buildJsonObject {
                put("session_id", session.sessionId)
                put("round_count", turns.size)
                put("turns", buildJsonArray {
                    turns.forEach { turn -> add(turnJson(turn.sequence, turn.inputKind, turn.userText ?: turn.interactionJson.orEmpty(), turn.assistantText)) }
                })
            })
        },
    )

    private fun listTool(owner: PetDiaryOwner) = Tool(
        name = "pet_diary_list",
        description = "List archived pet diaries owned by this selected second user. Raw dialogue is omitted.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("limit", type("integer", "Maximum 1-100, default 30.")) },
            )
        },
        execute = { args ->
            val limit = args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            response(buildJsonObject {
                put("diaries", buildJsonArray {
                    dao.getArchives(owner.assistantId, limit).forEach { session ->
                        add(buildJsonObject {
                            put("session_id", session.sessionId)
                            put("date", session.localDate)
                            put("title", session.title)
                            put("summary", session.summary)
                            put("archive_reason", session.archiveReason.orEmpty())
                            put("state_version", session.stateVersion)
                            put("round_count", dao.countTurns(session.sessionId))
                        })
                    }
                })
            })
        },
    )

    private fun readTool(owner: PetDiaryOwner) = Tool(
        name = "pet_diary_read",
        description = "Read one immutable archived pet dialogue and its editable metadata.",
        parameters = { idSchema() },
        execute = { args ->
            val session = ownedSession(args.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull, owner)
                ?: return@Tool response(error("PET_DIARY_NOT_FOUND"))
            if (session.status != PetDialogueSessionStatus.ARCHIVED.name) {
                return@Tool response(error("PET_DIARY_NOT_AVAILABLE"))
            }
            response(buildJsonObject {
                put("session_id", session.sessionId)
                put("title", session.title)
                put("summary", session.summary)
                put("notes", session.notes)
                put("tags_json", session.tagsJson)
                put("state_version", session.stateVersion)
                put("turns", buildJsonArray {
                    dao.getTurns(session.sessionId).forEach { turn ->
                        add(turnJson(turn.sequence, turn.inputKind, turn.userText ?: turn.interactionJson.orEmpty(), turn.assistantText))
                    }
                })
            })
        },
    )

    private fun updateMetadataTool(owner: PetDiaryOwner) = Tool(
        name = "pet_diary_update_metadata",
        description = "Update title, summary, tags or notes. Original dialogue is immutable.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("session_id", type("string", "Diary session ID."))
                    put("expected_version", type("integer", "CAS state version."))
                    put("title", type("string", "Editable title."))
                    put("summary", type("string", "Editable summary."))
                    put("notes", type("string", "Editable notes."))
                    put("tags_json", type("string", "JSON array of tags."))
                },
                required = listOf("session_id", "expected_version"),
            )
        },
        execute = { args ->
            val input = args.jsonObject
            val session = ownedSession(input["session_id"]?.jsonPrimitive?.contentOrNull, owner)
                ?: return@Tool response(error("PET_DIARY_NOT_FOUND"))
            val expected = input["expected_version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: return@Tool response(error("EXPECTED_VERSION_REQUIRED"))
            val result = repository.updateMetadata(
                sessionId = session.sessionId,
                expectedVersion = expected,
                title = input["title"]?.jsonPrimitive?.contentOrNull ?: session.title,
                summary = input["summary"]?.jsonPrimitive?.contentOrNull ?: session.summary,
                notes = input["notes"]?.jsonPrimitive?.contentOrNull ?: session.notes,
                tagsJson = input["tags_json"]?.jsonPrimitive?.contentOrNull ?: session.tagsJson,
                summaryState = PetSummaryState.READY,
                actor = "assistant:${owner.assistantId}",
            )
            response(mutationJson(result))
        },
    )

    private fun softDeleteTool(owner: PetDiaryOwner) = statusTool(
        name = "pet_diary_soft_delete",
        description = "Move an archived pet diary to the 30-day recycle bin.",
        owner = owner,
        mutate = repository::softDelete,
    )

    private fun restoreTool(owner: PetDiaryOwner) = statusTool(
        name = "pet_diary_restore",
        description = "Restore a pet diary from the recycle bin.",
        owner = owner,
        mutate = repository::restore,
    )

    private fun statusTool(
        name: String,
        description: String,
        owner: PetDiaryOwner,
        mutate: suspend (String, Long, String) -> PetMetadataResult,
    ) = Tool(
        name = name,
        description = description,
        parameters = { idSchema(includeVersion = true) },
        execute = { args ->
            val input = args.jsonObject
            val session = ownedSession(input["session_id"]?.jsonPrimitive?.contentOrNull, owner)
                ?: return@Tool response(error("PET_DIARY_NOT_FOUND"))
            val expected = input["expected_version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: return@Tool response(error("EXPECTED_VERSION_REQUIRED"))
            response(mutationJson(mutate(session.sessionId, expected, "assistant:${owner.assistantId}")))
        },
    )

    private suspend fun ownedSession(
        id: String?,
        owner: PetDiaryOwner,
    ): PetDialogueSessionEntity? {
        if (id == null) return null
        return dao.getSession(id)?.takeIf {
            it.assistantId == owner.assistantId &&
                it.privilegedConversationId == owner.conversationId
        }
    }

    private fun owner(context: ToolInvocationContext): PetDiaryOwner? {
        val privilege = context.privilege ?: return null
        if (!privilege.isPrivileged || privilege.privilegedConversationId != privilege.conversationId) return null
        if (!PetDiaryAccessPolicy.allowsOrigin(context.callOrigin)) return null
        if (context.callerAssistantId != privilege.assistantId.toString() ||
            context.callerConversationId != privilege.conversationId.toString()
        ) return null
        return PetDiaryOwner(context.callerAssistantId, context.callerConversationId)
    }

    private fun idSchema(includeVersion: Boolean = false) = InputSchema.Obj(
        properties = buildJsonObject {
            put("session_id", type("string", "Diary session ID."))
            if (includeVersion) put("expected_version", type("integer", "CAS state version."))
        },
        required = if (includeVersion) listOf("session_id", "expected_version") else listOf("session_id"),
    )

    private fun type(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun turnJson(sequence: Int, kind: String, input: String, response: String?) = buildJsonObject {
        put("sequence", sequence)
        put("input_kind", kind)
        put("input", input)
        put("response", response.orEmpty())
    }

    private fun mutationJson(result: PetMetadataResult) = when (result) {
        is PetMetadataResult.Updated -> buildJsonObject { put("success", true); put("state_version", result.stateVersion) }
        PetMetadataResult.Missing -> error("PET_DIARY_NOT_FOUND")
        PetMetadataResult.Conflict -> error("PET_DIARY_VERSION_CONFLICT")
        PetMetadataResult.InvalidState -> error("PET_DIARY_STATE_INVALID")
    }

    private fun error(code: String) = buildJsonObject { put("error", code) }
    private fun response(value: kotlinx.serialization.json.JsonObject) = listOf(UIMessagePart.Text(value.toString()))

    private data class PetDiaryOwner(val assistantId: String, val conversationId: String)

}

object PetDiaryAccessPolicy {
    private val allowedOrigins = setOf(
            ToolCallOrigin.LocalChat,
            ToolCallOrigin.SystemAssistant,
            ToolCallOrigin.PetHandoffConfirmed,
        )

    fun allowsOrigin(origin: ToolCallOrigin?): Boolean = origin in allowedOrigins
}
