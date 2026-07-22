package me.rerere.rikkahub.privilege

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.display.DisplayCapability

/**
 * Small fixed schema shared by the privileged UserService and the app process.
 *
 * This deliberately has no generic `data` object: a display Binder may only expose its identity
 * and the capabilities that were actually established by the privileged side.
 */
internal data class ManagedDisplayBridgeResponse(
    val ok: Boolean,
    val code: String,
    val displayId: Int?,
    val capabilities: Set<DisplayCapability>,
)

internal object ManagedDisplayBridgeWire {
    private val allowedKeys = setOf("ok", "code", "display_id", "capabilities", "message")
    private val codePattern = Regex("[A-Z0-9_]{2,80}")
    private val capabilityByWireName = DisplayCapability.entries.associateBy {
        it.name.lowercase(Locale.ROOT)
    }

    fun success(
        displayId: Int?,
        capabilities: Set<DisplayCapability>,
        message: String,
    ): String = encode(
        ok = true,
        code = "OK",
        displayId = displayId,
        capabilities = capabilities,
        message = message,
    )

    fun failure(
        code: String,
        message: String,
        displayId: Int? = null,
    ): String = encode(
        ok = false,
        code = code,
        displayId = displayId,
        capabilities = emptySet(),
        message = message,
    )

    fun decode(raw: String): ManagedDisplayBridgeResponse {
        val objectValue = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: throw IllegalArgumentException("Invalid managed display response JSON.")
        require(objectValue.keys.all(allowedKeys::contains)) {
            "Unexpected managed display response field."
        }

        val ok = objectValue.boolean("ok")
        val code = objectValue.string("code")
        require(codePattern.matches(code)) { "Invalid managed display response code." }
        val displayId = objectValue.displayId("display_id")
        val capabilities = objectValue.capabilities("capabilities")

        if (ok) require(code == "OK") { "Successful managed display response must use OK." }
        return ManagedDisplayBridgeResponse(ok, code, displayId, capabilities)
    }

    fun failureCode(response: ManagedDisplayBridgeResponse): String =
        response.code.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }
            ?: "display_capability_unavailable"

    private fun encode(
        ok: Boolean,
        code: String,
        displayId: Int?,
        capabilities: Set<DisplayCapability>,
        message: String,
    ): String = buildJsonObject {
        put("ok", ok)
        put("code", code)
        put("display_id", displayId?.let(::JsonPrimitive) ?: JsonNull)
        put("capabilities", buildJsonArray {
            capabilities.sortedBy { it.name }.forEach { capability ->
                add(JsonPrimitive(capability.name.lowercase(Locale.ROOT)))
            }
        })
        put("message", message.take(MAX_MESSAGE_LENGTH))
    }.toString()

    private fun JsonObject.boolean(name: String): Boolean {
        val primitive = this[name] as? JsonPrimitive
            ?: throw IllegalArgumentException("Missing managed display $name.")
        require(!primitive.isString) { "Managed display $name must be boolean." }
        return primitive.booleanOrNull
            ?: throw IllegalArgumentException("Managed display $name must be boolean.")
    }

    private fun JsonObject.string(name: String): String {
        val primitive = this[name] as? JsonPrimitive
            ?: throw IllegalArgumentException("Missing managed display $name.")
        require(primitive.isString) { "Managed display $name must be a string." }
        return primitive.contentOrNull
            ?: throw IllegalArgumentException("Missing managed display $name.")
    }

    private fun JsonObject.displayId(name: String): Int? {
        val element = this[name] ?: throw IllegalArgumentException("Missing managed display $name.")
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Managed display $name must be an integer.")
        require(!primitive.isString) { "Managed display $name must be an integer." }
        val value = primitive.intOrNull
            ?: throw IllegalArgumentException("Managed display $name must be an integer.")
        require(value > 0) { "Primary or invalid display id is forbidden." }
        return value
    }

    private fun JsonObject.capabilities(name: String): Set<DisplayCapability> {
        val values = this[name] as? JsonArray
            ?: throw IllegalArgumentException("Missing managed display capabilities.")
        return buildSet {
            values.forEach { element ->
                val primitive = element as? JsonPrimitive
                    ?: throw IllegalArgumentException("Invalid managed display capability.")
                require(primitive.isString) { "Invalid managed display capability." }
                val wireName = primitive.contentOrNull
                    ?: throw IllegalArgumentException("Invalid managed display capability.")
                val capability = capabilityByWireName[wireName]
                    ?: throw IllegalArgumentException("Unknown managed display capability.")
                require(add(capability)) { "Duplicate managed display capability." }
            }
        }
    }

    private const val MAX_MESSAGE_LENGTH = 512
}
