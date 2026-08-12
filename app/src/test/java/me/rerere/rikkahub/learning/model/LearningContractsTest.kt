package me.rerere.rikkahub.learning.model

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningContractsTest {
    private val assistantA = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val assistantB = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val streamId = Uuid.parse("10000000-0000-0000-0000-000000000001")

    @Test
    fun `scope parser canonicalizes assistants and rejects global or malformed values`() {
        assertEquals(
            LearningScope.Assistant(assistantA),
            LearningScope.parseOrNull("ASSISTANT", assistantA.toString()),
        )
        assertEquals(
            LearningScope.AuthoritySubject("owner-subject:1"),
            LearningScope.parseOrNull("AUTHORITY_SUBJECT", "owner-subject:1"),
        )
        assertNull(LearningScope.parseOrNull("GLOBAL", "__global__"))
        assertNull(LearningScope.parseOrNull("ASSISTANT", "not-a-uuid"))
        assertNull(LearningScope.parseOrNull("AUTHORITY_SUBJECT", "bad\nsubject"))
        assertNull(LearningScope.parseOrNull("AUTHORITY_SUBJECT", "x".repeat(161)))
    }

    @Test
    fun `assistant and authority scopes follow the exact applicability matrix`() {
        val assistantRequest = LearningApplicabilityRequest(
            assistantId = assistantA,
            authoritySubjectId = null,
            authorityPolicyOptIn = false,
            grantConsumingAssistantId = null,
        )
        assertTrue(
            LearningScopePolicy.isCandidateAllowed(
                LearningScope.Assistant(assistantA),
                assistantRequest,
            ),
        )
        assertFalse(
            LearningScopePolicy.isCandidateAllowed(
                LearningScope.Assistant(assistantB),
                assistantRequest,
            ),
        )

        val authorityRequest = LearningApplicabilityRequest(
            assistantId = assistantA,
            authoritySubjectId = "owner-subject",
            authorityPolicyOptIn = true,
            grantConsumingAssistantId = assistantA,
        )
        assertTrue(
            LearningScopePolicy.isCandidateAllowed(
                LearningScope.AuthoritySubject("owner-subject"),
                authorityRequest,
            ),
        )
        assertFalse(
            LearningScopePolicy.isCandidateAllowed(
                LearningScope.AuthoritySubject("other-subject"),
                authorityRequest,
            ),
        )
        assertFalse(
            LearningScopePolicy.isCandidateAllowed(
                LearningScope.AuthoritySubject("owner-subject"),
                authorityRequest.copy(authorityPolicyOptIn = false),
            ),
        )
        assertFalse(
            LearningScopePolicy.isCandidateAllowed(
                LearningScope.AuthoritySubject("owner-subject"),
                authorityRequest.copy(grantConsumingAssistantId = assistantB),
            ),
        )
    }

    @Test
    fun `only versioned sources may support persistent policy evidence`() {
        val versioned = source(sourceRevision = 4L, missingReason = null)
        val unversioned = source(
            sourceRevision = null,
            missingReason = MissingSourceRevisionReason.AUTHORITY_HAS_NO_REVISION,
        )

        assertTrue(versioned.eligibleForPersistentPolicyEvidence)
        assertFalse(unversioned.eligibleForPersistentPolicyEvidence)
        assertFalse(
            versioned.copy(
                sourceKind = LearningSourceKind.EXECUTION_EVENT,
                sourceRevision = null,
                missingRevisionReason = MissingSourceRevisionReason.RETENTION_GAP,
            ).eligibleForPersistentPolicyEvidence,
        )
        assertFails { source(sourceRevision = null, missingReason = null) }
        assertFails {
            source(
                sourceRevision = 4L,
                missingReason = MissingSourceRevisionReason.UNKNOWN,
            )
        }
    }

    @Test
    fun `unknown event code and version remain distinguishable`() {
        val known = LearningEventCode("COMMAND_TERMINAL", schemaVersion = 1)
        val unknown = LearningEventCode("FUTURE_EVENT", schemaVersion = 1)
        val futureVersion = LearningEventCode(
            "COMMAND_TERMINAL",
            schemaVersion = LearningEventCode.CURRENT_LEARNING_EVENT_SCHEMA_VERSION + 1,
        )

        assertEquals(LearningEventDecodeState.KNOWN, known.decodeState)
        assertTrue(known.producesJob)
        assertEquals("FUTURE_EVENT", unknown.rawCode)
        assertEquals(LearningEventDecodeState.UNKNOWN_NO_JOB, unknown.decodeState)
        assertFalse(unknown.producesJob)
        assertEquals(LearningEventDecodeState.INCOMPATIBLE_SCHEMA, futureVersion.decodeState)
        assertFalse(futureVersion.producesJob)
    }

    @Test
    fun `learning model string projections redact raw identifiers and unknown codes`() {
        val raw = "conversation-secret-123"
        val correlation = LearningCorrelation(conversationId = raw, commandId = "command-1")
        val source = source(sourceRevision = 1L, missingReason = null, sourceId = raw)
        val request = LearningApplicabilityRequest(
            assistantId = assistantA,
            authoritySubjectId = raw,
            authorityPolicyOptIn = true,
            grantConsumingAssistantId = assistantA,
        )
        val eventCode = LearningEventCode("PRIVATE_SECRET_CODE", schemaVersion = 1)

        assertFalse(correlation.toString().contains(raw))
        assertFalse(source.toString().contains(raw))
        assertFalse(request.toString().contains(raw))
        assertFalse(request.toString().contains(assistantA.toString()))
        assertFalse(eventCode.toString().contains("PRIVATE_SECRET_CODE"))
        assertFails { LearningCorrelation(commandId = "bad\u0000id") }
    }

    @Test
    fun `all rollout flags default to no writes and no provider effects`() {
        val flags = LearningFeatureFlags()

        assertFalse(flags.schemaReady)
        assertFalse(flags.hasBusinessWritesEnabled)
        assertFalse(flags.hasProviderEffectEnabled)
        assertFalse(flags.allowRemoteReflection)
        assertFalse(flags.copy(schemaReady = true).hasBusinessWritesEnabled)
    }

    private fun source(
        sourceRevision: Long?,
        missingReason: MissingSourceRevisionReason?,
        sourceId: String = "conversation-secret-123",
    ) = LearningSourceRef(
        sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
        sourceId = sourceId,
        sourceRevision = sourceRevision,
        missingRevisionReason = missingReason,
        databaseStreamId = streamId,
        scope = LearningScope.Assistant(assistantA),
        occurredAtMs = 1_000L,
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected IllegalArgumentException", failed)
    }
}
