package me.rerere.rikkahub.memory.dreaming.model

import me.rerere.rikkahub.memory.dreaming.DreamingTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DreamAuthorityFingerprintTest {
    @Test
    fun `fingerprint covers semantic metadata and ignores source input order`() {
        val base = DreamingTestFixtures.memory(
            sources = listOf(
                DreamingTestFixtures.source(messageId = "b", digest = "b".repeat(64)),
                DreamingTestFixtures.source(messageId = "a", digest = "a".repeat(64)),
            ),
        )
        val reversed = base.copy(sources = base.sources.reversed(), tags = base.tags.reversed())

        assertEquals(DreamAuthorityFingerprintV1.compute(base), DreamAuthorityFingerprintV1.compute(reversed))
        assertNotEquals(
            DreamAuthorityFingerprintV1.compute(base),
            DreamAuthorityFingerprintV1.compute(base.copy(truthStatus = me.rerere.rikkahub.memory.MemoryTruthStatus.DISPUTED)),
        )
        assertNotEquals(
            DreamAuthorityFingerprintV1.compute(base),
            DreamAuthorityFingerprintV1.compute(base.copy(expiresAtEpochMs = DreamingTestFixtures.NOW + 1)),
        )
    }

    @Test
    fun `opaque identities have exact entropy encoded lengths`() {
        DreamOpaqueToken("m_" + "A".repeat(22))
        DreamOpaqueToken("c_" + "_".repeat(22))
        DreamProposalNonce("p_" + "N".repeat(43))

        assertThrows(IllegalArgumentException::class.java) { DreamOpaqueToken("m_" + "A".repeat(23)) }
        assertThrows(IllegalArgumentException::class.java) { DreamProposalNonce("p_" + "N".repeat(42)) }
    }

    @Test
    fun `fixed canonical source manifest vector cannot drift`() {
        val source = DreamAuthoritySource(
            conversationId = "c",
            messageId = "m",
            role = me.rerere.rikkahub.memory.MemorySourceRole.USER,
            sourceKind = me.rerere.rikkahub.memory.MemorySourceKind.TEXT,
            consumedTextDigest = DreamSha256("0".repeat(64)),
            evidenceGroupId = "g",
        )

        assertEquals(
            "ed518725c9bf0e6f9633cf9b152933d405c5e5d6bbebc224d42c3c55588728ff",
            DreamAuthorityFingerprintV1.sourceManifestHash(listOf(source)).value,
        )
    }

    @Test
    fun `fence freezes valid IANA timezone and last applied range`() {
        assertThrows(IllegalArgumentException::class.java) {
            DreamingTestFixtures.fence(baseMemoryEpoch = 2, lastAppliedEpoch = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DreamingTestFixtures.fence().copy(sourceTimezoneId = "GMT+08:00")
        }
    }
}
