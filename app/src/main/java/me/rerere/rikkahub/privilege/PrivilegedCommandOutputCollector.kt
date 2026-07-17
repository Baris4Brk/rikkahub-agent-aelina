package me.rerere.rikkahub.privilege

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Shared stdout/stderr byte budget that continues draining both streams after truncation. */
internal class PrivilegedCommandOutputCollector(
    private val maxBytes: Int,
) {
    private val lock = Any()
    private var remaining = maxBytes
    private var wasTruncated = false
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()

    init {
        require(maxBytes > 0)
    }

    fun drain(input: InputStream, destination: Destination) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                synchronized(lock) {
                    val kept = read.coerceAtMost(remaining)
                    if (kept > 0) {
                        target(destination).write(buffer, 0, kept)
                        remaining -= kept
                    }
                    if (kept < read) wasTruncated = true
                }
            }
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            stdout = stdout.toString(Charsets.UTF_8.name()),
            stderr = stderr.toString(Charsets.UTF_8.name()),
            truncated = wasTruncated,
            retainedBytes = maxBytes - remaining,
        )
    }

    private fun target(destination: Destination): ByteArrayOutputStream = when (destination) {
        Destination.Stdout -> stdout
        Destination.Stderr -> stderr
    }

    enum class Destination { Stdout, Stderr }

    data class Snapshot(
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val retainedBytes: Int,
    )
}
