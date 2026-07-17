package me.rerere.rikkahub.data.ai.tools

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * A fixed-argv local process adapter. It deliberately does not accept shell text or an
 * argument array from the model; callers construct the allowlisted argv in application code.
 */
class LocalProcessTool(
    private val command: List<String>,
    private val workingDirectory: String? = null,
    private val outputLimitBytes: Int = 256 * 1024,
    private val executionTimeout: Duration = Duration.parse("5m"),
) : StartableTool {
    init {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(command.all { it.isNotBlank() }) { "command contains a blank argument" }
        require(outputLimitBytes > 0) { "outputLimitBytes must be positive" }
    }

    override suspend fun start(
        args: JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionHandle {
        // args is intentionally ignored: this adapter only executes its fixed allowlisted argv.
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(false)
        workingDirectory?.let { processBuilder.directory(java.io.File(it)) }
        val process = processBuilder.start()
        return LocalProcessExecutionHandle(
            process = process,
            executionId = "local-${context.runId}-${Uuid.random()}",
            outputLimitBytes = outputLimitBytes,
            executionTimeout = executionTimeout,
        )
    }
}

class LocalProcessExecutionHandle(
    private val process: Process,
    override val executionId: String,
    private val outputLimitBytes: Int,
    private val executionTimeout: Duration,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ToolExecutionHandle {
    private val cancelRequested = AtomicBoolean(false)
    private val terminationConfirmed = AtomicBoolean(false)
    private val stdout = scope.async { readBounded(process.inputStream, outputLimitBytes) }
    private val stderr = scope.async { readBounded(process.errorStream, outputLimitBytes) }
    private val result = scope.async {
        val completed = withTimeoutOrNull(executionTimeout) {
            process.waitFor()
            listOf(stdout.await(), stderr.await())
        }
        if (completed == null) {
            requestCancel(ToolCancelReason.TIMEOUT)
            awaitTermination(2_000.milliseconds)
            ProcessOutput(exitCode = null, stdout = stdout.await(), stderr = stderr.await(), timedOut = true)
        } else {
            ProcessOutput(
                exitCode = process.exitValue(),
                stdout = completed[0],
                stderr = completed[1],
                timedOut = false,
            )
        }
    }

    override suspend fun awaitResult(): ToolResult {
        val output = result.await()
        val status = when {
            output.timedOut -> "timeout"
            cancelRequested.get() && terminationConfirmed.get() -> "cancelled"
            output.exitCode == 0 -> "completed"
            else -> "failed"
        }
        val payload = buildString {
            append('{')
            append("\"status\":\"").append(status).append("\",")
            append("\"executionId\":\"").append(executionId).append("\",")
            output.exitCode?.let { append("\"exitCode\":").append(it).append(',') }
            append("\"stdout\":").append(jsonString(output.stdout)).append(',')
            append("\"stderr\":").append(jsonString(output.stderr))
            append('}')
        }
        return listOf(UIMessagePart.Text(payload))
    }

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        return try {
            // Process.destroy() is the portable TERM-equivalent. awaitTermination performs the
            // forced follow-up and verifies the process has actually exited.
            process.destroy()
            CancelRequestResult.Requested
        } catch (t: Throwable) {
            CancelRequestResult.Failed(t.message ?: reason.message)
        }
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        if (!process.isAlive) {
            terminationConfirmed.set(true)
            return ToolTerminationState.StoppedConfirmed
        }
        val exited = withTimeoutOrNull(gracePeriod) {
            process.waitFor()
            true
        } ?: false
        if (exited || !process.isAlive) {
            terminationConfirmed.set(true)
            return ToolTerminationState.StoppedConfirmed
        }

        return try {
            process.destroyForcibly()
            val forceExited = withTimeoutOrNull(gracePeriod) {
                process.waitFor()
                true
            } ?: false
            if (forceExited || !process.isAlive) {
                terminationConfirmed.set(true)
                ToolTerminationState.StoppedConfirmed
            } else {
                ToolTerminationState.StillRunning
            }
        } catch (_: Throwable) {
            ToolTerminationState.Unknown
        }
    }

    private data class ProcessOutput(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )

    private companion object {
        suspend fun readBounded(input: InputStream, limit: Int): String = withContext(Dispatchers.IO) {
            input.use { stream ->
                val buffer = ByteArray(8 * 1024)
                val output = java.io.ByteArrayOutputStream(minOf(limit, 8 * 1024))
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val remaining = limit - total
                    if (remaining > 0) {
                        output.write(buffer, 0, minOf(count, remaining))
                        total += minOf(count, remaining)
                    }
                    if (total >= limit) break
                }
                output.toString(Charsets.UTF_8.name())
            }
        }

        fun jsonString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) {
                        append("\\u%04x".format(char.code))
                    } else {
                        append(char)
                    }
                }
            }
            append('"')
        }
    }
}
