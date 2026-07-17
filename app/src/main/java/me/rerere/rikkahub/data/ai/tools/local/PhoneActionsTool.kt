package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.phone.PhoneCallController
import me.rerere.rikkahub.data.phone.PhoneCallResult

fun callPhoneTool(controller: PhoneCallController): Tool = Tool(
    name = "call_phone",
    description = """
        Directly place a real phone call using the SIM account selected by the user in
        RikkaHub settings. Use this tool whenever the user asks to call, directly dial, or
        phone a person or number. If the user gives only a contact name, call
        search_contacts first and pass the chosen phone number here. Never use open_url or
        a tel: URL for a phone-call request: open_url is not allowed to handle telephone
        schemes. The user controls the SIM selection; never ask for or invent a SIM id.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("phone_number", buildJsonObject {
                    put("type", "string")
                    put("description", "Phone number to call, for example +8613800138000")
                })
            },
            required = listOf("phone_number"),
        )
    },
    execute = { input ->
        val number = input.jsonObject["phone_number"]?.jsonPrimitive?.contentOrNull
        if (number.isNullOrBlank()) {
            return@Tool listOf(phoneError(
                code = "MISSING_NUMBER",
                message = "phone_number is required.",
                recovery = "Provide a phone number. For a contact name, use search_contacts first.",
            ))
        }

        val result = controller.placeCall(number)
        listOf(when (result) {
            is PhoneCallResult.Success -> UIMessagePart.Text(buildJsonObject {
                put("ok", true)
                put("code", "CALL_PLACED")
                put("message", "The phone call was placed using the user's selected SIM.")
                put("data", buildJsonObject {
                    put("phone_number", result.phoneNumber)
                    put("phone_account_label", result.account.label)
                })
            }.toString())

            is PhoneCallResult.MissingPermission -> phoneError(
                code = "NO_PERMISSION",
                message = "${result.permission} permission is not granted.",
                recovery = "Open the Direct phone calls tool setting and grant the requested phone permissions.",
            )

            PhoneCallResult.NoAvailableAccount -> phoneError(
                code = "NO_PHONE_ACCOUNT",
                message = "No call-capable SIM account is currently available.",
                recovery = "Check that a SIM or eSIM is enabled, then reopen the phone tool settings.",
            )

            PhoneCallResult.AccountSelectionRequired -> phoneError(
                code = "PHONE_ACCOUNT_SELECTION_REQUIRED",
                message = "More than one SIM is available and no default calling SIM has been selected in RikkaHub.",
                recovery = "Choose the default calling SIM in the Direct phone calls tool settings.",
            )

            is PhoneCallResult.AccountUnavailable -> phoneError(
                code = "PHONE_ACCOUNT_UNAVAILABLE",
                message = "The user's selected calling SIM is no longer available.",
                recovery = "Ask the user to select an available SIM in the Direct phone calls tool settings. Do not retry with another SIM.",
            )

            PhoneCallResult.InvalidPhoneNumber -> phoneError(
                code = "INVALID_NUMBER",
                message = "phone_number must be a valid telephone number.",
                recovery = "Use search_contacts again or provide a number containing only digits and normal phone punctuation.",
            )

            is PhoneCallResult.Failed -> phoneError(
                code = "CALL_FAILED",
                message = result.message,
                recovery = "Do not silently switch SIMs. Report the failure to the user.",
            )
        })
    },
)

private fun phoneError(
    code: String,
    message: String,
    recovery: String,
): UIMessagePart.Text = UIMessagePart.Text(buildJsonObject {
    put("ok", false)
    put("error", code)
    put("code", code)
    put("message", message)
    put("recovery", recovery)
}.toString())
