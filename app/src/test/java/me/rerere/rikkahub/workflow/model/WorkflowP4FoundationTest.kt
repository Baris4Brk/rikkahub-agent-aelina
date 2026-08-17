package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowP4FoundationTest {
    private val toast = Tool(
        name = "show_toast",
        description = "show text",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject { put("type", "string") })
                    put("duration", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 10)
                    })
                },
                required = listOf("text"),
            )
        },
        execute = { emptyList() },
    )

    @Test
    fun `authoring validates InputSchema and stamps exact fingerprint`() {
        val ok = WorkflowJson.parse(
            """{"name":"Toast","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi","duration":2}}]}""",
            listOf(toast),
        ) as WorkflowJson.ParseResult.Ok

        val fingerprint = ok.definition.actions.single().toolSchemaFingerprint
        assertNotNull(fingerprint)
        assertTrue(WorkflowToolSchemaSnapshot.isCanonical(fingerprint!!))

        val missing = WorkflowJson.parse(
            """{"name":"Toast","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}""",
            listOf(toast),
        ) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_action_args", missing.error)

        val unknown = WorkflowJson.parse(
            """{"name":"Toast","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi","extra":true}}]}""",
            listOf(toast),
        ) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_action_args", unknown.error)
    }

    @Test
    fun `stored parser rejects every permission-bearing structural ambiguity`() {
        assertNull(WorkflowJson.parseStored(
            """{"id":"x","name":"X","future":1,"trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{}}]}""",
        ))
        assertNull(WorkflowJson.parseStored(
            """{"id":"x","name":"X","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":[],"future":1}]}""",
        ))
        assertNull(WorkflowJson.parseStored(
            """{"id":"x","name":"X","trigger":{"type":"manual"},"conditions":[{"type":"unknown"}],"actions":[{"tool":"show_toast","args":{}}]}""",
        ))
        assertNull(WorkflowJson.parseStored(
            """{"id":"x","name":"X","trigger":{"type":"unknown"},"actions":[{"tool":"show_toast","args":{}}]}""",
        ))
    }

    @Test
    fun `learned capability schema and provenance survive canonical round trip`() {
        val authored = (WorkflowJson.parse(
            """{"name":"Toast","trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi"}}]}""",
            listOf(toast),
        ) as WorkflowJson.ParseResult.Ok).definition
        val learned = authored.copy(
            id = "learned:candidate-1",
            enabled = false,
            authoringAssistantId = "assistant-1",
            capabilitySnapshot = WorkflowCapabilitySnapshot.capture(authored.actions),
            origin = WorkflowOrigin.LEARNED,
            sourceCandidateId = "candidate-1",
            sourceArtifactHash = "a".repeat(64),
            grantDigest = "b".repeat(64),
        )

        val encoded = WorkflowJson.encodeForLearned(learned)
        assertNotNull(encoded)
        val reparsed = WorkflowJson.parseStored(encoded!!)!!
        assertEquals(WorkflowOrigin.LEARNED, reparsed.origin)
        assertEquals(learned.capabilitySnapshot, reparsed.capabilitySnapshot)
        assertEquals(
            learned.actions.single().toolSchemaFingerprint,
            reparsed.actions.single().toolSchemaFingerprint,
        )
        assertEquals("candidate-1", reparsed.sourceCandidateId)
        assertEquals("a".repeat(64), reparsed.sourceArtifactHash)
        assertEquals("b".repeat(64), reparsed.grantDigest)
        assertNull(reparsed.authoritySubjectId)
        assertTrue(encoded.contains("\"authority_subject_id\":null"))

        val authorityScoped = learned.copy(authoritySubjectId = "authority-subject-1")
        val authorityEncoded = requireNotNull(WorkflowJson.encodeForLearned(authorityScoped))
        assertEquals(
            "authority-subject-1",
            WorkflowJson.parseStored(authorityEncoded)?.authoritySubjectId,
        )

        val missingDurableScope = encoded.replace(",\"authority_subject_id\":null", "")
        assertNull(WorkflowJson.parseStored(missingDurableScope))
        assertEquals(
            WorkflowJson.LearnedScopeStorage.LEGACY_MISSING,
            WorkflowJson.parseStoredWithCompatibility(missingDurableScope)?.learnedScopeStorage,
        )
    }

    @Test
    fun `learned missing assistant authority capability or schema fails closed`() {
        val action = WorkflowAction(
            tool = "show_toast",
            args = buildJsonObject { put("text", JsonPrimitive("hi")) },
            toolSchemaFingerprint = "a".repeat(64),
        )
        val complete = WorkflowDefinition(
            id = "learned:x",
            name = "X",
            enabled = false,
            trigger = TriggerSpec.Manual,
            actions = listOf(action),
            authoringAssistantId = "assistant",
            capabilitySnapshot = setOf("tool.show_toast"),
            origin = WorkflowOrigin.LEARNED,
            sourceCandidateId = "candidate",
            sourceArtifactHash = "b".repeat(64),
            grantDigest = "c".repeat(64),
        )

        assertNotNull(WorkflowJson.encodeForLearned(complete))
        assertNull(WorkflowJson.encodeForLearned(complete.copy(authoringAssistantId = null)))
        assertNull(WorkflowJson.encodeForLearned(complete.copy(grantDigest = null)))
        assertNull(WorkflowJson.encodeForLearned(complete.copy(capabilitySnapshot = emptySet())))
        assertNull(WorkflowJson.encodeForLearned(
            complete.copy(actions = listOf(action.copy(toolSchemaFingerprint = null))),
        ))
    }
}
