package me.rerere.rikkahub.privilege

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

object PrivilegedCommandLimits {
    const val MIN_TIMEOUT_MS = 100L
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 600_000L

    const val DEFAULT_OUTPUT_BYTES = 128 * 1024
    const val MAX_COMBINED_OUTPUT_BYTES = 512 * 1024
    const val MAX_BINDER_RESULT_BYTES = 768 * 1024

    const val MAX_STDIN_BYTES = 256 * 1024
    const val MAX_CONCURRENT_COMMANDS = 4
    const val MAX_EXECUTABLE_BYTES = 4 * 1024
    const val MAX_COMMAND_BYTES = 64 * 1024
    const val MAX_ARGUMENT_COUNT = 256
    const val MAX_ARGUMENT_BYTES = 16 * 1024
    const val MAX_COMBINED_ARGUMENT_BYTES = 128 * 1024
}

@Serializable
enum class PrivilegedCommandMode {
    @SerialName("argv")
    ARGV,

    @SerialName("shell")
    SHELL,
}

@Serializable
data class PrivilegedCommandInput(
    val mode: PrivilegedCommandMode,
    val executable: String = "",
    val arguments: List<String> = emptyList(),
    val command: String = "",
    val stdin: String = "",
    @SerialName("timeout_ms")
    val timeoutMs: Long = PrivilegedCommandLimits.DEFAULT_TIMEOUT_MS,
    @SerialName("max_output_bytes")
    val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
) {
    fun normalized(): PrivilegedCommandInput = when (mode) {
        PrivilegedCommandMode.ARGV -> copy(command = "")
        PrivilegedCommandMode.SHELL -> copy(executable = "", arguments = emptyList())
    }

    fun validate(): PrivilegedCommandValidation {
        fun reject(message: String) = PrivilegedCommandValidation(false, "INVALID_ARGUMENTS", message)
        if (timeoutMs !in PrivilegedCommandLimits.MIN_TIMEOUT_MS..PrivilegedCommandLimits.MAX_TIMEOUT_MS) {
            return reject("timeout_ms is outside the supported range.")
        }
        if (maxOutputBytes !in 1..PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES) {
            return reject("max_output_bytes is outside the supported range.")
        }
        if (stdin.utf8Size() > PrivilegedCommandLimits.MAX_STDIN_BYTES) {
            return reject("stdin exceeds the supported byte limit.")
        }
        if ('\u0000' in stdin) return reject("stdin contains a NUL character.")

        return when (mode) {
            PrivilegedCommandMode.ARGV -> {
                if (executable.isBlank()) return reject("argv mode requires executable.")
                if ('\u0000' in executable) return reject("executable contains a NUL character.")
                if (executable.utf8Size() > PrivilegedCommandLimits.MAX_EXECUTABLE_BYTES) {
                    return reject("executable exceeds the supported byte limit.")
                }
                if (arguments.size > PrivilegedCommandLimits.MAX_ARGUMENT_COUNT) {
                    return reject("arguments contains too many entries.")
                }
                if (arguments.any { '\u0000' in it }) return reject("arguments contains a NUL character.")
                if (arguments.any { it.utf8Size() > PrivilegedCommandLimits.MAX_ARGUMENT_BYTES }) {
                    return reject("an argument exceeds the supported byte limit.")
                }
                if (arguments.sumOf(String::utf8Size) > PrivilegedCommandLimits.MAX_COMBINED_ARGUMENT_BYTES) {
                    return reject("arguments exceed the combined byte limit.")
                }
                PrivilegedCommandValidation(true, "OK", "Command input is valid.")
            }

            PrivilegedCommandMode.SHELL -> {
                if (command.isBlank()) return reject("shell mode requires command.")
                if ('\u0000' in command) return reject("command contains a NUL character.")
                if (command.utf8Size() > PrivilegedCommandLimits.MAX_COMMAND_BYTES) {
                    return reject("command exceeds the supported byte limit.")
                }
                PrivilegedCommandValidation(true, "OK", "Command input is valid.")
            }
        }
    }
}

data class PrivilegedCommandValidation(
    val valid: Boolean,
    val code: String,
    val message: String,
)

@Serializable
data class PrivilegedCommandRequest(
    @SerialName("command_id")
    val commandId: String,
    val mode: PrivilegedCommandMode,
    val executable: String = "",
    val arguments: List<String> = emptyList(),
    val command: String = "",
    val stdin: String = "",
    @SerialName("timeout_ms")
    val timeoutMs: Long = PrivilegedCommandLimits.DEFAULT_TIMEOUT_MS,
    @SerialName("max_output_bytes")
    val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
) {
    constructor(commandId: String, input: PrivilegedCommandInput) : this(
        commandId = commandId,
        mode = input.mode,
        executable = input.executable,
        arguments = input.arguments,
        command = input.command,
        stdin = input.stdin,
        timeoutMs = input.timeoutMs,
        maxOutputBytes = input.maxOutputBytes,
    )

    fun toInput(): PrivilegedCommandInput = PrivilegedCommandInput(
        mode = mode,
        executable = executable,
        arguments = arguments,
        command = command,
        stdin = stdin,
        timeoutMs = timeoutMs,
        maxOutputBytes = maxOutputBytes,
    ).normalized()
}

@Serializable
data class PrivilegedCommandResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val data: PrivilegedCommandResultData? = null,
)

@Serializable
data class PrivilegedCommandResultData(
    @SerialName("command_id")
    val commandId: String,
    @SerialName("exit_code")
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("timed_out")
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    @SerialName("duration_ms")
    val durationMs: Long = 0,
    val privilege: String = "unavailable",
)

object PrivilegedCommandJson {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }
    private val toolFields = setOf(
        "mode",
        "executable",
        "arguments",
        "command",
        "stdin",
        "timeout_ms",
        "max_output_bytes",
    )

    fun decodeToolInput(element: JsonElement): PrivilegedCommandInput {
        val obj = element as? kotlinx.serialization.json.JsonObject
            ?: throw SerializationException("Tool input must be a JSON object.")
        val unknown = obj.keys - toolFields
        if (unknown.isNotEmpty()) {
            throw SerializationException("Unknown privileged command field(s): ${unknown.sorted().joinToString()}.")
        }
        return json.decodeFromJsonElement<PrivilegedCommandInput>(obj).normalized()
    }

    fun encodeRequest(request: PrivilegedCommandRequest): String = json.encodeToString(request)
    fun decodeRequest(raw: String): PrivilegedCommandRequest = json.decodeFromString(raw)
    fun encodeResult(result: PrivilegedCommandResult): String = json.encodeToString(result)
    fun decodeResult(raw: String): PrivilegedCommandResult = json.decodeFromString(raw)

    /**
     * Keeps a synchronous Binder response below the configured transport ceiling. The command
     * output budget normally makes this a no-op, but JSON escaping can expand an otherwise valid
     * stdout/stderr payload considerably.
     */
    fun encodeResultForBinder(
        result: PrivilegedCommandResult,
        maxBytes: Int = PrivilegedCommandLimits.MAX_BINDER_RESULT_BYTES,
    ): String {
        require(maxBytes > 0) { "maxBytes must be positive." }
        var current = result
        var encoded = encodeResult(current)
        while (encoded.utf8Size() > maxBytes) {
            val data = current.data ?: return encodeResult(
                PrivilegedCommandResult(
                    ok = false,
                    code = "TRANSACTION_TOO_LARGE",
                    message = "Privileged command result exceeded the Binder response limit.",
                ),
            )
            if (data.stdout.isEmpty() && data.stderr.isEmpty()) {
                return encoded
            }
            val trimStdout = data.stdout.length >= data.stderr.length
            val source = if (trimStdout) data.stdout else data.stderr
            val keep = (source.length * 3 / 4).coerceAtMost(source.length - 1)
            val shortened = source.substring(0, keep.coerceAtLeast(0))
            current = current.copy(
                data = if (trimStdout) {
                    data.copy(stdout = shortened, truncated = true)
                } else {
                    data.copy(stderr = shortened, truncated = true)
                },
            )
            encoded = encodeResult(current)
        }
        return encoded
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
