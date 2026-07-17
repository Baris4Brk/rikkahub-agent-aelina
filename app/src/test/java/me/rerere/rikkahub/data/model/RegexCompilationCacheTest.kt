package me.rerere.rikkahub.data.model

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexCompilationCacheTest {
    @Test
    fun `successful compilation is reused until the ten minute ttl expires`() {
        var now = 0L
        val compilations = AtomicInteger()
        val cache = RegexCompilationCache(
            clockMillis = { now },
            compiler = { pattern ->
                compilations.incrementAndGet()
                Regex(pattern)
            },
        )

        assertTrue(cache.get("a+").isSuccess)
        assertTrue(cache.get("a+").isSuccess)
        now = REGEX_CACHE_TTL_MILLIS - 1
        assertTrue(cache.get("a+").isSuccess)
        assertEquals(1, compilations.get())

        now = REGEX_CACHE_TTL_MILLIS
        assertTrue(cache.get("a+").isSuccess)
        assertEquals(2, compilations.get())
    }

    @Test
    fun `failed compilation is cached without retrying on every message`() {
        val compilations = AtomicInteger()
        val cache = RegexCompilationCache(
            clockMillis = { 0L },
            compiler = { pattern ->
                compilations.incrementAndGet()
                Regex(pattern)
            },
        )

        repeat(3) { assertTrue(cache.get("[").isFailure) }

        assertEquals(1, compilations.get())
    }
}
