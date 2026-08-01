package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.local.CoordinateSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseGeocodingSettingsTest {
    @Test
    fun `defaults keep external coordinate disclosure disabled`() {
        val settings = ReverseGeocodingSettings()
        assertTrue(settings.enabled)
        assertFalse(settings.externalEnabled)
        assertEquals("auto", settings.defaultProviderId)
        assertTrue(settings.providers.isEmpty())
    }

    @Test
    fun `endpoint rejects non https credentials and fragments`() {
        assertFalse(isSafeReverseGeocoderEndpoint("http://example.com/reverse"))
        assertFalse(isSafeReverseGeocoderEndpoint("https://user:pass@example.com/reverse"))
        assertFalse(isSafeReverseGeocoderEndpoint("https://example.com/reverse#fragment"))
        assertTrue(isSafeReverseGeocoderEndpoint("https://example.com/reverse?version=1"))
        assertFalse(isSafeReverseGeocoderEndpoint("https://example.com/reverse?key=must-not-persist"))
        assertFalse(isSafeReverseGeocoderEndpoint("https://example.com/reverse?%61pi_key=must-not-persist"))
    }

    @Test
    fun `normalization removes invalid providers and repairs default`() {
        val good = ReverseGeocoderProviderConfig(
            id = "  MAP_ONE ",
            type = ReverseGeocoderProviderKind.AMAP,
            displayName = " Map ",
            endpoint = "https://example.com/reverse",
            priority = 20_000,
            queryCoordinateSystem = CoordinateSystem.GCJ02,
        )
        assertNull(good.copy(endpoint = "file:///tmp/key").normalizedOrNull())
        val normalized = ReverseGeocodingSettings(
            defaultProviderId = "missing",
            providers = listOf(good, good.copy(displayName = "duplicate")),
        ).normalized()
        assertEquals("auto", normalized.defaultProviderId)
        assertEquals(1, normalized.providers.size)
        assertEquals("map_one", normalized.providers.single().id)
        assertEquals(10_000, normalized.providers.single().priority)
    }
}
