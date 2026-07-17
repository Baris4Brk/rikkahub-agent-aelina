package me.rerere.rikkahub.privilege

import java.io.ByteArrayInputStream
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCommandOutputCollectorTest {
    @Test
    fun `stdout and stderr share one byte budget while both streams are drained`() {
        val stdoutInput = TrackingInputStream("a".repeat(4_096).toByteArray())
        val stderrInput = TrackingInputStream("b".repeat(4_096).toByteArray())
        val collector = PrivilegedCommandOutputCollector(maxBytes = 1_024)

        val first = thread {
            collector.drain(stdoutInput, PrivilegedCommandOutputCollector.Destination.Stdout)
        }
        val second = thread {
            collector.drain(stderrInput, PrivilegedCommandOutputCollector.Destination.Stderr)
        }
        first.join()
        second.join()

        val snapshot = collector.snapshot()
        assertEquals(1_024, snapshot.retainedBytes)
        assertTrue(snapshot.truncated)
        assertTrue(stdoutInput.reachedEnd)
        assertTrue(stderrInput.reachedEnd)
    }

    @Test
    fun `binder encoder trims heavily escaped output below transport ceiling`() {
        val result = PrivilegedCommandResult(
            ok = true,
            code = "OK",
            message = "Command completed.",
            data = PrivilegedCommandResultData(
                commandId = "9e03bab6-323e-42b3-a17a-53e8d04f56c9",
                stdout = "\\\"".repeat(50_000),
                stderr = "\\\"".repeat(50_000),
            ),
        )

        val encoded = PrivilegedCommandJson.encodeResultForBinder(result, maxBytes = 32 * 1024)
        val decoded = PrivilegedCommandJson.decodeResult(encoded)

        assertTrue(encoded.toByteArray().size <= 32 * 1024)
        assertTrue(decoded.data?.truncated == true)
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        @Volatile
        var reachedEnd: Boolean = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = super.read(b, off, len)
            if (result < 0) reachedEnd = true
            return result
        }
    }
}
