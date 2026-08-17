package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RemoteBackgroundDispatchAttestationTest {
    @Test
    fun `planner factory is deterministic and binds the official API family`() {
        val common = RemoteBackgroundDispatchContext(
            providerIdentitySha256 = "a".repeat(64),
            modelIdentitySha256 = "b".repeat(64),
            configurationIdentitySha256 = "c".repeat(64),
            templateVersion = "reflection-v1",
            inputIdentitySha256 = "d".repeat(64),
            providerRequestKey = "learning-provider-v1:${"e".repeat(64)}",
            maxOutputTokens = 1_024,
        )
        val openCode = RemoteBackgroundDispatchAttestation(
            apiFamily = RemoteBackgroundApiFamily.OPENCODE_GO_CHAT_COMPLETIONS_V1,
            context = common,
        ).opaqueDigestSha256()

        assertEquals(
            openCode,
            expectedRemoteBackgroundDispatchAttestationSha256(
                providerIdentitySha256 = common.providerIdentitySha256,
                modelIdentitySha256 = common.modelIdentitySha256,
                configurationIdentitySha256 = common.configurationIdentitySha256,
                templateVersion = common.templateVersion,
                inputIdentitySha256 = common.inputIdentitySha256,
                providerRequestKey = common.providerRequestKey,
                maxOutputTokens = common.maxOutputTokens,
                apiFamily = RemoteBackgroundApiFamily.OPENCODE_GO_CHAT_COMPLETIONS_V1,
            ),
        )
        assertEquals(64, openCode.length)
        assertNotEquals(
            openCode,
            RemoteBackgroundDispatchAttestation(
                apiFamily = RemoteBackgroundApiFamily.OPENAI_CHAT_COMPLETIONS_V1,
                context = common,
            ).opaqueDigestSha256(),
        )
        assertNotEquals(
            openCode,
            RemoteBackgroundDispatchAttestation(
                apiFamily = RemoteBackgroundApiFamily.OPENCODE_GO_CHAT_COMPLETIONS_V1,
                context = common.copy(maxOutputTokens = common.maxOutputTokens + 1),
            ).opaqueDigestSha256(),
        )
    }
}
