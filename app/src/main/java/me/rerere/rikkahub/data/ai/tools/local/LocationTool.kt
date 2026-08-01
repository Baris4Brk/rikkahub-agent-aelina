package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val MIN_LOCATION_TIMEOUT_MS = 5_000L
private const val MAX_LOCATION_TIMEOUT_MS = 60_000L

fun locationTool(context: Context): Tool = createLocationTool(
    resolverProvider = { AndroidLocationSource(context) },
)

internal fun locationToolWithReverseGeocoding(
    context: Context,
    coordinator: ReverseGeocodeCoordinator,
): Tool = createLocationTool(
    resolverProvider = { AndroidLocationSource(context) },
    reverseGeocodeCoordinator = coordinator,
)

@Suppress("UNUSED_PARAMETER")
internal fun locationTool(
    context: Context,
    resolver: LocationResolver,
    reverseGeocodeCoordinator: ReverseGeocodeCoordinator? = null,
): Tool = createLocationTool(
    resolverProvider = { resolver },
    reverseGeocodeCoordinator = reverseGeocodeCoordinator,
)

private fun createLocationTool(
    resolverProvider: () -> LocationResolver,
    reverseGeocodeCoordinator: ReverseGeocodeCoordinator? = null,
): Tool = Tool(
    name = "get_location",
    description = "Get the device's current WGS84 location using Android native providers first. " +
        "Works without Google Play Services and reports whether a result is fresh, cached, or precision-limited. " +
        "Optional reverse geocoding is best effort: a detailed address is a nearby map result, not proof that " +
        "the device is inside a POI, building, or road. Android's platform Geocoder may use a managed network service.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("accuracy", buildJsonObject {
                    put("type", "string")
                    put("enum", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("high"))
                        add(kotlinx.serialization.json.JsonPrimitive("balanced"))
                        add(kotlinx.serialization.json.JsonPrimitive("low"))
                    })
                    put("description", "Accuracy preference. Defaults to balanced.")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Total timeout in milliseconds, 5000..60000. Defaults: high=45000, balanced=30000, low=15000.",
                    )
                })
                put("allow_cached", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Allow direct or fallback cached locations. Defaults to true.")
                })
                put("direct_cache_max_age_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum cache age that may skip a new request. High defaults to disabled.")
                })
                put("fallback_cache_max_age_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum cache age allowed only after fresh location attempts fail.")
                })
                put("include_address", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Resolve an optional structured address. Defaults to false.")
                })
                put("address_mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        AddressFailureMode.entries.forEach { add(JsonPrimitive(it.wireName)) }
                    })
                    put("description", "best_effort keeps a successful location when address lookup fails; required returns a partial result.")
                })
                put("address_provider", buildJsonObject {
                    put("type", "string")
                    put("description", "Configured provider ID, auto, or android. Defaults to auto.")
                })
                put("address_detail", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        AddressDetailLevel.entries.forEach { add(JsonPrimitive(it.wireName)) }
                    })
                    put("description", "Requested address detail. Defaults to street.")
                })
                put("address_language", buildJsonObject {
                    put("type", "string")
                    put("description", "BCP 47 language tag. Defaults to zh-CN.")
                })
                put("allow_platform_geocoder", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Allow Android's implementation-managed platform Geocoder. Defaults to true.")
                })
                put("allow_external_address", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Explicitly allow a configured third-party provider. Defaults to false.")
                })
                put("address_timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Address timeout from 1000 to 15000 ms. Defaults to 5000.")
                })
            }
        )
    },
    execute = { input ->
        val params = input as? JsonObject
        val accuracyName = (params?.get("accuracy") as? JsonPrimitive)?.contentOrNull ?: "balanced"
        val accuracy = RequestedAccuracy.fromWireName(accuracyName)
        val invalid = when {
            params == null -> invalidArgument("input must be a JSON object")
            params["accuracy"] != null && params["accuracy"] !is JsonPrimitive ->
                invalidArgument("accuracy must be a string")
            accuracy == null -> invalidArgument("unknown accuracy: $accuracyName")
            params["timeout_ms"] != null && (params["timeout_ms"] as? JsonPrimitive)?.longOrNull == null ->
                invalidArgument("timeout_ms must be an integer")
            params["allow_cached"] != null && (params["allow_cached"] as? JsonPrimitive)?.booleanOrNull == null ->
                invalidArgument("allow_cached must be a boolean")
            params["direct_cache_max_age_ms"] != null &&
                (params["direct_cache_max_age_ms"] as? JsonPrimitive)?.longOrNull == null ->
                invalidArgument("direct_cache_max_age_ms must be an integer")
            params["fallback_cache_max_age_ms"] != null &&
                (params["fallback_cache_max_age_ms"] as? JsonPrimitive)?.longOrNull == null ->
                invalidArgument("fallback_cache_max_age_ms must be an integer")
            params["include_address"] != null &&
                params.booleanValue("include_address") == null ->
                invalidArgument("include_address must be a boolean")
            params["address_mode"] != null && params.stringValue("address_mode") == null ->
                invalidArgument("address_mode must be a string")
            params["address_provider"] != null && params.stringValue("address_provider") == null ->
                invalidArgument("address_provider must be a string")
            params["address_detail"] != null && params.stringValue("address_detail") == null ->
                invalidArgument("address_detail must be a string")
            params["address_language"] != null && params.stringValue("address_language") == null ->
                invalidArgument("address_language must be a string")
            params["allow_platform_geocoder"] != null &&
                params.booleanValue("allow_platform_geocoder") == null ->
                invalidArgument("allow_platform_geocoder must be a boolean")
            params["allow_external_address"] != null &&
                params.booleanValue("allow_external_address") == null ->
                invalidArgument("allow_external_address must be a boolean")
            params["address_timeout_ms"] != null &&
                params.integerValue("address_timeout_ms") == null ->
                invalidArgument("address_timeout_ms must be an integer")
            else -> null
        }
        val payload = if (invalid != null || accuracy == null || params == null) {
            invalid ?: invalidArgument("unknown accuracy: $accuracyName")
        } else {
            val timeoutMs = (params["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: accuracy.defaultTimeoutMs
            val allowCached = (params["allow_cached"] as? JsonPrimitive)?.booleanOrNull ?: true
            val directCacheMaxAgeMs = (params["direct_cache_max_age_ms"] as? JsonPrimitive)?.longOrNull
                ?: accuracy.defaultDirectCacheMaxAgeMs
            val fallbackCacheMaxAgeMs = (params["fallback_cache_max_age_ms"] as? JsonPrimitive)?.longOrNull
                ?: accuracy.defaultFallbackCacheMaxAgeMs
            val includeAddress = params.booleanValue("include_address") ?: false
            val addressModeName = params.stringValue("address_mode") ?: AddressFailureMode.BEST_EFFORT.wireName
            val addressMode = AddressFailureMode.fromWireName(addressModeName)
            val addressProvider = params.stringValue("address_provider") ?: AUTO_REVERSE_GEOCODER_ID
            val addressDetailName = params.stringValue("address_detail") ?: AddressDetailLevel.STREET.wireName
            val addressDetail = AddressDetailLevel.fromWireName(addressDetailName)
            val addressLanguage = params.stringValue("address_language") ?: DEFAULT_REVERSE_GEOCODE_LANGUAGE
            val allowPlatformGeocoder = params.booleanValue("allow_platform_geocoder") ?: true
            val allowExternalAddress = params.booleanValue("allow_external_address") ?: false
            val addressTimeoutMs = params.integerValue("address_timeout_ms")
                ?: DEFAULT_REVERSE_GEOCODE_TIMEOUT_MS
            val addressRequestValidation = if (includeAddress) {
                ReverseGeocodeRequestValidator.validate(
                    ReverseGeocodeRequest(
                        latitude = 0.0,
                        longitude = 0.0,
                        providerId = addressProvider,
                        languageTag = addressLanguage,
                        detailLevel = addressDetail ?: AddressDetailLevel.STREET,
                        allowPlatformGeocoder = allowPlatformGeocoder,
                        allowExternal = allowExternalAddress,
                        timeoutMs = addressTimeoutMs,
                    ),
                )
            } else {
                null
            }
            when {
                timeoutMs !in MIN_LOCATION_TIMEOUT_MS..MAX_LOCATION_TIMEOUT_MS ->
                    invalidArgument("timeout_ms must be between 5000 and 60000")
                directCacheMaxAgeMs < 0L ->
                    invalidArgument("direct_cache_max_age_ms must be non-negative")
                fallbackCacheMaxAgeMs < 0L ->
                    invalidArgument("fallback_cache_max_age_ms must be non-negative")
                directCacheMaxAgeMs > fallbackCacheMaxAgeMs ->
                    invalidArgument("direct_cache_max_age_ms must not exceed fallback_cache_max_age_ms")
                addressMode == null -> invalidArgument("unknown address_mode: $addressModeName")
                addressDetail == null -> invalidArgument("unknown address_detail: $addressDetailName")
                !ReverseGeocodeRequestValidator.isValidProviderId(addressProvider) ->
                    invalidArgument("address_provider must be a lowercase configured provider ID")
                ReverseGeocodeRequestValidator.normalizeLanguageTag(addressLanguage) == null ->
                    invalidArgument("address_language must be a valid BCP 47 language tag with 2 to 35 characters")
                addressTimeoutMs !in MIN_REVERSE_GEOCODE_TIMEOUT_MS..MAX_REVERSE_GEOCODE_TIMEOUT_MS ->
                    invalidArgument("address_timeout_ms must be between 1000 and 15000")
                addressRequestValidation is ReverseGeocodeRequestValidation.Invalid ->
                    addressRequestValidation.error.toLocationRequestError()
                else -> {
                    try {
                        val location = resolverProvider().resolve(
                            LocationRequest(
                                accuracy = accuracy,
                                timeoutMs = timeoutMs,
                                allowCached = allowCached,
                                directCacheMaxAgeMs = directCacheMaxAgeMs,
                                fallbackCacheMaxAgeMs = fallbackCacheMaxAgeMs,
                            ),
                        )
                        if (!includeAddress || location !is LocationResolution.Success) {
                            location.toJson()
                        } else {
                            val reverseRequest = (
                                addressRequestValidation as? ReverseGeocodeRequestValidation.Valid
                                )?.request?.copy(
                                latitude = location.fix.latitude,
                                longitude = location.fix.longitude,
                            )
                                ?: error("Validated address request missing")
                            val addressResult = resolveAddress(
                                coordinator = reverseGeocodeCoordinator,
                                request = reverseRequest,
                            )
                            location.withAddress(addressResult, addressMode)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        LocationResolution.Failure(
                            code = "INTERNAL_ERROR",
                            message = "Unexpected location failure.",
                            recovery = "Retry the location request.",
                        ).toJson()
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private suspend fun resolveAddress(
    coordinator: ReverseGeocodeCoordinator?,
    request: ReverseGeocodeRequest,
): ReverseGeocodeResolution {
    if (coordinator == null) {
        return ReverseGeocodeResolution.Failure(
            ReverseGeocodeError(
                code = "ANDROID_GEOCODER_UNAVAILABLE",
                message = "Address resolution is unavailable in this tool instance.",
                recovery = "Use the application-provided location tool or call reverse_geocode separately.",
            ),
        )
    }
    return try {
        coordinator.reverse(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ReverseGeocodeResolution.Failure(
            ReverseGeocodeError(
                code = "ANDROID_GEOCODER_FAILED",
                message = "Address resolution failed unexpectedly.",
                recovery = "Try again later.",
            ),
        )
    }
}

private fun LocationResolution.Success.withAddress(
    result: ReverseGeocodeResolution,
    mode: AddressFailureMode,
): JsonObject {
    val locationJson = toJson()
    return buildJsonObject {
        locationJson.forEach { (key, value) -> put(key, value) }
        when (result) {
            is ReverseGeocodeResolution.Success -> {
                put("address_status", "resolved")
                put("address", result.address.toJson())
                put("address_cached", result.cached)
                result.cacheAgeMs?.let { put("address_cache_age_ms", it.coerceAtLeast(0L)) }
                put("address_attempted_providers", buildJsonArray {
                    result.attemptedProviders.distinct().forEach { add(JsonPrimitive(it)) }
                })
            }
            is ReverseGeocodeResolution.Failure -> {
                if (mode == AddressFailureMode.REQUIRED) {
                    put("ok", false)
                    put("partial", true)
                    put("location_ok", true)
                }
                put("address_status", "failed")
                put("address_error", buildJsonObject {
                    put("code", result.error.code)
                    put("message", result.error.message)
                    result.error.recovery?.let { put("recovery", it) }
                    put("attempted_providers", buildJsonArray {
                        result.attemptedProviders.distinct().forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        }
    }
}

private fun JsonObject.stringValue(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private fun JsonObject.integerValue(name: String): Long? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun invalidArgument(message: String): JsonObject = LocationResolution.Failure(
    code = "INVALID_ARGUMENT",
    message = message,
    recovery = "Correct the input parameters and try again.",
).toJson()

private fun ReverseGeocodeError.toLocationRequestError(): JsonObject = buildJsonObject {
    put("ok", false)
    put("code", code)
    put("message", message)
    recovery?.let { put("recovery", it) }
}

private fun LocationResolution.toJson(): JsonObject = when (this) {
    is LocationResolution.Success -> buildJsonObject {
        put("ok", true)
        put("latitude", fix.latitude)
        put("longitude", fix.longitude)
        put("accuracy_m", fix.accuracyM)
        fix.altitudeM?.let {
            put("altitude", it)
            put("altitude_m", it)
        }
        fix.speedMps?.let {
            put("speed", it)
            put("speed_mps", it)
        }
        fix.bearingDegrees?.let {
            put("bearing", it)
            put("bearing_degrees", it)
        }
        put("provider", fix.provider)
        put("source", source.wireName)
        put("source_type", sourceType.wireName)
        put("fresh", fresh)
        put("cached", cached)
        put("generated_after_request", generatedAfterRequest)
        put("age_ms", ageMs.coerceAtLeast(0L))
        put("permission_precision", permissionPrecision.wireName)
        put("requested_accuracy", requestedAccuracy.wireName)
        put("effective_precision", effectiveLocationPrecision(permissionPrecision, fix.accuracyM))
        put("precision_limited_by_permission", permissionPrecision == PermissionPrecision.COARSE)
        put("coordinate_system", "WGS84")
        put("timestamp_ms", fix.timestampMs)
        put("elapsed_realtime_nanos", fix.elapsedRealtimeNanos)
        cacheStatus?.let { put("cache_status", it) }
        warningCode?.let { put("warning_code", it) }
        warning?.let { put("warning", it) }
    }
    is LocationResolution.Failure -> buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", message)
        put(
            "recovery",
            recovery ?: "Retry the location request or review the app and system location settings.",
        )
    }
}

internal fun effectiveLocationPrecision(
    permissionPrecision: PermissionPrecision,
    accuracyM: Float,
): String = when {
    permissionPrecision == PermissionPrecision.COARSE -> PermissionPrecision.COARSE.wireName
    accuracyM <= 25f -> RequestedAccuracy.HIGH.wireName
    accuracyM <= 100f -> RequestedAccuracy.BALANCED.wireName
    else -> RequestedAccuracy.LOW.wireName
}
