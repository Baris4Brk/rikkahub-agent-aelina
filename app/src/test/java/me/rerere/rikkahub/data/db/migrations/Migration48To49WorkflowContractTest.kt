package me.rerere.rikkahub.data.db.migrations

import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration48To49WorkflowContractTest {
    private val legacy = """{"id":"w1","name":"Legacy","enabled":false,"trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi"}}],"authoring_assistant_id":"assistant-1"}"""

    @Test
    fun `v49 declares the exact durable workflow authority columns`() {
        assertEquals(
            listOf(
                "stateVersion", "origin", "sourceCandidateId", "sourceArtifactHash",
                "grantDigest", "authoringAssistantId", "capabilitySnapshotJson",
                "toolSchemaFingerprintsJson", "staleReason",
            ),
            WORKFLOW_V49_COLUMNS.map { it.first },
        )
        assertEquals("INTEGER NOT NULL DEFAULT 1", WORKFLOW_V49_COLUMNS[0].second)
        assertEquals("TEXT NOT NULL DEFAULT 'USER'", WORKFLOW_V49_COLUMNS[1].second)
    }

    @Test
    fun `legacy rows become explicit user authority with canonical projections`() {
        val migrated = workflowV49Backfill(legacy, projectedEnabled = true)
        assertNotNull(migrated)
        migrated!!
        assertEquals("assistant-1", migrated.authoringAssistantId)
        assertTrue(migrated.capabilitySnapshotJson.startsWith("["))
        val parsed = WorkflowJson.parseStoredWithCompatibility(migrated.definitionJson)
        assertNotNull(parsed)
        assertEquals(WorkflowOrigin.USER, parsed!!.definition.origin)
        assertTrue(parsed.definition.enabled)
        assertEquals(
            parsed.definition.capabilitySnapshot.sorted(),
            parsed.definition.capabilitySnapshot.toList(),
        )
        assertTrue(parsed.definition.actions.all { it.toolSchemaFingerprint == null })
        assertFalse(migrated.definitionJson.contains("source_candidate_id"))
        assertFalse(migrated.definitionJson.contains("grant_digest"))
    }

    @Test
    fun `malformed legacy rows are never expanded into authority`() {
        assertEquals(null, workflowV49Backfill("{not-json"))
        assertEquals(null, workflowV49Backfill(
            """{"id":"w","name":"X","trigger":{"type":"manual"},"actions":[{"tool":"x","args":[] }]}""",
        ))
    }
}
