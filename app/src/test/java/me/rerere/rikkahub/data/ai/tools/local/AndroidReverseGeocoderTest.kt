package me.rerere.rikkahub.data.ai.tools.local

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidReverseGeocoderTest {
    @Test
    fun `unavailable platform returns a stable error without querying`() = runBlocking {
        val client = FakeClient(present = false)
        val result = AndroidReverseGeocoder(client).reverse(request())

        assertFailureCode(result, "ANDROID_GEOCODER_UNAVAILABLE")
        assertEquals(0, client.queryCount)
    }

    @Test
    fun `platform permission is enforced before querying`() = runBlocking {
        val client = FakeClient()
        val result = AndroidReverseGeocoder(client).reverse(request(allowPlatform = false))

        assertFailureCode(result, "PLATFORM_GEOCODER_NOT_ALLOWED")
        assertEquals(0, client.queryCount)
    }

    @Test
    fun `empty and blank candidates return no result`() = runBlocking {
        val client = FakeClient(
            candidates = listOf(AndroidGeocodeCandidate(formattedAddress = "  ")),
        )
        val result = AndroidReverseGeocoder(client).reverse(request())

        assertFailureCode(result, "NO_GEOCODER_RESULT")
        assertEquals(3, client.lastMaxResults)
    }

    @Test
    fun `candidate scoring retains source rank and uses deterministic tie break`() = runBlocking {
        val first = AndroidGeocodeCandidate(formattedAddress = "first", city = "A")
        val second = AndroidGeocodeCandidate(formattedAddress = "second", city = "B", district = "C")
        val result = AndroidReverseGeocoder(FakeClient(candidates = listOf(first, second))).reverse(request())

        val success = result as ReverseGeocodeResolution.Success
        // The second candidate has one extra field but pays a rank penalty of two.
        assertEquals("first", success.address.formattedAddress)
    }

    @Test
    fun `platform mapping never invents POI exactness or nearest road`() = runBlocking {
        val result = AndroidReverseGeocoder(
            FakeClient(
                candidates = listOf(
                    AndroidGeocodeCandidate(
                        formattedAddress = "浙江省杭州市文三路",
                        city = "杭州市",
                        road = "文三路",
                        houseNumber = "1号",
                    ),
                ),
            ),
        ).reverse(request()) as ReverseGeocodeResolution.Success

        assertNull(result.address.poiName)
        assertFalse(result.address.isExact)
        assertEquals(AddressMatchType.APPROXIMATE, result.address.matchType)
        assertEquals(AddressDetailLevel.STREET, result.address.achievedDetail)
        assertEquals(CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN, result.address.coordinateDisclosure)
        assertFalse(result.address.explicitExternalProvider)
    }

    @Test
    fun `ordinary platform failure is redacted`() = runBlocking {
        val result = AndroidReverseGeocoder(
            FakeClient(failure = IllegalStateException("secret coordinate and backend detail")),
        ).reverse(request())

        assertFailureCode(result, "ANDROID_GEOCODER_FAILED")
        val json = result.toJson().toString()
        assertFalse(json.contains("secret coordinate"))
        assertFalse(json.contains("IllegalStateException"))
    }

    @Test
    fun `timeout is mapped before general cancellation`() = runBlocking {
        val result = AndroidReverseGeocoder(
            object : AndroidGeocoderClient {
                override fun isPresent() = true
                override suspend fun query(
                    latitude: Double,
                    longitude: Double,
                    maxResults: Int,
                    locale: Locale,
                ): List<AndroidGeocodeCandidate> = awaitCancellation()
            },
        ).reverse(request(timeoutMs = 20L))

        assertFailureCode(result, "ANDROID_GEOCODER_TIMEOUT")
    }

    @Test
    fun `caller cancellation is propagated`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val geocoder = AndroidReverseGeocoder(
            object : AndroidGeocoderClient {
                override fun isPresent() = true
                override suspend fun query(
                    latitude: Double,
                    longitude: Double,
                    maxResults: Int,
                    locale: Locale,
                ): List<AndroidGeocodeCandidate> {
                    started.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        var propagated = false
        val job = async {
            try {
                geocoder.reverse(request(timeoutMs = 15_000L))
            } catch (_: CancellationException) {
                propagated = true
                throw CancellationException()
            }
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(propagated)
    }

    @Test
    fun `single completion gate rejects late duplicate callbacks`() {
        val gate = SingleCompletionGate()
        assertTrue(gate.tryComplete())
        assertFalse(gate.tryComplete())
        assertFalse(gate.tryComplete())
    }

    private fun request(
        allowPlatform: Boolean = true,
        timeoutMs: Long = 5_000L,
    ) = ReverseGeocodeRequest(
        latitude = 30.0,
        longitude = 120.0,
        allowPlatformGeocoder = allowPlatform,
        timeoutMs = timeoutMs,
    )

    private fun assertFailureCode(result: ReverseGeocodeResolution, expectedCode: String) {
        assertTrue(result is ReverseGeocodeResolution.Failure)
        assertEquals(expectedCode, (result as ReverseGeocodeResolution.Failure).error.code)
    }

    private class FakeClient(
        private val present: Boolean = true,
        private val candidates: List<AndroidGeocodeCandidate> = emptyList(),
        private val failure: Exception? = null,
    ) : AndroidGeocoderClient {
        var queryCount = 0
        var lastMaxResults: Int? = null

        override fun isPresent(): Boolean = present

        override suspend fun query(
            latitude: Double,
            longitude: Double,
            maxResults: Int,
            locale: Locale,
        ): List<AndroidGeocodeCandidate> {
            queryCount += 1
            lastMaxResults = maxResults
            failure?.let { throw it }
            return candidates
        }
    }
}
