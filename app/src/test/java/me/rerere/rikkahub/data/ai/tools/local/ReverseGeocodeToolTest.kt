package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseGeocodeToolTest {
    @Test
    fun `tool strictly rejects unknown fields before calling coordinator`() {
        var calls = 0
        val tool = reverseGeocodeTool {
            calls += 1
            failure()
        }

        val result = execTool(tool, """{"latitude":30,"longitude":120,"command":"leak"}""")

        assertTrue(result.contains("\"code\":\"INVALID_ARGUMENT\""))
        assertTrue(result.contains("unknown fields"))
        assertEquals(0, calls)
    }

    @Test
    fun `tool rejects string coordinates non finite values and invalid boundaries`() {
        val tool = reverseGeocodeTool { failure() }

        listOf(
            """{"latitude":"30","longitude":120}""",
            """{"latitude":91,"longitude":120}""",
            """{"latitude":30,"longitude":-181}""",
            """{"latitude":30,"longitude":120,"timeout_ms":999}""",
        ).forEach { input ->
            val result = execTool(tool, input)
            assertTrue("expected invalid result for $input: $result", result.contains("\"code\":\"INVALID_ARGUMENT\""))
        }
    }

    @Test
    fun `tool applies defaults and passes a normalized request`() {
        var captured: ReverseGeocodeRequest? = null
        val tool = reverseGeocodeTool { request ->
            captured = request
            success()
        }

        val result = execTool(tool, """{"latitude":30,"longitude":120,"language":"zh-cn"}""")

        assertTrue(result.contains("\"ok\":true"))
        assertEquals("zh-CN", captured?.languageTag)
        assertEquals("auto", captured?.providerId)
        assertEquals(AddressDetailLevel.STREET, captured?.detailLevel)
        assertEquals(true, captured?.allowPlatformGeocoder)
        assertEquals(false, captured?.allowExternal)
        assertEquals(5_000L, captured?.timeoutMs)
    }

    @Test
    fun `explicit external provider requires per-call disclosure permission`() {
        var calls = 0
        val tool = reverseGeocodeTool {
            calls += 1
            failure()
        }

        val denied = execTool(tool, """{"latitude":30,"longitude":120,"provider":"amap-main"}""")
        val allowed = execTool(
            tool,
            """{"latitude":30,"longitude":120,"provider":"amap-main","allow_external":true}""",
        )

        assertTrue(denied.contains("\"code\":\"EXTERNAL_GEOCODING_DISABLED\""))
        assertTrue(allowed.contains("\"code\":\"NO_GEOCODER_RESULT\""))
        assertEquals(1, calls)
    }

    @Test
    fun `unexpected coordinator error is redacted`() {
        val tool = reverseGeocodeTool { throw IllegalStateException("key=secret at 30,120") }

        val result = execTool(tool, """{"latitude":30,"longitude":120}""")

        assertTrue(result.contains("\"code\":\"ANDROID_GEOCODER_FAILED\""))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("30,120"))
    }

    @Test(expected = CancellationException::class)
    fun `caller cancellation is not converted to a tool error`() {
        execTool(
            reverseGeocodeTool { throw CancellationException("stop") },
            """{"latitude":30,"longitude":120}""",
        )
    }

    private fun success() = ReverseGeocodeResolution.Success(
        address = StructuredAddress(
            formattedAddress = "Hangzhou",
            provider = "android_geocoder",
            queryCoordinateSystem = CoordinateSystem.WGS84,
            achievedDetail = AddressDetailLevel.CITY,
            coordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN,
            explicitExternalProvider = false,
        ),
        attemptedProviders = listOf("android"),
    )

    private fun failure() = ReverseGeocodeResolution.Failure(
        ReverseGeocodeError("NO_GEOCODER_RESULT", "No result."),
    )
}
