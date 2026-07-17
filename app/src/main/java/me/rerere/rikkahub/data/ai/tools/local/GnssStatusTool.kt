package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val GNSS_STATUS_TOOL_NAME = "get_gnss_status"

fun gnssStatusTool(context: Context): Tool = createGnssStatusTool {
    AndroidGnssObservationSource(context)
}

internal fun locationToolBundle(context: Context): List<Tool> = listOf(
    locationTool(context),
    gnssStatusTool(context),
)

@Suppress("UNUSED_PARAMETER")
internal fun gnssStatusTool(
    context: Context,
    source: GnssObservationSource,
): Tool = createGnssStatusTool { source }

private fun createGnssStatusTool(
    sourceProvider: () -> GnssObservationSource,
): Tool = Tool(
    name = GNSS_STATUS_TOOL_NAME,
    description = "Observe Android GNSS satellite status for 5 to 10 seconds. Reports satellites visible " +
        "during the observation and satellites marked used-in-fix by Android; it does not force a constellation " +
        "connection or prove that a location was calculated exclusively from BeiDou.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("observation_window_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Observation duration from 5000 to 10000 ms. Defaults to 8000 ms.")
                })
            },
        )
    },
    execute = { input ->
        val params = input as? JsonObject
        val unknownFields = params?.keys.orEmpty() - setOf("observation_window_ms")
        val window = (params?.get("observation_window_ms") as? JsonPrimitive)?.longOrNull
            ?: DEFAULT_GNSS_OBSERVATION_WINDOW_MS
        val invalid = when {
            params == null -> gnssInvalidArgument("input must be a JSON object")
            unknownFields.isNotEmpty() -> gnssInvalidArgument(
                "unknown fields: ${unknownFields.sorted().joinToString()}",
            )
            params["observation_window_ms"] != null &&
                (params["observation_window_ms"] as? JsonPrimitive)?.longOrNull == null ->
                gnssInvalidArgument("observation_window_ms must be an integer")
            window !in MIN_GNSS_OBSERVATION_WINDOW_MS..MAX_GNSS_OBSERVATION_WINDOW_MS ->
                gnssInvalidArgument("observation_window_ms must be between 5000 and 10000")
            else -> null
        }
        val result = if (invalid != null) {
            invalid
        } else {
            try {
                sourceProvider().observe(GnssObservationRequest(window)).toJson()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                GnssObservationResult.Failure(
                    code = "GNSS_REGISTRATION_FAILED",
                    message = failure.message ?: "Unexpected GNSS observation failure.",
                    recovery = "Retry the observation or review Android location settings.",
                ).toJson()
            }
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun gnssInvalidArgument(message: String): JsonObject = GnssObservationResult.Failure(
    code = "INVALID_ARGUMENT",
    message = message,
    recovery = "Use an observation_window_ms value from 5000 to 10000.",
).toJson()

internal fun GnssObservationResult.toJson(): JsonObject = when (this) {
    is GnssObservationResult.Success -> buildJsonObject {
        put("ok", true)
        put("observation_window_ms", observationWindowMs)
        put("gnss_started", gnssStarted)
        put("first_fix_observed", firstFixObserved)
        put("satellites_visible", satellitesVisible)
        put("satellites_used_in_fix", satellitesUsedInFix)
        put("constellations", buildJsonObject {
            constellations.forEach { (name, counts) ->
                put(name, buildJsonObject {
                    put("visible", counts.visible)
                    put("used_in_fix", counts.usedInFix)
                })
            }
        })
        put("observed_at_ms", observedAtMs)
        warningCode?.let { put("warning_code", it) }
        warning?.let { put("warning", it) }
    }
    is GnssObservationResult.Failure -> buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", message)
        put("recovery", recovery)
    }
}
