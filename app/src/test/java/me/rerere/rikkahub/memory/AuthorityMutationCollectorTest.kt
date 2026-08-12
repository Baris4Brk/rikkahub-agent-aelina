package me.rerere.rikkahub.memory

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityMutationCollectorTest {
    @Test
    fun finalMutationWinsForOneAuthorityEntity() {
        val memory = memory(revision = 1)
        val collector = AuthorityMutationCollector(AuthorityChangeReason.USER_MUTATION)

        collector.memory(memory, MemoryRevisionOperation.CREATE)
        collector.memory(memory.copy(revision = 2), MemoryRevisionOperation.ARCHIVE)

        val change = collector.snapshot().single()
        assertEquals("7", change.entityId)
        assertEquals(2L, change.entityRevision)
        assertEquals(AuthorityChangeOperation.ARCHIVE, change.operation)
    }

    @Test
    fun projectionIgnoresOnlyRevisionAndAccessMetadata() {
        val memory = memory(revision = 1)

        assertTrue(
            memory.hasSameAuthorityProjection(
                memory.copy(revision = 9, updatedAtMs = 99, lastAccessedAtMs = 88),
            ),
        )
        assertFalse(memory.hasSameAuthorityProjection(memory.copy(originAssistantId = null)))
        assertFalse(memory.hasSameAuthorityProjection(memory.copy(content = "changed")))
    }

    private fun memory(revision: Int) = MemoryEntity(
        id = 7,
        assistantId = SCOPE,
        content = "authority",
        revision = revision,
        originAssistantId = SCOPE,
    )

    private companion object {
        const val SCOPE = "11111111-1111-1111-1111-111111111111"
    }
}
