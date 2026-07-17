package me.rerere.workspace

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

class WorkspaceProcessLogPumps(
    stdout: InputStream,
    stderr: InputStream,
    stdoutFile: File,
    stderrFile: File,
    maxFileBytes: Long = MAX_WORKSPACE_PROCESS_LOG_FILE_BYTES,
) : Closeable {
    private val stdoutPump = StreamPump(stdout, RotatingLogWriter(stdoutFile, maxFileBytes), "workspace-stdout")
    private val stderrPump = StreamPump(stderr, RotatingLogWriter(stderrFile, maxFileBytes), "workspace-stderr")

    fun awaitClosed(timeoutMillis: Long = 2_000L) {
        stdoutPump.join(timeoutMillis)
        stderrPump.join(timeoutMillis)
    }

    override fun close() {
        stdoutPump.close()
        stderrPump.close()
    }
}

internal class RotatingLogWriter(
    private val file: File,
    private val maxFileBytes: Long,
) : Closeable {
    private var output: FileOutputStream
    private var currentBytes: Long

    init {
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        file.parentFile?.mkdirs()
        if (file.length() >= maxFileBytes) rotate()
        output = FileOutputStream(file, true)
        currentBytes = file.length()
    }

    @Synchronized
    fun write(bytes: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        var remaining = length
        while (remaining > 0) {
            if (currentBytes >= maxFileBytes) rotateOpenStream()
            val chunk = minOf(remaining.toLong(), maxFileBytes - currentBytes).toInt()
            output.write(bytes, cursor, chunk)
            output.flush()
            currentBytes += chunk
            cursor += chunk
            remaining -= chunk
        }
    }

    @Synchronized
    override fun close() {
        output.close()
    }

    private fun rotateOpenStream() {
        output.close()
        rotate()
        output = FileOutputStream(file, false)
        currentBytes = 0L
    }

    private fun rotate() {
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.exists()) backup.delete()
        if (file.exists() && !file.renameTo(backup)) {
            file.copyTo(backup, overwrite = true)
            file.delete()
        }
    }
}

private class StreamPump(
    private val input: InputStream,
    private val writer: RotatingLogWriter,
    name: String,
) : Closeable {
    private val worker = thread(start = true, isDaemon = true, name = name) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var logWritable = true
        try {
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read > 0 && logWritable) {
                        try {
                            writer.write(buffer, 0, read)
                        } catch (_: Exception) {
                            // Keep draining even when storage is full or a log file becomes unavailable.
                            logWritable = false
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Process termination closes the streams. Preserve everything already written.
        } finally {
            runCatching { writer.close() }
        }
    }

    fun join(timeoutMillis: Long) = worker.join(timeoutMillis)

    override fun close() {
        runCatching { input.close() }
        worker.join(1_000L)
    }
}

fun readWorkspaceProcessLogs(
    stdoutFile: File,
    stderrFile: File,
    stream: WorkspaceProcessLogStream,
    requestedTailBytes: Int,
): WorkspaceProcessLogs {
    val totalBudget = requestedTailBytes.coerceIn(1, MAX_WORKSPACE_PROCESS_LOG_TAIL_BYTES)
    val stdoutBudget = when (stream) {
        WorkspaceProcessLogStream.STDOUT -> totalBudget
        WorkspaceProcessLogStream.STDERR -> 0
        WorkspaceProcessLogStream.BOTH -> totalBudget / 2
    }
    val stderrBudget = when (stream) {
        WorkspaceProcessLogStream.STDOUT -> 0
        WorkspaceProcessLogStream.STDERR -> totalBudget
        WorkspaceProcessLogStream.BOTH -> totalBudget - stdoutBudget
    }
    val stdout = readRotatedTail(stdoutFile, stdoutBudget)
    val stderr = readRotatedTail(stderrFile, stderrBudget)
    return WorkspaceProcessLogs(
        stdout = stdout.text,
        stderr = stderr.text,
        truncated = stdout.truncated || stderr.truncated,
    )
}

private data class LogTail(val text: String, val truncated: Boolean)

private fun readRotatedTail(file: File, maxBytes: Int): LogTail {
    if (maxBytes <= 0) return LogTail("", false)
    val backup = File(file.parentFile, "${file.name}.1")
    val currentBytes = file.readAvailableBytes()
    val backupBytes = backup.readAvailableBytes()
    val totalLength = backupBytes.size.toLong() + currentBytes.size
    val currentTake = minOf(maxBytes, currentBytes.size)
    val backupTake = minOf(maxBytes - currentTake, backupBytes.size)
    val result = ByteArray(backupTake + currentTake)
    if (backupTake > 0) {
        backupBytes.copyInto(result, 0, backupBytes.size - backupTake, backupBytes.size)
    }
    if (currentTake > 0) {
        currentBytes.copyInto(result, backupTake, currentBytes.size - currentTake, currentBytes.size)
    }
    return LogTail(
        text = result.toString(Charsets.UTF_8),
        truncated = totalLength > result.size,
    )
}

private fun File.readAvailableBytes(): ByteArray = try {
    if (isFile) readBytes() else byteArrayOf()
} catch (_: Exception) {
    // Rotation can briefly rename a file while a tail request is reading it.
    byteArrayOf()
}
