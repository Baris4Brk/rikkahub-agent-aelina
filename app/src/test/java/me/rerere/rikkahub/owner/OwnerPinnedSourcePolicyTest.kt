package me.rerere.rikkahub.owner

import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPinnedSourcePolicyTest {
    @Test
    fun `accepts immutable versions commits and hashes`() {
        assertTrue(OwnerPinnedSourcePolicy.isPinned("v1.2.3"))
        assertTrue(OwnerPinnedSourcePolicy.isPinned("0123456789abcdef"))
        assertTrue(OwnerPinnedSourcePolicy.isPinned("sha256:${"ab".repeat(32)}"))
    }

    @Test
    fun `rejects floating or ambiguous pins`() {
        assertFalse(OwnerPinnedSourcePolicy.isPinned("latest"))
        assertFalse(OwnerPinnedSourcePolicy.isPinned("main"))
        assertFalse(OwnerPinnedSourcePolicy.isPinned("1.x"))
        assertFalse(OwnerPinnedSourcePolicy.isPinned(""))
    }

    @Test
    fun `registry content must actually match its hash commit or version pin`() {
        val bytes = "{\"name\":\"exa\"}".encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = buildJsonObject {
            put("commit", "0123456789abcdef")
            put("version", "v1.2.3")
        }

        assertTrue(OwnerPinnedSourcePolicy.verifyManifest("sha256:$hash", bytes, manifest))
        assertTrue(OwnerPinnedSourcePolicy.verifyManifest("0123456789abcdef", bytes, manifest))
        assertTrue(OwnerPinnedSourcePolicy.verifyManifest("1.2.3", bytes, manifest))
        assertFalse(OwnerPinnedSourcePolicy.verifyManifest("1.2.4", bytes, manifest))
        assertFalse(OwnerPinnedSourcePolicy.verifyManifest("sha256:${"00".repeat(32)}", bytes, manifest))
    }
}
