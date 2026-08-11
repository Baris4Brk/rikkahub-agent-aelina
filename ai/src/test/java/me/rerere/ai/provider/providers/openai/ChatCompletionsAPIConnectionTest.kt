package me.rerere.ai.provider.providers.openai

import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatCompletionsAPIConnectionTest {
    @Test
    fun `normal attempt keeps shared client`() {
        val client = OkHttpClient()

        assertSame(client, client.forProviderStreamAttempt(freshConnection = false))
    }

    @Test
    fun `watchdog retry uses isolated connection pool`() {
        val client = OkHttpClient()
        val retryClient = client.forProviderStreamAttempt(freshConnection = true)

        assertNotSame(client, retryClient)
        assertNotSame(client.connectionPool, retryClient.connectionPool)
        retryClient.connectionPool.evictAll()
    }
}
