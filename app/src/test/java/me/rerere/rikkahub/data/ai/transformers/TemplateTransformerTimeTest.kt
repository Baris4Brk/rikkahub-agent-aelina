package me.rerere.rikkahub.data.ai.transformers

import java.util.Locale
import java.util.TimeZone
import kotlinx.datetime.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class TemplateTransformerTimeTest {
    private lateinit var oldLocale: Locale
    private lateinit var oldTimeZone: TimeZone

    @Before
    fun setUp() {
        oldLocale = Locale.getDefault()
        oldTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(oldLocale)
        TimeZone.setDefault(oldTimeZone)
    }

    @Test
    fun `historical message clock is stable and comes from createdAt`() {
        val oldMessage = LocalDateTime(2024, 1, 2, 3, 4, 5)
        val newerMessage = LocalDateTime(2026, 7, 8, 9, 10, 11)

        assertEquals(templateMessageClock(oldMessage), templateMessageClock(oldMessage))
        assertNotEquals(templateMessageClock(oldMessage), templateMessageClock(newerMessage))
    }
}
