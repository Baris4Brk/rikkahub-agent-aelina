package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `default location path neither calls address coordinator nor changes legacy JSON`() {
        var addressCalls = 0
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver { locationSuccess() },
            ReverseGeocodeCoordinator {
                addressCalls += 1
                reverseFailure()
            },
        )

        val result = execTool(tool, """{"future_legacy_field":"kept-compatible"}""")

        assertTrue(result.contains("\"ok\":true"))
        assertFalse(result.contains("address_status"))
        assertEquals(0, addressCalls)
    }

    @Test
    fun `optional address uses the same WGS84 fix without overwriting location fields`() {
        var captured: ReverseGeocodeRequest? = null
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver { locationSuccess() },
            ReverseGeocodeCoordinator { request ->
                captured = request
                ReverseGeocodeResolution.Success(
                    address = StructuredAddress(
                        formattedAddress = "Zhejiang Hangzhou",
                        city = "Hangzhou",
                        road = "Wensan Road",
                        provider = "android_geocoder",
                        queryCoordinateSystem = CoordinateSystem.WGS84,
                        matchType = AddressMatchType.APPROXIMATE,
                        achievedDetail = AddressDetailLevel.STREET,
                        isExact = false,
                        coordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN,
                        explicitExternalProvider = false,
                    ),
                    cached = true,
                    cacheAgeMs = 250L,
                    attemptedProviders = listOf("android"),
                )
            },
        )

        val result = execTool(
            tool,
            """{
                "include_address":true,
                "address_detail":"street",
                "address_language":"en-us",
                "address_timeout_ms":15000
            }""".trimIndent(),
        )

        assertEquals(30.0, captured?.latitude ?: Double.NaN, 0.0)
        assertEquals(120.0, captured?.longitude ?: Double.NaN, 0.0)
        assertEquals("en-US", captured?.languageTag)
        assertTrue(result.contains("\"latitude\":30.0"))
        assertTrue(result.contains("\"longitude\":120.0"))
        assertTrue(result.contains("\"coordinate_system\":\"WGS84\""))
        assertTrue(result.contains("\"address_status\":\"resolved\""))
        assertTrue(result.contains("\"formatted_address\":\"Zhejiang Hangzhou\""))
        assertTrue(result.contains("\"address_cached\":true"))
        assertTrue(result.contains("\"address_cache_age_ms\":250"))
    }

    @Test
    fun `location failure never calls reverse geocoding`() {
        var addressCalls = 0
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver { LocationResolution.Failure("LOCATION_SERVICES_DISABLED", "Location is off.") },
            ReverseGeocodeCoordinator {
                addressCalls += 1
                reverseFailure()
            },
        )

        val result = execTool(tool, """{"include_address":true}""")

        assertTrue(result.contains("\"code\":\"LOCATION_SERVICES_DISABLED\""))
        assertEquals(0, addressCalls)
    }

    @Test
    fun `best effort address failure keeps successful coordinates`() {
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver { locationSuccess() },
            ReverseGeocodeCoordinator { reverseFailure() },
        )

        val result = execTool(tool, """{"include_address":true,"address_mode":"best_effort"}""")

        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"latitude\":30.0"))
        assertTrue(result.contains("\"address_status\":\"failed\""))
        assertTrue(result.contains("\"code\":\"NO_GEOCODER_RESULT\""))
        assertFalse(result.contains("\"partial\":true"))
    }

    @Test
    fun `required address failure reports partial while preserving the fix`() {
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver { locationSuccess() },
            ReverseGeocodeCoordinator { reverseFailure() },
        )

        val result = execTool(tool, """{"include_address":true,"address_mode":"required"}""")

        assertTrue(result.contains("\"ok\":false"))
        assertTrue(result.contains("\"partial\":true"))
        assertTrue(result.contains("\"location_ok\":true"))
        assertTrue(result.contains("\"latitude\":30.0"))
        assertTrue(result.contains("\"address_status\":\"failed\""))
    }

    @Test
    fun `new address arguments fail before device location is read`() {
        var locationCalls = 0
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver {
                locationCalls += 1
                locationSuccess()
            },
            ReverseGeocodeCoordinator { reverseFailure() },
        )
        val invalidInputs = listOf(
            """{"include_address":"true"}""",
            """{"address_mode":"strict"}""",
            """{"address_detail":"building"}""",
            """{"address_provider":"Amap"}""",
            """{"address_language":"zh_CN"}""",
            """{"address_timeout_ms":999}""",
            """{"include_address":true,"address_provider":"amap","allow_external_address":false}""",
        )

        invalidInputs.forEach { input ->
            val result = execTool(tool, input)
            assertTrue("expected an input failure for $input: $result", result.contains("\"ok\":false"))
        }
        assertEquals(0, locationCalls)
    }

    @Test
    fun `address coordinator exceptions are redacted without losing the location`() {
        val tool = locationTool(
            NULL_CONTEXT,
            LocationResolver { locationSuccess() },
            ReverseGeocodeCoordinator { throw IllegalStateException("secret at 30,120") },
        )

        val result = execTool(tool, """{"include_address":true}""")

        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"latitude\":30.0"))
        assertTrue(result.contains("\"code\":\"ANDROID_GEOCODER_FAILED\""))
        assertFalse(result.contains("secret"))
    }

    @Test(expected = CancellationException::class)
    fun `address cancellation propagates instead of becoming a partial response`() {
        execTool(
            locationTool(
                NULL_CONTEXT,
                LocationResolver { locationSuccess() },
                ReverseGeocodeCoordinator { throw CancellationException("stop") },
            ),
            """{"include_address":true}""",
        )
    }

    private fun locationSuccess() = LocationResolution.Success(
        fix = LocationFix(
            latitude = 30.0,
            longitude = 120.0,
            accuracyM = 8.5f,
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
        requestedAccuracy = RequestedAccuracy.BALANCED,
    )

    private fun reverseFailure() = ReverseGeocodeResolution.Failure(
        error = ReverseGeocodeError(
            code = "NO_GEOCODER_RESULT",
            message = "No permitted reverse-geocoding backend returned a usable address.",
        ),
        attemptedProviders = listOf("android"),
    )
}
