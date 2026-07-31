package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.GenerationCompletionPolicy
import me.rerere.ai.ui.GenerationOutcome
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmlToolCallRecoveryTest {
    @Test
    fun `well formed DSML becomes a real typed tool call and raw protocol disappears`() {
        val raw = """
            I will inspect the API.
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="termux_session_send">
            <｜｜DSML｜｜parameter name="input" string="true">python3 -c "print('ok')"</｜｜DSML｜｜parameter>
            <｜｜DSML｜｜parameter name="session_id" string="true">rk_test</｜｜DSML｜｜parameter>
            <｜｜DSML｜｜parameter name="timeout_seconds" string="false">15</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
        """.trimIndent()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(raw)),
        )

        val recovered = message.recoverDsmlToolCalls(setOf("termux_session_send"))

        assertTrue(recovered.detected)
        assertFalse(recovered.malformed)
        assertEquals(1, recovered.recoveredTools.size)
        val tool = recovered.recoveredTools.single()
        assertEquals("termux_session_send", tool.toolName)
        val args = Json.parseToJsonElement(tool.input).jsonObject
        assertEquals("python3 -c \"print('ok')\"", args.getValue("input").jsonPrimitive.content)
        assertEquals("rk_test", args.getValue("session_id").jsonPrimitive.content)
        assertEquals(15, args.getValue("timeout_seconds").jsonPrimitive.content.toInt())
        assertTrue(recovered.message.parts.first() is UIMessagePart.Text)
        assertTrue(recovered.message.parts.last() is UIMessagePart.Tool)
        assertFalse(recovered.message.parts.filterIsInstance<UIMessagePart.Text>().any {
            "DSML" in it.text
        })
        assertEquals(
            GenerationOutcome.ContinueToolLoop,
            GenerationCompletionPolicy.evaluate(
                recovered.message,
                GenerationTerminal.fromProviderReason("stop"),
            ),
        )
    }

    @Test
    fun `unknown tool is suppressed instead of becoming visible or executable`() {
        val raw = """
            before
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="not_exposed">
            <｜｜DSML｜｜parameter name="value" string="true">x</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
        """.trimIndent()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(raw)),
        )

        val recovered = message.recoverDsmlToolCalls(setOf("termux_session_send"))

        assertTrue(recovered.detected)
        assertTrue(recovered.malformed)
        assertTrue(recovered.recoveredTools.isEmpty())
        assertTrue(recovered.message.parts.isEmpty())
        assertTrue(
            GenerationCompletionPolicy.evaluate(
                recovered.message,
                GenerationTerminal.missingTransportTerminal("malformed DSML suppressed"),
            ) is GenerationOutcome.NeedsFinalAnswer,
        )
    }

    @Test
    fun `invalid non string JSON parameter suppresses the complete DSML text part`() {
        val raw = """
            <｜｜DSML｜｜tool_calls>
            <｜｜DSML｜｜invoke name="termux_session_send">
            <｜｜DSML｜｜parameter name="timeout_seconds" string="false">not-json</｜｜DSML｜｜parameter>
            </｜｜DSML｜｜invoke>
            </｜｜DSML｜｜tool_calls>
        """.trimIndent()
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("safe earlier text"), UIMessagePart.Text(raw)),
        )

        val recovered = message.recoverDsmlToolCalls(setOf("termux_session_send"))

        assertTrue(recovered.malformed)
        assertEquals(listOf("safe earlier text"), recovered.message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .map(UIMessagePart.Text::text))
    }
}
