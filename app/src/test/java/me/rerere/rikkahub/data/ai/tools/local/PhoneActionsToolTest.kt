package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.phone.PhoneAccountKey
import me.rerere.rikkahub.data.phone.PhoneAccountOption
import me.rerere.rikkahub.data.phone.PhoneCallController
import me.rerere.rikkahub.data.phone.PhoneCallResult
import me.rerere.rikkahub.data.phone.PhoneCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionsToolTest {
    @Test
    fun `call phone returns the selected account used by the controller`() = runBlocking {
        val account = PhoneAccountOption(
            PhoneAccountKey("com.android.phone/.ConnectionService", "sim-2"),
            "SIM 2",
        )
        val controller = FakePhoneCallController(
            PhoneCallResult.Success("13800138000", account),
        )

        val result = callPhoneTool(controller)
            .execute(Json.parseToJsonElement("""{"phone_number":"13800138000"}"""))
            .single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(result.text).jsonObject

        assertTrue(payload["ok"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("CALL_PLACED", payload["code"]?.jsonPrimitive?.content)
        assertEquals(
            "SIM 2",
            payload["data"]?.jsonObject?.get("phone_account_label")?.jsonPrimitive?.content,
        )
        assertEquals(listOf("13800138000"), controller.numbers)
    }

    @Test
    fun `call phone never exposes a SIM selector to the model`() {
        val tool = callPhoneTool(FakePhoneCallController(PhoneCallResult.AccountSelectionRequired))
        val schemaText = tool.parameters()?.toString().orEmpty()

        assertTrue(schemaText.contains("phone_number"))
        assertFalse(schemaText.contains("sim", ignoreCase = true))
        assertFalse(schemaText.contains("account_id", ignoreCase = true))
        assertTrue(tool.description.contains("search_contacts"))
        assertTrue(tool.description.contains("Never use open_url", ignoreCase = true))
    }

    @Test
    fun `open url rejects telephone schemes so the model must use call phone`() {
        assertTrue(isTelephoneUrl("tel:+8613800138000"))
        assertTrue(isTelephoneUrl("TEL:13800138000"))
        assertFalse(isTelephoneUrl("https://example.com/tel:123"))
    }
}

private class FakePhoneCallController(
    private val result: PhoneCallResult,
) : PhoneCallController {
    private val mutableState = MutableStateFlow(PhoneCallState())
    override val state: StateFlow<PhoneCallState> = mutableState
    val numbers = mutableListOf<String>()

    override suspend fun refresh() = Unit

    override suspend fun selectAccount(key: PhoneAccountKey) = Unit

    override suspend fun placeCall(phoneNumber: String): PhoneCallResult {
        numbers += phoneNumber
        return result
    }
}
