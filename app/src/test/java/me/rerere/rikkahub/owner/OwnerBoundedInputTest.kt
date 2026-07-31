package me.rerere.rikkahub.owner

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerBoundedInputTest {
    @Test
    fun `bounded read never consumes more than its limit`() {
        val input = ByteArrayInputStream(ByteArray(32) { it.toByte() })

        val result = input.readOwnerBytesAtMost(7)

        assertArrayEquals(ByteArray(7) { it.toByte() }, result)
        assertEquals(7, input.read())
    }

    @Test
    fun `bounded read accepts a shorter stream`() {
        val source = "manifest".encodeToByteArray()
        assertArrayEquals(source, ByteArrayInputStream(source).readOwnerBytesAtMost(128))
    }
}
