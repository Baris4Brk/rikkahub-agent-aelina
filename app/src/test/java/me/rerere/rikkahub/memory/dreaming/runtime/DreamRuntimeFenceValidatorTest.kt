package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamRuntimeFenceValidatorTest {
    @Test
    fun `unavailable projection is never treated as an empty valid snapshot`() {
        val result = DreamRuntimeFenceValidator.validate(
            projection = DreamSnapshotProjection.Unavailable(
                DreamSnapshotProjectionUnavailableReason.PAYLOAD_PARSE_FAILED,
            ),
            expectedScopeId = DreamRuntimeTestFixtures.scope,
        ) as DreamRuntimeFenceResult.Invalid

        assertEquals(listOf(DreamRuntimeFenceFailure.PROJECTION_UNAVAILABLE), result.failures)
        assertEquals(
            DreamSnapshotProjectionUnavailableReason.PAYLOAD_PARSE_FAILED,
            result.unavailableReason,
        )
    }

    @Test
    fun `scope pointer epoch revision integrity and atomic read are hard fences`() {
        val otherScope = DreamScopeId.requireCanonical("99999999-9999-4999-8999-999999999999")
        val projection = DreamRuntimeTestFixtures.projection(
            scopeId = otherScope,
            activeSnapshotId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            status = DreamRuntimeSnapshotStatus.STALE,
            currentMemoryEpoch = 8L,
            currentDreamRevision = 4L,
            payloadIntegrity = DreamRuntimePayloadIntegrity.MISMATCH,
            readConsistency = DreamRuntimeReadConsistency.UNKNOWN,
        )

        val result = DreamRuntimeFenceValidator.validate(
            projection = projection,
            expectedScopeId = DreamRuntimeTestFixtures.scope,
        ) as DreamRuntimeFenceResult.Invalid

        assertTrue(DreamRuntimeFenceFailure.READ_NOT_ATOMIC in result.failures)
        assertTrue(DreamRuntimeFenceFailure.SCOPE_MISMATCH in result.failures)
        assertTrue(DreamRuntimeFenceFailure.SNAPSHOT_NOT_ACTIVE in result.failures)
        assertTrue(DreamRuntimeFenceFailure.ACTIVE_POINTER_MISMATCH in result.failures)
        assertTrue(DreamRuntimeFenceFailure.MEMORY_EPOCH_MISMATCH in result.failures)
        assertTrue(DreamRuntimeFenceFailure.DREAM_REVISION_MISMATCH in result.failures)
        assertTrue(DreamRuntimeFenceFailure.SNAPSHOT_REVISION_MISMATCH in result.failures)
        assertTrue(DreamRuntimeFenceFailure.PAYLOAD_INTEGRITY_FAILED in result.failures)
    }

    @Test
    fun `manifest count ordinal and fragment mismatch reject the whole projection`() {
        val claims = listOf(
            DreamRuntimeTestFixtures.claim(
                ordinal = 1,
                fragmentIntegrity = DreamRuntimeFragmentIntegrity.MISMATCH,
            ),
        )
        val result = DreamRuntimeFenceValidator.validate(
            projection = DreamRuntimeTestFixtures.projection(
                claims = claims,
                expectedClaimCount = 2,
            ),
            expectedScopeId = DreamRuntimeTestFixtures.scope,
        ) as DreamRuntimeFenceResult.Invalid

        assertTrue(DreamRuntimeFenceFailure.CLAIM_COUNT_INVALID in result.failures)
        assertTrue(DreamRuntimeFenceFailure.MANIFEST_ORDINAL_INVALID in result.failures)
        assertTrue(DreamRuntimeFenceFailure.CLAIM_FRAGMENT_INTEGRITY_FAILED in result.failures)
    }

    @Test
    fun `fully current atomically read projection passes`() {
        val result = DreamRuntimeFenceValidator.validate(
            projection = DreamRuntimeTestFixtures.projection(),
            expectedScopeId = DreamRuntimeTestFixtures.scope,
        )

        assertTrue(result is DreamRuntimeFenceResult.Valid)
    }
}
