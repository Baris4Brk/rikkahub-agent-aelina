package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationToolTest {

    // Successful location fix requires FusedLocationProviderClient — instrumented test required.

    @Test
    fun `get_location returns error envelope for unknown accuracy`() {
        // Unknown-accuracy validation runs before any Context call.
        val tool = locationTool(NULL_CONTEXT)
        val result = execTool(tool, """{"accuracy":"foo"}""")
        assertTrue(
            "expected unknown-accuracy error, got: $result",
            result.contains("\"ok\":false") &&
                result.contains("\"code\":\"INVALID_ARGUMENT\"") &&
                result.contains("unknown accuracy"),
        )
    }

    @Test
    fun `get_location returns structured WGS84 result from resolver`() {
        var capturedRequest: LocationRequest? = null
        val resolver = LocationResolver { request ->
            capturedRequest = request
            LocationResolution.Success(
                fix = LocationFix(
                    latitude = 30.0,
                    longitude = 120.0,
                    accuracyM = 8.5f,
                    altitudeM = 42.0,
                    speedMps = 1.5f,
                    bearingDegrees = 180f,
                    provider = LocationProviders.GPS,
                    timestampMs = 1_700_000_000_000L,
                    elapsedRealtimeNanos = 55_000_000_000L,
                ),
                source = LocationSourceKind.ANDROID_LOCATION_MANAGER,
                sourceType = LocationSourceType.GNSS,
                generatedAfterRequest = true,
                ageMs = 320L,
                cached = false,
                fresh = true,
                permissionPrecision = PermissionPrecision.FINE,
                requestedAccuracy = RequestedAccuracy.HIGH,
            )
        }

        val result = execTool(locationTool(NULL_CONTEXT, resolver), """{"accuracy":"high"}""")

        assertEquals(45_000L, capturedRequest?.timeoutMs)
        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"source\":\"android_location_manager\""))
        assertTrue(result.contains("\"source_type\":\"gnss\""))
        assertTrue(result.contains("\"fresh\":true"))
        assertTrue(result.contains("\"cached\":false"))
        assertTrue(result.contains("\"permission_precision\":\"fine\""))
        assertTrue(result.contains("\"coordinate_system\":\"WGS84\""))
        assertTrue(result.contains("\"effective_precision\":\"high\""))
        assertTrue(result.contains("\"altitude\":42.0"))
        assertTrue(result.contains("\"altitude_m\":42.0"))
        assertTrue(result.contains("\"speed\":1.5"))
        assertTrue(result.contains("\"speed_mps\":1.5"))
        assertTrue(result.contains("\"bearing\":180.0"))
        assertTrue(result.contains("\"bearing_degrees\":180.0"))
    }

    @Test
    fun `get_location passes cache controls and validates their bounds`() {
        var captured: LocationRequest? = null
        val resolver = LocationResolver { request ->
            captured = request
            LocationResolution.Failure("NATIVE_LOCATION_FAILED", "no fix")
        }

        execTool(
            locationTool(NULL_CONTEXT, resolver),
            """{
                "accuracy":"low",
                "timeout_ms":60000,
                "allow_cached":false,
                "direct_cache_max_age_ms":0,
                "fallback_cache_max_age_ms":120000
            }""".trimIndent(),
        )
        val invalid = execTool(
            locationTool(NULL_CONTEXT, resolver),
            """{"timeout_ms":4999}""",
        )

        assertEquals(RequestedAccuracy.LOW, captured?.accuracy)
        assertEquals(60_000L, captured?.timeoutMs)
        assertEquals(false, captured?.allowCached)
        assertEquals(0L, captured?.directCacheMaxAgeMs)
        assertEquals(120_000L, captured?.fallbackCacheMaxAgeMs)
        assertTrue(invalid.contains("\"code\":\"INVALID_ARGUMENT\""))
    }

    @Test
    fun `get_location returns recovery for structured location failure`() {
        val resolver = LocationResolver {
            LocationResolution.Failure(
                code = "LOCATION_SERVICES_DISABLED",
                message = "Location is off.",
                recovery = "Open location settings.",
            )
        }

        val result = execTool(locationTool(NULL_CONTEXT, resolver), "{}")

        assertTrue(result.contains("\"ok\":false"))
        assertTrue(result.contains("\"code\":\"LOCATION_SERVICES_DISABLED\""))
        assertTrue(result.contains("\"recovery\":\"Open location settings.\""))
    }

    @Test
    fun `get_location always includes recovery in failure envelopes`() {
        val resolver = LocationResolver {
            LocationResolution.Failure(
                code = "PROVIDER_TIMEOUT",
                message = "The provider timed out.",
            )
        }

        val result = execTool(locationTool(NULL_CONTEXT, resolver), "{}")

        assertTrue(result.contains("\"ok\":false"))
        assertTrue(result.contains("\"code\":\"PROVIDER_TIMEOUT\""))
        assertTrue(result.contains("\"recovery\":"))
    }

    @Test
    fun `effective precision reflects permission and measured accuracy`() {
        assertEquals("coarse", effectiveLocationPrecision(PermissionPrecision.COARSE, 8f))
        assertEquals("high", effectiveLocationPrecision(PermissionPrecision.FINE, 20f))
        assertEquals("balanced", effectiveLocationPrecision(PermissionPrecision.FINE, 75f))
        assertEquals("low", effectiveLocationPrecision(PermissionPrecision.FINE, 500f))
    }

    @Test
    fun `coarse success JSON stays usable and reports permission limitation`() {
        val resolver = LocationResolver {
            LocationResolution.Success(
                fix = LocationFix(
                    latitude = 30.0,
                    longitude = 120.0,
                    accuracyM = 500f,
                    provider = LocationProviders.NETWORK,
                    timestampMs = 1_700_000_000_000L,
                    elapsedRealtimeNanos = 55_000_000_000L,
                ),
                source = LocationSourceKind.ANDROID_LOCATION_MANAGER,
                sourceType = LocationSourceType.APPROXIMATE,
                generatedAfterRequest = true,
                ageMs = 0L,
                cached = false,
                fresh = true,
                permissionPrecision = PermissionPrecision.COARSE,
                requestedAccuracy = RequestedAccuracy.HIGH,
                warningCode = "PRECISE_LOCATION_NOT_GRANTED",
                warning = "Enable Precise location for satellite-level accuracy.",
            )
        }

        val result = execTool(locationTool(NULL_CONTEXT, resolver), """{"accuracy":"high"}""")

        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"source_type\":\"approximate\""))
        assertTrue(result.contains("\"effective_precision\":\"coarse\""))
        assertTrue(result.contains("\"precision_limited_by_permission\":true"))
        assertTrue(result.contains("\"warning_code\":\"PRECISE_LOCATION_NOT_GRANTED\""))
    }
}
