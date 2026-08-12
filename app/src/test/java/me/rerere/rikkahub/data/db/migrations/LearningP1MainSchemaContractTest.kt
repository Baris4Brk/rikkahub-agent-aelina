package me.rerere.rikkahub.data.db.migrations

import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningP1MainSchemaContractTest {
    @Test
    fun `P1 command and execution authority columns are nullable additive legacy gates`() {
        assertEquals(
            mapOf(
                "branchAnchorMessageRevision" to "INTEGER",
                "conversationSourceRevision" to "INTEGER",
                "completionKind" to "TEXT",
                "resultAssistantMessageId" to "TEXT",
                "resultAssistantMessageRevision" to "INTEGER",
            ),
            LEARNING_V46_P1_COMMAND_AUTHORITY_COLUMNS.toMap(),
        )
        assertEquals(
            mapOf(
                "tool_call_id" to "TEXT",
                "tool_name" to "TEXT",
                "tool_schema_fingerprint" to "TEXT",
                "owning_assistant_message_id" to "TEXT",
                "owning_assistant_message_revision" to "INTEGER",
            ),
            LEARNING_V46_P1_EXECUTION_AUTHORITY_COLUMNS.toMap(),
        )
        val allDeclarations = (
            LEARNING_V46_P1_COMMAND_AUTHORITY_COLUMNS +
                LEARNING_V46_P1_EXECUTION_AUTHORITY_COLUMNS
            ).joinToString("\n") { it.second }
        assertFalse(allDeclarations.contains("DEFAULT", ignoreCase = true))
        assertFalse(allDeclarations.contains("NOT NULL", ignoreCase = true))
    }

    @Test
    fun `source authority tables are content-free tombstone heads without parent foreign keys`() {
        val sql = LEARNING_V46_SOURCE_AUTHORITY_TABLE_AND_INDEX_SQL.joinToString("\n")
        assertEquals(
            setOf(
                "learning_conversation_source_authority",
                "learning_message_source_authority",
            ),
            LEARNING_V46_SOURCE_AUTHORITY_TABLE_AND_INDEX_SQL
                .filter { it.startsWith("CREATE TABLE") }
                .mapNotNull { statement -> Regex("`([^`]+)`").find(statement)?.groupValues?.get(1) }
                .toSet(),
        )
        listOf(
            "source_revision",
            "previous_source_revision",
            "source_state",
            "change_kind",
            "payload_integrity_sha256",
        ).forEach { column -> assertTrue(sql.contains("`$column`")) }
        listOf(
            "messages",
            "payload_json",
            "prompt",
            "reasoning",
            "tool_args",
            "tool_output",
            "credential",
        ).forEach { forbidden ->
            assertFalse("forbidden source-authority field $forbidden", sql.contains("`$forbidden`"))
        }
        assertFalse(sql.contains("FOREIGN KEY"))
        assertFalse(sql.contains("REFERENCES"))
    }

    @Test
    fun `outbox P1 delta stays typed and sentinel treats every new field as payload`() {
        assertEquals(
            setOf(
                "previous_source_revision",
                "source_state",
                "conversation_source_revision",
                "branch_anchor_message_revision",
                "completion_kind",
                "tool_name",
                "tool_schema_fingerprint",
                "message_revision",
            ),
            LEARNING_V46_OUTBOX_P1_COLUMNS.map { it.first }.toSet(),
        )
        LEARNING_V46_OUTBOX_P1_COLUMNS.forEach { (column, _) ->
            assertTrue(column in LEARNING_V46_SENTINEL_PAYLOAD_COLUMNS)
        }
        assertFalse(LEARNING_V46_OUTBOX_TABLE_SQL.contains("payload_json"))
        assertFalse(LEARNING_V46_OUTBOX_TABLE_SQL.contains("correlation_json"))
    }

    @Test
    fun `source authority entities reject fake revisions and tombstone resurrection material`() {
        assertThrows(IllegalArgumentException::class.java) {
            conversation(revision = 3, previous = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            conversation(
                revision = 2,
                previous = 1,
                state = "TOMBSTONED",
                branchMessageId = MESSAGE_ID,
                branchMessageRevision = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            message(revision = 1, previous = null, state = "ACTIVE", digest = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            message(
                revision = 1,
                previous = null,
                state = "ACTIVE",
                digest = "not-a-revision-token",
            )
        }
        message(revision = 2, previous = 1, state = "TOMBSTONED", digest = null)
    }

    private fun conversation(
        revision: Long,
        previous: Long?,
        state: String = "ACTIVE",
        branchMessageId: String? = MESSAGE_ID,
        branchMessageRevision: Long? = 1,
    ) = LearningConversationSourceAuthorityEntity(
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = CONVERSATION_ID,
        assistantIdSnapshot = SCOPE_ID,
        sourceRevision = revision,
        previousSourceRevision = previous,
        sourceState = state,
        changeKind = if (state == "TOMBSTONED") "CONVERSATION_DELETED" else "UPDATED",
        branchHeadMessageId = branchMessageId,
        branchHeadMessageRevision = branchMessageRevision,
        occurredAtMs = revision,
        updatedAtMs = revision,
    )

    private fun message(
        revision: Long,
        previous: Long?,
        state: String,
        digest: String?,
    ) = LearningMessageSourceAuthorityEntity(
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = CONVERSATION_ID,
        messageId = MESSAGE_ID,
        messageRole = "ASSISTANT",
        sourceRevision = revision,
        previousSourceRevision = previous,
        sourceState = state,
        changeKind = if (state == "TOMBSTONED") "DELETED" else "UPDATED",
        payloadIntegritySha256 = digest,
        occurredAtMs = revision,
        updatedAtMs = revision,
    )

    private companion object {
        const val SCOPE_ID = "00000000-0000-0000-0000-000000000001"
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000002"
        const val MESSAGE_ID = "00000000-0000-0000-0000-000000000003"
    }
}
