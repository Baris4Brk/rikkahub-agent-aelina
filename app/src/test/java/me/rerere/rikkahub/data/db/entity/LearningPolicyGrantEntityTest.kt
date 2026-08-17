package me.rerere.rikkahub.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class LearningPolicyGrantEntityTest {
    @Test
    fun `grant accepts only explicit user review in a non-global scope`() {
        assertThrows(IllegalArgumentException::class.java) {
            grant(scopeKind = "GLOBAL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            grant(actor = "SYSTEM")
        }
        assertEquals("GRANTED", grant().state)
    }

    @Test
    fun `grant binds content revision independently from grant state version`() {
        val head = grant(policyRevision = 7L, stateVersion = 3L)

        assertEquals(7L, head.policyRevision)
        assertEquals(3L, head.stateVersion)
    }

    @Test
    fun `revision snapshots must be contiguous and time-identical`() {
        val head = grant(stateVersion = 3L)
        val revision = head.toRevisionEntity()

        assertEquals(2L, revision.previousStateVersion)
        assertEquals(revision.updatedAtMs, revision.changedAtMs)
        assertThrows(IllegalArgumentException::class.java) {
            revision.copy(previousStateVersion = 1L)
        }
    }

    private fun grant(
        scopeKind: String = "ASSISTANT",
        actor: String = "USER_REVIEW",
        policyRevision: Long = 2L,
        stateVersion: Long = 1L,
    ) = LearningPolicyGrantEntity(
        grantId = "grant-1",
        sourceStreamId = "10000000-0000-0000-0000-000000000001",
        policyId = "policy-1",
        policyRevision = policyRevision,
        artifactSha256 = "a".repeat(64),
        scopeKind = scopeKind,
        scopeId = ASSISTANT_ID,
        consumingAssistantId = ASSISTANT_ID,
        actor = actor,
        state = "GRANTED",
        stateVersion = stateVersion,
        grantedAtMs = 10L,
        revokedAtMs = null,
        reasonCode = "USER_APPROVED",
        createdAtMs = 10L,
        updatedAtMs = 20L,
    )
}

private const val ASSISTANT_ID = "90000000-0000-0000-0000-000000000009"
