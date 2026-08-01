package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseGeocodeModelsTest {
    @Test
    fun `validator normalizes a valid language tag`() {
        val result = ReverseGeocodeRequestValidator.validate(
            ReverseGeocodeRequest(latitude = 30.0, longitude = 120.0, languageTag = "zh-cn"),
        )

        assertTrue(result is ReverseGeocodeRequestValidation.Valid)
        assertEquals("zh-CN", (result as ReverseGeocodeRequestValidation.Valid).request.languageTag)
    }

    @Test
    fun `validator rejects non-finite and out of range coordinates`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -90.01, 90.01).forEach { latitude ->
            val result = ReverseGeocodeRequestValidator.validate(
                ReverseGeocodeRequest(latitude = latitude, longitude = 120.0),
            )
            assertInvalidCode(result, "INVALID_ARGUMENT")
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -180.01, 180.01).forEach { longitude ->
            val result = ReverseGeocodeRequestValidator.validate(
                ReverseGeocodeRequest(latitude = 30.0, longitude = longitude),
            )
            assertInvalidCode(result, "INVALID_ARGUMENT")
        }
    }

    @Test
    fun `validator accepts coordinate and timeout boundaries`() {
        listOf(
            ReverseGeocodeRequest(-90.0, -180.0, timeoutMs = MIN_REVERSE_GEOCODE_TIMEOUT_MS),
            ReverseGeocodeRequest(90.0, 180.0, timeoutMs = MAX_REVERSE_GEOCODE_TIMEOUT_MS),
        ).forEach { request ->
            assertTrue(ReverseGeocodeRequestValidator.validate(request) is ReverseGeocodeRequestValidation.Valid)
        }
    }

    @Test
    fun `validator separates platform and external disclosure permissions`() {
        assertInvalidCode(
            ReverseGeocodeRequestValidator.validate(
                ReverseGeocodeRequest(30.0, 120.0, providerId = "android", allowPlatformGeocoder = false),
            ),
            "PLATFORM_GEOCODER_NOT_ALLOWED",
        )
        assertInvalidCode(
            ReverseGeocodeRequestValidator.validate(
                ReverseGeocodeRequest(30.0, 120.0, providerId = "amap-main", allowExternal = false),
            ),
            "EXTERNAL_GEOCODING_DISABLED",
        )
        assertTrue(
            ReverseGeocodeRequestValidator.validate(
                ReverseGeocodeRequest(30.0, 120.0, providerId = "amap-main", allowExternal = true),
            ) is ReverseGeocodeRequestValidation.Valid,
        )
    }

    @Test
    fun `validator rejects malformed provider and language`() {
        listOf("Amap", "-amap", "amap.main", "a".repeat(65)).forEach { provider ->
            assertInvalidCode(
                ReverseGeocodeRequestValidator.validate(ReverseGeocodeRequest(30.0, 120.0, providerId = provider)),
                "INVALID_ARGUMENT",
            )
        }
        listOf("x", " zh-CN", "zh_CN", "und").forEach { language ->
            assertInvalidCode(
                ReverseGeocodeRequestValidator.validate(ReverseGeocodeRequest(30.0, 120.0, languageTag = language)),
                "INVALID_ARGUMENT",
            )
        }
    }

    @Test
    fun `structured address normalization is bounded and does not invent fields`() {
        val normalized = address(
            formattedAddress = "  ${"x".repeat(3_000)}  ",
            city = " Hangzhou ",
            road = "   ",
            matchedDistanceM = Double.NaN,
        ).normalized()

        assertEquals(2_048, normalized.formattedAddress?.length)
        assertEquals("Hangzhou", normalized.city)
        assertNull(normalized.road)
        assertNull(normalized.poiName)
        assertNull(normalized.matchedDistanceM)
        assertTrue(normalized.hasUsableAddressField())
    }

    @Test
    fun `achieved detail follows actual fields rather than requested detail`() {
        assertEquals(AddressDetailLevel.ADMIN, inferAchievedAddressDetail(country = "CN"))
        assertEquals(AddressDetailLevel.CITY, inferAchievedAddressDetail(city = "Hangzhou"))
        assertEquals(AddressDetailLevel.DISTRICT, inferAchievedAddressDetail(district = "Xihu"))
        assertEquals(AddressDetailLevel.STREET, inferAchievedAddressDetail(road = "Wensan Rd"))
        assertEquals(AddressDetailLevel.POI, inferAchievedAddressDetail(poiName = "Library"))
    }

    @Test
    fun `success JSON is stable and explicitly discloses platform uncertainty`() {
        val json = ReverseGeocodeResolution.Success(
            address = address(city = "杭州市", road = "文三路"),
            attemptedProviders = listOf("android", "android"),
        ).toJson().toString()

        assertTrue(json.contains("\"ok\":true"))
        assertTrue(json.contains("\"coordinate_disclosure\":\"platform_geocoder_unknown\""))
        assertTrue(json.contains("\"is_exact\":false"))
        assertTrue(json.contains("\"attempted_providers\":[\"android\"]"))
        assertFalse(json.contains("coordinates_shared"))
    }

    @Test
    fun `failure JSON uses stable text without exception details`() {
        val json = ReverseGeocodeResolution.Failure(
            error = ReverseGeocodeError(
                code = "ANDROID_GEOCODER_FAILED",
                message = "The Android platform geocoder failed.",
                recovery = "Try again later.",
            ),
            attemptedProviders = listOf("android"),
        ).toJson().toString()

        assertTrue(json.contains("\"address_status\":\"failed\""))
        assertTrue(json.contains("\"code\":\"ANDROID_GEOCODER_FAILED\""))
        assertFalse(json.contains("java.lang"))
    }

    private fun address(
        formattedAddress: String? = "浙江省杭州市文三路",
        city: String? = null,
        road: String? = null,
        matchedDistanceM: Double? = null,
    ) = StructuredAddress(
        formattedAddress = formattedAddress,
        city = city,
        road = road,
        provider = "android_geocoder",
        queryCoordinateSystem = CoordinateSystem.WGS84,
        matchType = AddressMatchType.APPROXIMATE,
        achievedDetail = inferAchievedAddressDetail(city = city, road = road),
        matchedDistanceM = matchedDistanceM,
        coordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN,
        explicitExternalProvider = false,
        requestedLanguage = "zh-CN",
    )

    private fun assertInvalidCode(result: ReverseGeocodeRequestValidation, expectedCode: String) {
        assertTrue(result is ReverseGeocodeRequestValidation.Invalid)
        assertEquals(expectedCode, (result as ReverseGeocodeRequestValidation.Invalid).error.code)
    }
}
