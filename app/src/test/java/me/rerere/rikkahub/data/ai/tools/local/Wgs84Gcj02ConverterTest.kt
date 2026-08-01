package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Wgs84Gcj02ConverterTest {
    @Test
    fun `converts mainland coordinate only when configured`() {
        val converted = Wgs84Gcj02Converter.convert(39.9042, 116.4074)
        assertTrue(converted.latitude in 39.905..39.907)
        assertTrue(converted.longitude in 116.413..116.415)
    }

    @Test
    fun `leaves coordinates outside mainland bounds unchanged`() {
        val converted = Wgs84Gcj02Converter.convert(51.5074, -0.1278)
        assertEquals(51.5074, converted.latitude, 0.0)
        assertEquals(-0.1278, converted.longitude, 0.0)
    }
}
