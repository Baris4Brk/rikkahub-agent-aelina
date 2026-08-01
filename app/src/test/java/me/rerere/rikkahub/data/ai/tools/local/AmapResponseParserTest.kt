package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapResponseParserTest {
    private val request = ReverseGeocodeRequest(
        latitude = 39.9042,
        longitude = 116.4074,
        providerId = "amap_main",
        allowExternal = true,
    )

    @Test
    fun `maps documented response without inventing exactness`() {
        val result = AmapResponseParser.parse(
            providerId = "amap_main",
            queryCoordinateSystem = CoordinateSystem.GCJ02,
            request = request,
            body = """{
                "status":"1","infocode":"10000",
                "regeocode":{
                  "formatted_address":"北京市东城区东华门街道",
                  "addressComponent":{
                    "country":"中国","province":"北京市","city":[],"district":"东城区","township":"东华门街道",
                    "streetNumber":{"street":"东长安街","number":"1号","distance":"12.5"}
                  }
                }
              }""",
        ) as ReverseGeocodeResolution.Success
        assertEquals("北京市东城区东华门街道", result.address.formattedAddress)
        assertNull(result.address.city)
        assertEquals("东长安街", result.address.road)
        assertEquals(AddressMatchType.BUILDING, result.address.matchType)
        assertEquals(12.5, result.address.matchedDistanceM!!, 0.001)
        assertFalse(result.address.isExact)
        assertEquals(CoordinateDisclosure.CONFIGURED_EXTERNAL, result.address.coordinateDisclosure)
        assertEquals("高德地图", result.address.attribution)
    }

    @Test
    fun `maps authentication and rate errors without server text`() {
        val auth = AmapResponseParser.parse(
            "amap_main", CoordinateSystem.GCJ02,
            """{"status":"0","infocode":"10001","info":"secret server detail"}""", request,
        ) as ReverseGeocodeResolution.Failure
        val rate = AmapResponseParser.parse(
            "amap_main", CoordinateSystem.GCJ02,
            """{"status":"0","infocode":"10004","info":"raw quota detail"}""", request,
        ) as ReverseGeocodeResolution.Failure
        assertEquals("PROVIDER_AUTH_FAILED", auth.error.code)
        assertEquals("PROVIDER_RATE_LIMITED", rate.error.code)
        assertFalse(auth.error.message.contains("secret"))
        assertFalse(rate.error.message.contains("quota"))
    }

    @Test
    fun `rejects malformed or empty successful response`() {
        assertTrue(AmapResponseParser.parse("amap_main", CoordinateSystem.GCJ02, "not-json", request) is ReverseGeocodeResolution.Failure)
        val empty = AmapResponseParser.parse(
            "amap_main", CoordinateSystem.GCJ02,
            """{"status":"1","regeocode":{"addressComponent":{}}}""", request,
        ) as ReverseGeocodeResolution.Failure
        assertEquals("NO_GEOCODER_RESULT", empty.error.code)
    }
}
