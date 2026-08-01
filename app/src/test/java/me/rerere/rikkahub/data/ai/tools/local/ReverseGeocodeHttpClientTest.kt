package me.rerere.rikkahub.data.ai.tools.local

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReverseGeocodeHttpClientTest {
    @Test
    fun `dedicated client removes interceptors redirects and retries`() {
        val interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
        val base = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addNetworkInterceptor(interceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val client = ReverseGeocodeHttpClient(base).client
        assertTrueEmpty(client.interceptors)
        assertTrueEmpty(client.networkInterceptors)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
        assertEquals(10_000, client.writeTimeoutMillis)
    }

    private fun assertTrueEmpty(value: List<*>) = assertEquals(0, value.size)
}
