package me.rerere.rikkahub.security

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralToolResultStoreTest {
    private val binding = SecretPlaintextSessionBinding(
        authoritySubjectId = "owner-subject",
        authorityEpoch = 7,
        assistantId = "assistant",
        conversationId = "conversation",
        modelId = "model",
        providerId = "provider",
    )

    @Test
    fun `plaintext materializes once only for the exact bound provider request`() {
        var now = 1_000L
        val store = EphemeralToolResultStore(
            allowsEgress = { source, target -> source == target },
            nowMs = { now },
        )
        val issued = store.issue("one-use-secret".toCharArray(), binding)
        val messages = messages(issued.token)

        val first = store.materializeForProvider(messages, binding).toString()
        val second = store.materializeForProvider(messages, binding).toString()

        assertTrue(first.contains("one-use-secret"))
        assertFalse(second.contains("one-use-secret"))
        assertTrue(second.contains("SECRET_EPHEMERAL_EXPIRED"))
        now += EphemeralToolResultStore.RESULT_TTL_MS + 1
    }

    @Test
    fun `mismatched provider binding consumes and denies the ephemeral payload`() {
        val store = EphemeralToolResultStore(allowsEgress = { _, _ -> false })
        val issued = store.issue("never-egress".toCharArray(), binding)

        val result = store.materializeForProvider(
            messages(issued.token),
            binding.copy(providerId = "other-provider"),
        ).toString()

        assertFalse(result.contains("never-egress"))
        assertTrue(result.contains("SECRET_EGRESS_DENIED"))
    }

    @Test
    fun `compact owner action envelope materializes nested ephemeral payload`() {
        val store = EphemeralToolResultStore(allowsEgress = { source, target -> source == target })
        val issued = store.issue("nested-one-use-secret".toCharArray(), binding)
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "nested-secret-call",
                        toolName = "owner_secret_manage",
                        input = "{}",
                        output = listOf(
                            UIMessagePart.Text(
                                """{"ok":true,"actions":[{"type":"secret_plaintext_reveal","data":{"_ephemeral_secret_token":"${issued.token}","value":"[SECRET_REVEALED]"}}]}""",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val materialized = store.materializeForProvider(messages, binding).toString()

        assertTrue(materialized.contains("nested-one-use-secret"))
        assertFalse(materialized.contains(issued.token))
    }

    @Test
    fun `provider credential inventory materializes multiple keys exactly once`() {
        val store = EphemeralToolResultStore(allowsEgress = { source, target -> source == target })
        val first = store.issue("provider-key-one".toCharArray(), binding)
        val second = store.issue("provider-key-two".toCharArray(), binding)
        val messages = listOf(UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "provider-credential-inventory",
                toolName = "owner_secret_manage",
                input = "{}",
                output = listOf(UIMessagePart.Text(
                    """{"ok":true,"actions":[{"data":{"providers":[{"value":"[SECRET_REVEALED]","_ephemeral_secret_token":"${first.token}"},{"value":"[SECRET_REVEALED]","_ephemeral_secret_token":"${second.token}"}]}}]}""",
                )),
            )),
        ))

        val materialized = store.materializeForProvider(messages, binding).toString()
        val replay = store.materializeForProvider(messages, binding).toString()

        assertTrue(materialized.contains("provider-key-one"))
        assertTrue(materialized.contains("provider-key-two"))
        assertFalse(materialized.contains(first.token))
        assertFalse(materialized.contains(second.token))
        assertFalse(replay.contains("provider-key-one"))
        assertFalse(replay.contains("provider-key-two"))
        assertTrue(replay.contains("SECRET_EPHEMERAL_EXPIRED"))
    }

    private fun messages(token: String) = listOf(
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "secret-call",
                    toolName = "owner_secret_manage",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text(
                            """{"ok":true,"_ephemeral_secret_token":"$token","value":"[SECRET_REVEALED]"}""",
                        ),
                    ),
                ),
            ),
        ),
    )
}
