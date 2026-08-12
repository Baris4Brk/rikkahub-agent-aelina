package me.rerere.rikkahub.memory.dreaming.temporal

import java.time.Instant
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalSourceValidityGateTest {
    @Test
    fun `current confirmed untombstoned exact authority source is usable`() {
        val result = TemporalSourceValidityGate.evaluate(validRequest())

        assertTrue(result.isUsable)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `every authority invariant independently fails closed`() {
        val cases = listOf(
            validRequest().copy(timezoneId = "GMT+08:00") to
                TemporalSourceInvalidReason.INVALID_TIMEZONE,
            validRequest().copy(actualScopeId = DreamScopeId.Global) to
                TemporalSourceInvalidReason.SCOPE_MISMATCH,
            validRequest().copy(actualRevision = 8) to
                TemporalSourceInvalidReason.REVISION_MISMATCH,
            validRequest().copy(actualContentHash = "b".repeat(64)) to
                TemporalSourceInvalidReason.HASH_MISMATCH,
            validRequest().copy(sourceTombstoned = true) to
                TemporalSourceInvalidReason.SOURCE_TOMBSTONED,
            validRequest().copy(lifecycle = TemporalSourceLifecycle.ARCHIVED) to
                TemporalSourceInvalidReason.LIFECYCLE_NOT_ACTIVE,
            validRequest().copy(truth = TemporalSourceTruth.DISPUTED) to
                TemporalSourceInvalidReason.TRUTH_NOT_CONFIRMED,
            validRequest().copy(expiresAtEpochMs = NOW) to
                TemporalSourceInvalidReason.MEMORY_EXPIRED,
        )

        cases.forEach { (request, reason) ->
            val result = TemporalSourceValidityGate.evaluate(request)
            assertFalse(reason.name, result.isUsable)
            assertEquals(listOf(reason), result.reasons)
        }
    }

    @Test
    fun `multiple corruptions return stable metadata-only reasons in enum order`() {
        val invalid = validRequest().copy(
            timezoneId = "not/a-zone",
            actualScopeId = DreamScopeId.Global,
            actualRevision = 0,
            actualContentHash = "",
            sourceTombstoned = true,
            lifecycle = TemporalSourceLifecycle.STALE,
            truth = TemporalSourceTruth.SUPERSEDED,
            expiresAtEpochMs = NOW - 1,
        )

        val first = TemporalSourceValidityGate.evaluate(invalid)
        val replay = TemporalSourceValidityGate.evaluate(invalid.copy())
        assertFalse(first.isUsable)
        assertEquals(TemporalSourceInvalidReason.entries, first.reasons)
        assertEquals(first, replay)
        assertTrue(
            first.reasons.none {
                it.name.contains("11111111") || it.name.contains(HASH)
            },
        )
    }

    @Test
    fun `expiry boundary uses frozen now and is independent from source timestamp`() {
        assertFalse(
            TemporalSourceValidityGate.evaluate(
                validRequest().copy(
                    expiresAtEpochMs = NOW,
                    sourceTimestampEpochMs = NOW + 86_400_000L,
                ),
            ).isUsable,
        )
        assertTrue(
            TemporalSourceValidityGate.evaluate(
                validRequest().copy(
                    expiresAtEpochMs = NOW + 1,
                    sourceTimestampEpochMs = null,
                ),
            ).isUsable,
        )
    }

    private fun validRequest() = TemporalSourceValidityRequest(
        expectedScopeId = SCOPE,
        actualScopeId = SCOPE,
        expectedRevision = 7,
        actualRevision = 7,
        expectedContentHash = HASH,
        actualContentHash = HASH,
        lifecycle = TemporalSourceLifecycle.ACTIVE,
        truth = TemporalSourceTruth.CONFIRMED,
        expiresAtEpochMs = NOW + 10_000,
        sourceTombstoned = false,
        frozenNowEpochMs = NOW,
        sourceTimestampEpochMs = NOW - 1_000,
        timezoneId = "Asia/Shanghai",
    )

    private companion object {
        val SCOPE = DreamScopeId.requireCanonical("11111111-1111-1111-1111-111111111111")
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val NOW: Long = Instant.parse("2026-08-12T00:00:00Z").toEpochMilli()
    }
}
