package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssStatusToolTest {
    @Test
    fun `Location switch bundle contains location and GNSS status tools`() {
        assertEquals(
            listOf("get_location", "get_gnss_status"),
            locationToolBundle(NULL_CONTEXT).map { it.name },
        )
    }

    @Test
    fun `get_gnss_status rejects invalid duration and unknown fields before observing`() {
        var calls = 0
        val source = GnssObservationSource {
            calls++
            GnssObservationResult.Failure("unexpected", "unexpected", "unexpected")
        }

        val tooShort = execTool(
            gnssStatusTool(NULL_CONTEXT, source),
            """{"observation_window_ms":4999}""",
        )
        val unknown = execTool(
            gnssStatusTool(NULL_CONTEXT, source),
            """{"observation_window_ms":8000,"command":"gps"}""",
        )

        assertTrue(tooShort.contains("\"code\":\"INVALID_ARGUMENT\""))
        assertTrue(unknown.contains("\"code\":\"INVALID_ARGUMENT\""))
        assertEquals(0, calls)
    }

    @Test
    fun `get_gnss_status preserves structured failure and recovery`() {
        val source = GnssObservationSource {
            GnssObservationResult.Failure(
                code = "GPS_PROVIDER_DISABLED",
                message = "GPS is disabled.",
                recovery = "Open Android location settings.",
            )
        }

        val raw = execTool(gnssStatusTool(NULL_CONTEXT, source), "{}")

        assertTrue(raw.contains("\"ok\":false"))
        assertTrue(raw.contains("\"code\":\"GPS_PROVIDER_DISABLED\""))
        assertTrue(raw.contains("\"recovery\":\"Open Android location settings.\""))
    }

    @Test
    fun `get_gnss_status returns structured observation JSON`() {
        var request: GnssObservationRequest? = null
        val source = GnssObservationSource {
            request = it
            GnssObservationResult.Success(
                observationWindowMs = it.observationWindowMs,
                gnssStarted = true,
                firstFixObserved = false,
                satellitesVisible = 3,
                satellitesUsedInFix = 1,
                constellations = mapOf(
                    "beidou" to ConstellationCounts(2, 1),
                    "gps" to ConstellationCounts(1, 0),
                ),
                observedAtMs = 1_700_000_000_000L,
            )
        }

        val raw = execTool(gnssStatusTool(NULL_CONTEXT, source), """{"observation_window_ms":9000}""")
        val json = Json.parseToJsonElement(raw).jsonObject

        assertEquals(9_000L, request?.observationWindowMs)
        assertEquals(true, json["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("3", json["satellites_visible"]?.jsonPrimitive?.content)
        assertEquals("1", json["satellites_used_in_fix"]?.jsonPrimitive?.content)
        assertEquals(
            "2",
            json["constellations"]?.jsonObject
                ?.get("beidou")?.jsonObject
                ?.get("visible")?.jsonPrimitive?.content,
        )
        assertTrue(!raw.contains("forced", ignoreCase = true))
        assertTrue(!raw.contains("exclusively", ignoreCase = true))
    }
}
