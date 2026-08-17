package me.rerere.rikkahub.learning.grant

import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyGrantArchitectureTest {
    @Test
    fun `writer contract cannot receive policy body prompt evidence or actor`() {
        val fieldNames = PolicyGrantReviewCommand::class.java.declaredFields
            .map { it.name.lowercase() }
            .filterNot { it.startsWith("$") }

        FORBIDDEN_AUTHORITY_FIELD_FRAGMENTS.forEach { forbidden ->
            assertFalse("Forbidden grant command field: $forbidden", fieldNames.any { forbidden in it })
        }
        assertTrue("contentRevision" in PolicyGrantReviewCommand::class.java.declaredFields.map { it.name })
        assertTrue("artifactSha256" in PolicyGrantReviewCommand::class.java.declaredFields.map { it.name })
    }

    @Test
    fun `command snapshot and result strings redact exact identities and digest`() {
        val command = PolicyGrantReviewCommand(
            fence = PolicyGrantFence.GRANT,
            sourceStreamId = STREAM,
            scope = SCOPE,
            consumingAssistantId = CONSUMER,
            policyId = POLICY,
            contentRevision = 7L,
            artifactSha256 = SHA,
            expectedGrantStateVersion = 0L,
            frozenNowEpochMs = 9L,
            reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
        )
        val snapshot = PolicyGrantAuthoritySnapshot(
            grantId = policyGrantId(STREAM, SCOPE, CONSUMER, POLICY),
            sourceStreamId = STREAM,
            scope = SCOPE,
            consumingAssistantId = CONSUMER,
            policyId = POLICY,
            contentRevision = 7L,
            artifactSha256 = SHA,
            state = PolicyGrantAuthorityState.GRANTED,
            stateVersion = 1L,
            grantedAtEpochMs = 9L,
            revokedAtEpochMs = null,
            reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            createdAtEpochMs = 9L,
            updatedAtEpochMs = 9L,
        )
        val renderings = listOf(
            command.toString(),
            snapshot.toString(),
            PolicyGrantReviewResult.Applied(snapshot).toString(),
            PolicyGrantReviewResult.Duplicate(snapshot).toString(),
        )

        renderings.forEach { rendering ->
            assertFalse(rendering.contains(STREAM))
            assertFalse(rendering.contains(SCOPE.storageId))
            assertFalse(rendering.contains(POLICY))
            assertFalse(rendering.contains(SHA))
            assertTrue(rendering.contains("redacted"))
        }
    }
}

private val FORBIDDEN_AUTHORITY_FIELD_FRAGMENTS = listOf(
    "text",
    "body",
    "prompt",
    "evidence",
    "modeloutput",
    "renderedfragment",
    "actor",
)
private val SCOPE = LearningScope.Assistant(Uuid.parse("70000000-0000-0000-0000-000000000007"))
private val CONSUMER = SCOPE.assistantId
private const val STREAM = "80000000-0000-0000-0000-000000000008"
private const val POLICY = "sensitive-policy-id"
private val SHA = "c".repeat(64)
