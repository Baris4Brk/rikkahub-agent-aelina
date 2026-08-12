package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryQueryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsRevisionTest {
    @Test
    fun schemaAndMutationsRequireObservedRevision() = runBlocking {
        var updateRevision: Int? = null
        var deleteRevision: Int? = null
        val tool = tools(
            onUpdateRevision = { updateRevision = it },
            onDeleteRevision = { deleteRevision = it },
        ).first { it.name == "memory_tool" }
        val schema = tool.parameters() as InputSchema.Obj
        assertNotNull(schema.properties["expected_revision"])

        val missingRevision = runCatching {
            tool.execute(Json.parseToJsonElement("""{"action":"edit","id":7,"content":"new"}"""))
        }.exceptionOrNull()
        assertTrue(missingRevision is IllegalStateException)

        tool.execute(
            Json.parseToJsonElement(
                """{"action":"edit","id":7,"expected_revision":3,"content":"new"}""",
            ),
        )
        tool.execute(
            Json.parseToJsonElement(
                """{"action":"delete","id":7,"expected_revision":4}""",
            ),
        )
        assertEquals(3, updateRevision)
        assertEquals(4, deleteRevision)
    }

    @Test
    fun queryAndMutationPayloadsExposeRevision() = runBlocking {
        val tools = tools()
        val create = tools.first { it.name == "memory_tool" }.execute(
            Json.parseToJsonElement("""{"action":"create","content":"fact"}"""),
        ).single() as UIMessagePart.Text
        assertEquals(9, Json.parseToJsonElement(create.text).jsonObject["revision"]?.jsonPrimitive?.content?.toInt())

        val query = tools.first { it.name == "memory_query" }.execute(
            Json.parseToJsonElement("""{"query":"fact"}"""),
        ).single() as UIMessagePart.Text
        val first = Json.parseToJsonElement(query.text).jsonArray.single().jsonObject
        assertEquals(9, first["revision"]?.jsonPrimitive?.content?.toInt())
    }

    private fun tools(
        onUpdateRevision: (Int) -> Unit = {},
        onDeleteRevision: (Int) -> Unit = {},
    ) = buildMemoryTools(
        onCreation = {
            AssistantMemory(id = 7, content = it.content, revision = 9)
        },
        onUpdate = { id, revision, input ->
            onUpdateRevision(revision)
            AssistantMemory(id = id, content = input.content, revision = revision + 1)
        },
        onDelete = { _, revision -> onDeleteRevision(revision) },
        onQuery = {
            listOf(
                MemoryQueryRecord(
                    id = 7,
                    title = "Fact",
                    content = "fact",
                    kind = MemoryKind.OTHER,
                    tags = emptyList(),
                    sourceType = "TEST",
                    updatedAtMs = 1L,
                    importance = 1f,
                    score = 1.0,
                    matchedTerms = listOf("fact"),
                    reason = "test",
                    revision = 9,
                ),
            )
        },
    )
}
