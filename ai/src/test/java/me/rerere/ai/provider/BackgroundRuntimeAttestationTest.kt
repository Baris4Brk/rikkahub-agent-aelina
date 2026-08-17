package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BackgroundRuntimeAttestationTest {
    @Test
    fun `standalone digest is deterministic and every field changes it`() {
        val baseline = attestation()
        val digest = baseline.opaqueDigestSha256()

        assertEquals(digest, baseline.copy().opaqueDigestSha256())
        assertEquals(64, digest.length)
        assertNotEquals(
            digest,
            baseline.copy(providerRuntimeAbi = "runtime-v2").opaqueDigestSha256(),
        )
        assertNotEquals(digest, baseline.copy(sdkAbi = "sdk-v2").opaqueDigestSha256())
        assertNotEquals(
            digest,
            baseline.copy(cancellationFenceAbi = "cancel-v2").opaqueDigestSha256(),
        )
        assertNotEquals(digest, baseline.copy(artifactSha256 = "b".repeat(64)).opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(forceCpu = false).opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(accelerator = "GPU").opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(contextWindowTokens = 8_192).opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(topK = 32).opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(topP = 0.9).opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(temperature = 0.5).opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(promptRendererAbi = "prompt-v2").opaqueDigestSha256())
        assertNotEquals(digest, baseline.copy(nativeToolAbi = "native-v2").opaqueDigestSha256())
    }

    @Test
    fun `stable provider idempotency key stays transient`() {
        val encoded = Json.encodeToString(
            TextGenerationParams.serializer(),
            TextGenerationParams(
                model = Model(modelId = "test"),
                stableProviderIdempotencyKey = "provider-attempt-123",
            ),
        )

        assertFalse(encoded.contains("provider-attempt-123"))
        assertFalse(encoded.contains("stableProviderIdempotencyKey"))
    }

    private fun attestation() = BackgroundRuntimeAttestation(
        providerRuntimeAbi = "runtime-v1",
        sdkAbi = "sdk-v1",
        cancellationFenceAbi = "cancel-v1",
        artifactSha256 = "a".repeat(64),
        forceCpu = true,
        accelerator = "CPU",
        contextWindowTokens = 4_096,
        topK = 64,
        topP = 0.95,
        temperature = 1.0,
        promptRendererAbi = "prompt-v1",
        nativeToolAbi = "native-v1",
    )
}
