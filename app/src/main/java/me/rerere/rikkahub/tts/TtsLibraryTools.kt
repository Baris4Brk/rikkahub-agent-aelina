package me.rerere.rikkahub.tts

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * Only a locally confirmed second-user task receives a speaking scope. Remote, workflow, pet
 * and unscoped calls may still use TTS if otherwise permitted, but cannot animate the desktop pet.
 */
fun secondUserTtsOwnerKey(context: ToolInvocationContext): String? {
    val privilege = context.privilege ?: return null
    if (!privilege.isPrivileged || !privilege.expandLocalTools) return null
    if (privilege.privilegedConversationId != privilege.conversationId) return null
    if (context.callOrigin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return null
    val assistantId = privilege.assistantId.toString()
    val conversationId = privilege.conversationId.toString()
    if (context.callerAssistantId != assistantId || context.callerConversationId != conversationId) return null
    return TtsPlaybackOwner.secondUser(assistantId, conversationId)
}

/** Local-only history tools for the configured second-user conversation. */
class TtsLibraryToolProvider(
    private val library: PersistentTtsLibrary,
) {
    fun tools(context: ToolInvocationContext): List<Tool> {
        if (!canAccess(context)) return emptyList()
        return listOf(listTool(), playTool())
    }

    private fun listTool() = Tool(
        name = "tts_library_list",
        description = "List permanently saved TTS audio created on this device. Returns safe metadata and an artifact ID, never an internal file path or raw audio.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum entries to return, 1-100; default 30.")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of newer entries to skip; default 0. Use this to page through the unlimited archive.")
                    })
                }
            )
        },
        execute = { arguments ->
            val limit = arguments.jsonObject["limit"]?.jsonPrimitive?.contentOrNull
                ?.toIntOrNull()?.coerceIn(1, 100) ?: 30
            val offset = arguments.jsonObject["offset"]?.jsonPrimitive?.contentOrNull
                ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            response(buildJsonObject {
                put("entries", buildJsonArray {
                    library.list(limit = limit, offset = offset).forEach { entry ->
                        add(buildJsonObject {
                            put("artifact_id", entry.artifactId)
                            put("created_at_ms", entry.createdAtMs)
                            put("text_preview", entry.text.take(TEXT_PREVIEW_LIMIT))
                            put("chunk_count", entry.chunks.size)
                            put("size_bytes", entry.totalBytes)
                        })
                    }
                })
            })
        },
    )

    private fun playTool() = Tool(
        name = "tts_library_play",
        description = "Play one saved TTS artifact by ID using its original audio files. This never calls the TTS provider or creates a replacement recording.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("artifact_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Artifact ID returned by text_to_speech or tts_library_list.")
                    })
                },
                required = listOf("artifact_id"),
            )
        },
        execute = { arguments ->
            val artifactId = arguments.jsonObject["artifact_id"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool response(buildJsonObject { put("error", "artifact_id_required") })
            if (!library.exists(artifactId)) {
                return@Tool response(buildJsonObject { put("error", "tts_artifact_not_found") })
            }
            val queued = library.queueReplay(artifactId)
            response(buildJsonObject {
                put("artifact_id", artifactId)
                put("reused_original_audio", queued)
                put("playback_queued", queued)
            })
        },
    )

    private fun canAccess(context: ToolInvocationContext): Boolean {
        val privilege = context.privilege ?: return false
        if (!privilege.isPrivileged || !privilege.expandLocalTools) return false
        if (privilege.privilegedConversationId != privilege.conversationId) return false
        if (context.callOrigin !in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return false
        return context.callerAssistantId == privilege.assistantId.toString() &&
            context.callerConversationId == privilege.conversationId.toString()
    }

    private fun response(value: kotlinx.serialization.json.JsonObject) =
        listOf(UIMessagePart.Text(value.toString()))

    private companion object {
        const val TEXT_PREVIEW_LIMIT = 160
    }
}
