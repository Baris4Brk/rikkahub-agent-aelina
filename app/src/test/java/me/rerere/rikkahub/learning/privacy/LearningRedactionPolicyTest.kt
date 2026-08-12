package me.rerere.rikkahub.learning.privacy

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.MissingSourceRevisionReason
import me.rerere.rikkahub.learning.storage.LearningJobErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningRedactionPolicyTest {
    @Test
    fun evidenceAliasesDoNotExposeStableIdentifiers() {
        val source = source("private-command-id", revision = 4)
        val aliases = LearningEvidenceAliasTable.create(listOf(source))
        assertEquals("E1", aliases.aliasFor(source))
        assertFalse(aliases.toString().contains("private-command-id"))
        assertTrue(aliases.containsAlias("E1"))
    }

    @Test
    fun revisionlessSourceCannotBecomeOutboundPersistentEvidence() {
        val source = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "legacy-command",
            sourceRevision = null,
            missingRevisionReason = MissingSourceRevisionReason.LEGACY_IMPORT,
            databaseStreamId = STREAM,
            scope = LearningScope.Assistant(ASSISTANT),
            occurredAtMs = 1,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LearningEvidenceAliasTable.create(listOf(source))
        }
    }

    @Test
    fun unknownErrorTextCollapsesToAllowlistedCode() {
        assertEquals(LearningJobErrorCode.UNKNOWN, LearningErrorCodePolicy.parseOrUnknown("secret=/tmp/a"))
        assertEquals(
            LearningJobErrorCode.SOURCE_STALE,
            LearningErrorCodePolicy.parseOrUnknown("SOURCE_STALE"),
        )
    }

    private fun source(id: String, revision: Long) = LearningSourceRef(
        sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
        sourceId = id,
        sourceRevision = revision,
        missingRevisionReason = null,
        databaseStreamId = STREAM,
        scope = LearningScope.Assistant(ASSISTANT),
        occurredAtMs = 1,
    )

    private companion object {
        val STREAM = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val ASSISTANT = Uuid.parse("00000000-0000-0000-0000-000000000002")
    }
}
