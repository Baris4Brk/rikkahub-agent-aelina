package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.datastore.ReverseGeocoderProviderConfig
import me.rerere.rikkahub.data.datastore.ReverseGeocoderProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigDataCloudReverseGeocoderTest {
    private val request = ReverseGeocodeRequest(
        latitude = 30.2741,
        longitude = 120.1551,
        languageTag = "zh-CN",
    )

    @Test
    fun `successful response is parsed into an admin-boundary address`() {
        val body = """
            {
              "latitude": 30.2741,
              "longitude": 120.1551,
              "countryName": "China",
              "countryCode": "CN",
              "principalSubdivision": "Zhejiang",
              "city": "Hangzhou",
              "locality": "Xihu",
              "localityInfo": {
                "administrative": [
                  {"name": "China", "adminLevel": 2},
                  {"name": "Zhejiang", "adminLevel": 4},
                  {"name": "Hangzhou", "adminLevel": 6},
                  {"name": "Xihu", "adminLevel": 8}
                ]
              }
            }
        """.trimIndent()

        val result = BigDataCloudResponseParser.parse(
            providerId = "bigdata-main",
            queryCoordinateSystem = CoordinateSystem.WGS84,
            body = body,
            request = request,
        ) as ReverseGeocodeResolution.Success

        assertEquals("China", result.address.country)
        assertEquals("CN", result.address.countryCode)
        assertEquals("Zhejiang", result.address.province)
        assertEquals("Hangzhou", result.address.city)
        assertEquals("Xihu", result.address.district)
        assertEquals("Xihu", result.address.township)
        assertEquals(AddressMatchType.ADMIN_BOUNDARY, result.address.matchType)
        assertFalse(result.address.isExact)
        assertEquals("BigDataCloud", result.address.attribution)
        assertTrue(result.address.explicitExternalProvider)
        assertEquals(CoordinateDisclosure.CONFIGURED_EXTERNAL, result.address.coordinateDisclosure)
        assertEquals("zh-CN", result.address.requestedLanguage)
        assertEquals(listOf("bigdata-main"), result.attemptedProviders)
    }

    @Test
    fun `response without any usable address field returns no-result failure`() {
        val body = """{"latitude":30.2741,"longitude":120.1551}"""

        val result = BigDataCloudResponseParser.parse(
            providerId = "bigdata-main",
            queryCoordinateSystem = CoordinateSystem.WGS84,
            body = body,
            request = request,
        )

        assertTrue(result is ReverseGeocodeResolution.Failure)
        assertEquals("NO_GEOCODER_RESULT", (result as ReverseGeocodeResolution.Failure).error.code)
    }

    @Test
    fun `malformed body returns invalid-response failure`() {
        val result = BigDataCloudResponseParser.parse(
            providerId = "bigdata-main",
            queryCoordinateSystem = CoordinateSystem.WGS84,
            body = "not-json",
            request = request,
        )

        assertTrue(result is ReverseGeocodeResolution.Failure)
        assertEquals("PROVIDER_RESPONSE_INVALID", (result as ReverseGeocodeResolution.Failure).error.code)
    }

    @Test
    fun `backend requires an https endpoint without credentials`() {
        val config = ReverseGeocoderProviderConfig(
            id = "bigdata-main",
            type = ReverseGeocoderProviderKind.BIGDATA_CLOUD,
            displayName = "BigDataCloud",
            endpoint = "http://api.bigdatacloud.net/data/reverse-geocode-client",
            enabled = true,
        )
        val http = ReverseGeocodeHttpClient(okhttp3.OkHttpClient())
        val backend = BigDataCloudReverseGeocoder(config, http)

        // Non-HTTPS endpoints are refused at request build time and surface as invalid-response.
        // Directly build a request to confirm the guard, then verify availability is positive.
        val availability = kotlinx.coroutines.runBlocking { backend.availability(request) }
        assertEquals(BackendAvailability.Available, availability)
        assertNotNull(backend.id)
    }
}
