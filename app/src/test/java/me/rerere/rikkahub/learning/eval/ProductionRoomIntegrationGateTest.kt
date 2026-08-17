package me.rerere.rikkahub.learning.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionRoomIntegrationGateTest {
    @Test
    fun `passed attestation requires every frozen production boundary`() {
        val complete = ProductionRoomIntegrationAttestationFactory.passed(
            FrozenProductionRoomIntegrationContractV1.requiredChecks,
        )

        assertEquals(ProductionRoomIntegrationState.PASSED, complete.state)
        assertEquals(complete.requiredCheckCount, complete.observedCheckCount)
        assertTrue(complete.attestationDigestSha256.matches(Regex("[0-9a-f]{64}")))
        assertFalse(complete.toString().contains(FrozenProductionRoomIntegrationContractV1.FIXTURE_ID))

        assertThrows(IllegalArgumentException::class.java) {
            ProductionRoomIntegrationAttestationFactory.passed(
                setOf(ProductionRoomIntegrationCheck.APP_DATABASE_ROOM_OPENED),
            )
        }
    }

    @Test
    fun `failure attestations retain only closed reason checks and digests`() {
        val observed = setOf(
            ProductionRoomIntegrationCheck.APP_DATABASE_ROOM_OPENED,
            ProductionRoomIntegrationCheck.LEARNING_DATABASE_FACADE_OPENED,
        )
        val abstained = ProductionRoomIntegrationAttestationFactory.abstained(
            ProductionRoomIntegrationReason.RUNTIME_OR_STORAGE_UNAVAILABLE,
            observed,
        )
        val rejected = ProductionRoomIntegrationAttestationFactory.rejected(
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
            observed,
        )

        assertEquals(ProductionRoomIntegrationState.ABSTAINED, abstained.state)
        assertEquals(ProductionRoomIntegrationState.REJECTED, rejected.state)
        assertFalse(abstained.attestationDigestSha256 == rejected.attestationDigestSha256)
        listOf(abstained, rejected).forEach { attestation ->
            assertFalse(attestation.toString().contains("prompt"))
            assertFalse(attestation.toString().contains("exception"))
            assertFalse(attestation.toString().contains("fixture payload"))
        }
    }
}
