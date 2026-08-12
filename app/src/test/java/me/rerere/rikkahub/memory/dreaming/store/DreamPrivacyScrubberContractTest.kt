package me.rerere.rikkahub.memory.dreaming.store

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.DreamingTestFixtures
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamPrivacyScrubberContractTest {
    @Test
    fun `entire scope cannot be mixed with narrower privacy targets`() {
        assertThrows(IllegalArgumentException::class.java) {
            DreamPrivacyScrubRequest(
                DreamingTestFixtures.scope,
                listOf(
                    DreamPrivacyTarget.EntireScope,
                    DreamPrivacyTarget.AuthorityMemory("42"),
                ),
                DreamingTestFixtures.NOW,
            )
        }
    }

    @Test
    fun `successful scrub contract clears content before authority delete continues`() = runBlocking {
        val events = mutableListOf<String>()
        var title = "private title"
        var statement = "private statement"
        var snapshotPayload = "private snapshot"
        val scrubber = DreamPrivacyScrubber {
            events += "claim-tombstone"
            title = ""
            statement = ""
            events += "version-clear"
            events += "snapshot-tombstone"
            snapshotPayload = ""
            events += "active-pointer-clear"
            events += "source-delete"
            DreamPrivacyScrubResult.Scrubbed(1, 1, 1, 1, true, 4)
        }

        val result = scrubber.scrubInCurrentTransaction(
            DreamPrivacyScrubRequest(
                DreamingTestFixtures.scope,
                listOf(DreamPrivacyTarget.AuthorityMemory("42")),
                DreamingTestFixtures.NOW,
            ),
        )
        events += "authority-delete"

        assertTrue(result is DreamPrivacyScrubResult.Scrubbed)
        assertEquals("", title)
        assertEquals("", statement)
        assertEquals("", snapshotPayload)
        assertEquals(
            listOf(
                "claim-tombstone",
                "version-clear",
                "snapshot-tombstone",
                "active-pointer-clear",
                "source-delete",
                "authority-delete",
            ),
            events,
        )
    }

    @Test
    fun `tombstoned domain claim cannot retain user content or sources`() {
        val active = DreamingTestFixtures.claim()
        assertThrows(IllegalArgumentException::class.java) {
            active.copy(state = DreamClaimState.TOMBSTONED)
        }

        val tombstone = active.copy(
            state = DreamClaimState.TOMBSTONED,
            title = "",
            statement = "",
            sources = emptyList(),
        )
        assertEquals(DreamClaimState.TOMBSTONED, tombstone.state)
    }
}
