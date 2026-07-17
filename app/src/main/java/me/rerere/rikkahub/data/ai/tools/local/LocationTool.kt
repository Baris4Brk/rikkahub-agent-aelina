package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

fun locationTool(context: Context): Tool = createLocationTool { AndroidLocationSource(context) }

@Suppress("UNUSED_PARAMETER")
internal fun locationTool(
    context: Context,
    resolver: LocationResolver,
): Tool = createLocationTool { resolver }

private fun createLocationTool(
    resolverProvider: () -> LocationResolver,
): Tool = Tool(
    name = "get_location",
    description = "Get the device's current WGS84 location using Android native providers first. " +
        "Works without Google Play Services and reports whether a result is fresh, cached, or precision-limited.",
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
            when {
                timeoutMs !in MIN_LOCATION_TIMEOUT_MS..MAX_LOCATION_TIMEOUT_MS ->
                    invalidArgument("timeout_ms must be between 5000 and 60000")
                directCacheMaxAgeMs < 0L ->
                    invalidArgument("direct_cache_max_age_ms must be non-negative")
                fallbackCacheMaxAgeMs < 0L ->
                    invalidArgument("fallback_cache_max_age_ms must be non-negative")
                directCacheMaxAgeMs > fallbackCacheMaxAgeMs ->
                    invalidArgument("direct_cache_max_age_ms must not exceed fallback_cache_max_age_ms")
                else -> {
                    try {
                        resolverProvider().resolve(
                            LocationRequest(
                                accuracy = accuracy,
                                timeoutMs = timeoutMs,
                                allowCached = allowCached,
                                directCacheMaxAgeMs = directCacheMaxAgeMs,
                                fallbackCacheMaxAgeMs = fallbackCacheMaxAgeMs,
                            )
                        ).toJson()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        LocationResolution.Failure(
                            code = "INTERNAL_ERROR",
                            message = failure.message ?: "Unexpected location failure.",
                            recovery = "Retry the location request.",
                        ).toJson()
                    }
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun invalidArgument(message: String): JsonObject = LocationResolution.Failure(
    code = "INVALID_ARGUMENT",
    message = message,
    recovery = "Correct the input parameters and try again.",
).toJson()

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
