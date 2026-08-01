package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal const val REVERSE_GEOCODE_TOOL_NAME = "reverse_geocode"

class ReverseGeocodeToolProvider internal constructor(
    private val coordinator: ReverseGeocodeCoordinator,
) {
    internal fun createTool(): Tool = reverseGeocodeTool(coordinator)

    internal fun createLocationTool(context: android.content.Context): Tool =
        locationToolWithReverseGeocoding(context, coordinator)
}

internal fun reverseGeocodeTool(coordinator: ReverseGeocodeCoordinator): Tool = Tool(
    name = REVERSE_GEOCODE_TOOL_NAME,
    description = "Reverse-geocode a supplied WGS84 coordinate into a best-effort structured address. " +
        "Android's platform Geocoder may use an implementation-managed network service. A road, POI, " +
        "building, or formatted address is a nearby map result, not proof that the device is inside it. " +
        "Configured third-party providers require both global policy and allow_external=true.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("latitude", numberSchema("WGS84 latitude from -90 to 90."))
                put("longitude", numberSchema("WGS84 longitude from -180 to 180."))
                put("provider", stringSchema("Configured provider ID, auto, or android. Defaults to auto."))
                put("detail_level", enumSchema(AddressDetailLevel.entries.map { it.wireName }))
                put("language", stringSchema("BCP 47 language tag. Defaults to zh-CN."))
                put("allow_platform_geocoder", booleanSchema(
                    "Allow Android's implementation-managed platform Geocoder. Defaults to true.",
                ))
                put("allow_external", booleanSchema(
                    "Explicitly allow disclosure to a configured third-party provider. Defaults to false.",
                ))
                put("timeout_ms", integerSchema("Timeout from 1000 to 15000 ms. Defaults to 5000."))
            },
            required = listOf("latitude", "longitude"),
        )
    },
    execute = { input ->
        val payload = when (val parsed = parseReverseGeocodeInput(input as? JsonObject)) {
            is ReverseGeocodeInput.Invalid -> ReverseGeocodeResolution.Failure(parsed.error).toJson()
            is ReverseGeocodeInput.Valid -> {
                try {
                    coordinator.reverse(parsed.request).toJson()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ReverseGeocodeResolution.Failure(
                        ReverseGeocodeError(
                            code = "ANDROID_GEOCODER_FAILED",
                            message = "Reverse geocoding failed unexpectedly.",
                            recovery = "Try again later.",
                        ),
                    ).toJson()
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private sealed interface ReverseGeocodeInput {
    data class Valid(val request: ReverseGeocodeRequest) : ReverseGeocodeInput
    data class Invalid(val error: ReverseGeocodeError) : ReverseGeocodeInput
}

private fun parseReverseGeocodeInput(params: JsonObject?): ReverseGeocodeInput {
    if (params == null) return invalidInput("input must be a JSON object")
    val allowed = setOf(
        "latitude",
        "longitude",
        "provider",
        "detail_level",
        "language",
        "allow_platform_geocoder",
        "allow_external",
        "timeout_ms",
    )
    val unknown = params.keys - allowed
    if (unknown.isNotEmpty()) return invalidInput("unknown fields: ${unknown.sorted().joinToString()}")

    val latitude = params.number("latitude") ?: return invalidInput("latitude must be a JSON number")
    val longitude = params.number("longitude") ?: return invalidInput("longitude must be a JSON number")
    val provider = params.string("provider") ?: if ("provider" in params) {
        return invalidInput("provider must be a string")
    } else {
        AUTO_REVERSE_GEOCODER_ID
    }
    val detailName = params.string("detail_level") ?: if ("detail_level" in params) {
        return invalidInput("detail_level must be a string")
    } else {
        AddressDetailLevel.STREET.wireName
    }
    val detail = AddressDetailLevel.fromWireName(detailName)
        ?: return invalidInput("unknown detail_level: $detailName")
    val language = params.string("language") ?: if ("language" in params) {
        return invalidInput("language must be a string")
    } else {
        DEFAULT_REVERSE_GEOCODE_LANGUAGE
    }
    val allowPlatform = params.boolean("allow_platform_geocoder") ?: if ("allow_platform_geocoder" in params) {
        return invalidInput("allow_platform_geocoder must be a boolean")
    } else {
        true
    }
    val allowExternal = params.boolean("allow_external") ?: if ("allow_external" in params) {
        return invalidInput("allow_external must be a boolean")
    } else {
        false
    }
    val timeoutMs = params.integer("timeout_ms") ?: if ("timeout_ms" in params) {
        return invalidInput("timeout_ms must be an integer")
    } else {
        DEFAULT_REVERSE_GEOCODE_TIMEOUT_MS
    }

    val request = ReverseGeocodeRequest(
        latitude = latitude,
        longitude = longitude,
        providerId = provider,
        languageTag = language,
        detailLevel = detail,
        allowPlatformGeocoder = allowPlatform,
        allowExternal = allowExternal,
        timeoutMs = timeoutMs,
    )
    return when (val validation = ReverseGeocodeRequestValidator.validate(request)) {
        is ReverseGeocodeRequestValidation.Valid -> ReverseGeocodeInput.Valid(validation.request)
        is ReverseGeocodeRequestValidation.Invalid -> ReverseGeocodeInput.Invalid(validation.error)
    }
}

private fun invalidInput(message: String) = ReverseGeocodeInput.Invalid(
    ReverseGeocodeError(
        code = "INVALID_ARGUMENT",
        message = message,
        recovery = "Correct the reverse-geocoding parameters and try again.",
    ),
)

private fun JsonObject.number(name: String): Double? =
    (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull

private fun JsonObject.integer(name: String): Long? =
    (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

private fun numberSchema(description: String) = buildJsonObject {
    put("type", "number")
    put("description", description)
}

private fun integerSchema(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun booleanSchema(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun enumSchema(values: List<String>) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}
