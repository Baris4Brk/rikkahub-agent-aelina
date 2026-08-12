package me.rerere.rikkahub.memory.dreaming.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamObserverModelsTest {
    @Test
    fun `scope accepts only raw canonical assistant UUID or exact global sentinel`() {
        val assistant = "123e4567-e89b-12d3-a456-426614174000"

        assertEquals(assistant, DreamScopeId.requireCanonical(assistant).value)
        assertEquals(DreamScopeId.Global, DreamScopeId.parseOrNull("__global__"))
        assertTrue(DreamScopeId.Global.isGlobal)
        assertFalse(DreamScopeId.requireCanonical(assistant).isGlobal)

        listOf(
            null,
            "",
            " ",
            "global",
            "assistant:$assistant",
            " $assistant",
            "$assistant ",
            assistant.uppercase(),
            "123e4567-e89b-12d3-a456-42661417400",
            "00000000-0000-0000-0000-000000000000-extra",
        ).forEach { invalid ->
            assertNull("unexpected scope: $invalid", DreamScopeId.parseOrNull(invalid))
        }
    }

    @Test
    fun `unknown storage enums and normalized spellings fail closed`() {
        assertEquals(
            AuthorityEntityKind.MEMORY,
            DreamObserverStorageCodec.authorityEntityKindOrNull("MEMORY"),
        )
        assertEquals(
            AuthorityChangeOperation.SCRUB,
            DreamObserverStorageCodec.authorityOperationOrNull("SCRUB"),
        )
        assertEquals(
            DreamRunStatus.RUNNING,
            DreamObserverStorageCodec.runStatusOrNull("RUNNING"),
        )
        assertNull(DreamObserverStorageCodec.authorityEntityKindOrNull("memory"))
        assertNull(DreamObserverStorageCodec.authorityOperationOrNull("UPDATE "))
        assertNull(DreamObserverStorageCodec.authorityReasonOrNull("USER_TEXT"))
        assertNull(DreamObserverStorageCodec.runModeOrNull("LIGHT"))
        assertNull(DreamObserverStorageCodec.runStatusOrNull("UNKNOWN"))
        assertNull(DreamObserverStorageCodec.runFailureCodeOrNull("TIMEOUT_TEXT"))
    }

    @Test
    fun `M1 behavior defaults are all off and invalid enablement is rejected`() {
        val flags = DreamingFeatureFlags.M1AllOff

        assertFalse(flags.schemaReady)
        assertFalse(flags.generate)
        assertFalse(flags.shadow)
        assertFalse(flags.use)
        assertFalse(flags.deepRebuild)
        assertFalse(flags.relationRoute)
        assertThrows(IllegalArgumentException::class.java) {
            DreamingFeatureFlags(schemaReady = false, generate = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DreamingFeatureFlags(schemaReady = true, shadow = true)
        }
    }

    @Test
    fun `invalid identifiers revision and lease metadata are rejected at DTO boundary`() {
        val scope = DreamScopeId.Global

        assertThrows(IllegalArgumentException::class.java) {
            AuthorityChange(
                scopeId = scope,
                entityKind = AuthorityEntityKind.MEMORY,
                entityId = "\n",
                entityRevision = 1,
                operation = AuthorityChangeOperation.UPDATE,
                reasonCode = AuthorityChangeReason.USER_MUTATION,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityChange(
                scopeId = scope,
                entityKind = AuthorityEntityKind.MEMORY,
                entityId = "memory-a",
                entityRevision = 0,
                operation = AuthorityChangeOperation.UPDATE,
                reasonCode = AuthorityChangeReason.USER_MUTATION,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalDreamRunId("not-a-run")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireDreamLeaseOwner("bad\nowner")
        }
    }

    @Test
    fun `restored receipt and terminal run DTOs reject corrupt persisted fields`() {
        val scope = DreamScopeId.Global
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityChangeReceipt(
                changeId = 1,
                scopeId = scope,
                memoryEpoch = 1,
                entityKind = AuthorityEntityKind.MEMORY,
                entityId = "bad\u0000id",
                entityRevision = 0,
                operation = AuthorityChangeOperation.UPDATE,
                reasonCode = AuthorityChangeReason.USER_MUTATION,
                createdAtMs = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityChange(
                scopeId = scope,
                entityKind = AuthorityEntityKind.MEMORY,
                entityId = "memory-a",
                entityRevision = 1,
                operation = AuthorityChangeOperation.UPDATE,
                reasonCode = AuthorityChangeReason.RUN_HEARTBEAT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DreamRun(
                runId = "00000000-0000-0000-0000-000000000001",
                scopeId = scope,
                mode = DreamRunMode.OBSERVER_REPLAY,
                status = DreamRunStatus.FAILED,
                baseMemoryEpoch = 0,
                baseObserverCheckpointEpoch = 0,
                attempt = 1,
                leaseOwner = null,
                leaseUntilMs = null,
                checkpointEpoch = 0,
                failureCode = null,
                createdAtMs = 1,
                startedAtMs = 1,
                updatedAtMs = 2,
                finishedAtMs = 2,
            )
        }
    }
}
