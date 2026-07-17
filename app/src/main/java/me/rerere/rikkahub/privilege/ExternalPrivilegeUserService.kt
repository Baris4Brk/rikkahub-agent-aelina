package me.rerere.rikkahub.privilege

import android.content.Context
import android.os.Parcel
import android.os.Process as AndroidProcess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeActionResult
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgePrivilege
import me.rerere.rikkahub.data.ai.tools.local.ProtectedPackagePolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.system.exitProcess

/**
 * Shizuku/Sui UserService. Fixed operations retain their original typed protection while the
 * generic command entry point validates its transport limits and minimum self-preservation policy
 * again inside the privileged process.
 */
class ExternalPrivilegeUserService() : IExternalPrivilegeBridgeService.Stub() {
    private var applicationPackageName: String = BuildConfig.APPLICATION_ID
    private val commandPermits = Semaphore(PrivilegedCommandLimits.MAX_CONCURRENT_COMMANDS, true)
    private val runningCommands = ConcurrentHashMap<String, RunningCommand>()
    private val cancellationBeforeStart = ConcurrentHashMap.newKeySet<String>()
    private val streamExecutor = Executors.newFixedThreadPool(
        PrivilegedCommandLimits.MAX_CONCURRENT_COMMANDS * STREAM_TASKS_PER_COMMAND,
    ) { runnable ->
        Thread(runnable, "privileged-command-stream").apply { isDaemon = true }
    }

    constructor(context: Context) : this() {
        applicationPackageName = context.packageName
    }

    override fun listPackages(userId: Int): String {
        invalidUser(userId)?.let { return encode(it) }
        return encode(
            runFixedCommand(
                listOf("/system/bin/pm", "list", "packages", "--user", userId.toString()),
                LIST_TIMEOUT_MS,
            ),
        )
    }

    override fun forceStopApp(
        packageName: String,
        userId: Int,
        protectedPackages: Array<out String>,
    ): String = mutate(
        packageName = packageName,
        userId = userId,
        protectedPackages = protectedPackages,
        command = listOf(
            "/system/bin/am", "force-stop", "--user", userId.toString(), packageName,
        ),
        successMessage = "App force-stopped.",
    )

    override fun clearAppCache(
        packageName: String,
        userId: Int,
        protectedPackages: Array<out String>,
    ): String = mutate(
        packageName = packageName,
        userId = userId,
        protectedPackages = protectedPackages,
        command = listOf(
            "/system/bin/pm", "clear", "--user", userId.toString(), "--cache-only", packageName,
        ),
        successMessage = "App cache cleared.",
    )

    override fun runCommand(requestJson: String): String {
        val request = try {
            PrivilegedCommandJson.decodeRequest(requestJson)
        } catch (error: SerializationException) {
            return encodeCommandResult(failure("INVALID_ARGUMENTS", "Invalid command request JSON."))
        } catch (error: IllegalArgumentException) {
            return encodeCommandResult(failure("INVALID_ARGUMENTS", "Invalid command request."))
        }
        if (!COMMAND_ID_PATTERN.matches(request.commandId)) {
            return encodeCommandResult(failure("INVALID_ARGUMENTS", "Invalid internal command id."))
        }

        val input = request.toInput()
        val validation = input.validate()
        if (!validation.valid) {
            return encodeCommandResult(
                commandFailure(request.commandId, validation.code, validation.message),
            )
        }
        PrivilegedOperationPolicy(applicationPackageName)
            .check(input, currentPrivilege())
            ?.let { blocked ->
                return encodeCommandResult(
                    commandFailure(request.commandId, blocked.code, blocked.message),
                )
            }
        if (!commandPermits.tryAcquire()) {
            return encodeCommandResult(
                commandFailure(
                    request.commandId,
                    "TOO_MANY_COMMANDS",
                    "The privileged command concurrency limit has been reached.",
                ),
            )
        }

        val startedNanos = System.nanoTime()
        var running: RunningCommand? = null
        try {
            if (cancellationBeforeStart.remove(request.commandId)) {
                return encodeCommandResult(
                    cancelledResult(request.commandId, startedNanos, terminationConfirmed = true),
                )
            }
            val launch = buildLaunch(input)
            val process = try {
                ProcessBuilder(launch.command)
                    .redirectErrorStream(false)
                    .start()
            } catch (error: Exception) {
                return encodeCommandResult(
                    commandFailure(
                        request.commandId,
                        "PROCESS_START_FAILED",
                        error.message ?: "Command failed to start.",
                        startedNanos,
                    ),
                )
            }
            running = RunningCommand(
                commandId = request.commandId,
                input = input,
                process = process,
                processGroupId = if (launch.processGroup) processPid(process) else null,
            )
            if (runningCommands.putIfAbsent(request.commandId, running) != null) {
                running.forceTerminate()
                return encodeCommandResult(
                    commandFailure(
                        request.commandId,
                        "INVALID_ARGUMENTS",
                        "The internal command id is already running.",
                        startedNanos,
                    ),
                )
            }
            if (cancellationBeforeStart.remove(request.commandId)) {
                running.requestCancel()
            }

            val output = PrivilegedCommandOutputCollector(input.maxOutputBytes)
            val stdoutFuture = streamExecutor.submit {
                output.drain(
                    process.inputStream,
                    PrivilegedCommandOutputCollector.Destination.Stdout,
                )
            }
            val stderrFuture = streamExecutor.submit {
                output.drain(
                    process.errorStream,
                    PrivilegedCommandOutputCollector.Destination.Stderr,
                )
            }
            val stdinFuture = streamExecutor.submit {
                runCatching {
                    process.outputStream.use { stream ->
                        if (input.stdin.isNotEmpty()) {
                            stream.write(input.stdin.toByteArray(Charsets.UTF_8))
                            stream.flush()
                        }
                    }
                }
            }

            val finished = try {
                process.waitFor(input.timeoutMs, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                running.requestCancel()
                false
            }
            if (!finished && !running.cancellationRequested.get()) {
                running.timedOut.set(true)
                running.terminationConfirmed.set(running.terminate())
            }
            if (process.isAlive) process.waitFor(TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)
            awaitStream(stdoutFuture)
            awaitStream(stderrFuture)
            awaitStream(stdinFuture)

            val snapshot = output.snapshot()
            val cancelled = running.cancellationRequested.get()
            val timedOut = running.timedOut.get()
            val terminationUnknown = (cancelled || timedOut) && !running.terminationConfirmed.get()
            val exitCode = if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()
            val code = when {
                terminationUnknown -> "TERMINATION_UNKNOWN"
                cancelled -> "COMMAND_CANCELLED"
                timedOut -> "COMMAND_TIMEOUT"
                exitCode == null -> "TERMINATION_UNKNOWN"
                exitCode != 0 -> "NON_ZERO_EXIT"
                snapshot.truncated -> "OUTPUT_LIMIT_REACHED"
                else -> "OK"
            }
            val ok = !cancelled && !timedOut && exitCode == 0
            val message = when (code) {
                "OK" -> "Command completed."
                "OUTPUT_LIMIT_REACHED" -> "Command completed; output was truncated."
                "NON_ZERO_EXIT" -> "Command exited with a non-zero status."
                "COMMAND_TIMEOUT" -> "Privileged command timed out and was stopped."
                "COMMAND_CANCELLED" -> "Privileged command was cancelled and stopped."
                else -> "The command ended, but complete process termination could not be confirmed."
            }
            return encodeCommandResult(
                PrivilegedCommandResult(
                    ok = ok,
                    code = code,
                    message = message,
                    data = PrivilegedCommandResultData(
                        commandId = request.commandId,
                        exitCode = exitCode,
                        stdout = snapshot.stdout,
                        stderr = snapshot.stderr,
                        timedOut = timedOut,
                        cancelled = cancelled,
                        truncated = snapshot.truncated,
                        durationMs = elapsedMillis(startedNanos),
                        privilege = currentPrivilege().wireName,
                    ),
                ),
            )
        } finally {
            running?.let { runningCommands.remove(request.commandId, it) }
            commandPermits.release()
        }
    }

    override fun cancelCommand(commandId: String): String {
        if (!COMMAND_ID_PATTERN.matches(commandId)) {
            return encodeCommandResult(failure("INVALID_ARGUMENTS", "Invalid internal command id."))
        }
        val running = runningCommands[commandId]
        if (running == null) {
            cancellationBeforeStart += commandId
            return encodeCommandResult(
                PrivilegedCommandResult(
                    ok = true,
                    code = "CANCEL_REQUESTED",
                    message = "Cancellation was recorded before command startup completed.",
                    data = PrivilegedCommandResultData(
                        commandId = commandId,
                        cancelled = true,
                        privilege = currentPrivilege().wireName,
                    ),
                ),
            )
        }
        val confirmed = running.requestCancel()
        return encodeCommandResult(
            cancelledResult(commandId, running.startedNanos, confirmed),
        )
    }

    override fun cancelAllCommands(): String {
        val commands = runningCommands.values.toList()
        if (commands.isEmpty()) {
            return encodeCommandResult(
                PrivilegedCommandResult(
                    ok = true,
                    code = "OK",
                    message = "No privileged commands are running.",
                ),
            )
        }
        val allConfirmed = commands.map { it.requestCancel() }.all { it }
        return encodeCommandResult(
            PrivilegedCommandResult(
                ok = allConfirmed,
                code = if (allConfirmed) "COMMAND_CANCELLED" else "TERMINATION_UNKNOWN",
                message = if (allConfirmed) {
                    "All running privileged commands were cancelled."
                } else {
                    "Cancellation was requested, but not every process termination was confirmed."
                },
                data = PrivilegedCommandResultData(
                    commandId = "*",
                    cancelled = true,
                    privilege = currentPrivilege().wireName,
                ),
            ),
        )
    }

    override fun destroy() {
        cancelAllCommands()
        streamExecutor.shutdownNow()
        thread(name = "shizuku-user-service-exit", isDaemon = true) {
            Thread.sleep(100)
            exitProcess(0)
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == SHIZUKU_USER_SERVICE_DESTROY_TRANSACTION) {
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun buildLaunch(input: PrivilegedCommandInput): LaunchCommand {
        val command = when (input.mode) {
            PrivilegedCommandMode.ARGV -> listOf(input.executable) + input.arguments
            PrivilegedCommandMode.SHELL -> listOf("/system/bin/sh", "-c", input.command)
        }
        return if (SETSID.canExecute()) {
            LaunchCommand(listOf(SETSID.absolutePath) + command, processGroup = true)
        } else {
            LaunchCommand(command, processGroup = false)
        }
    }

    private fun cancelledResult(
        commandId: String,
        startedNanos: Long,
        terminationConfirmed: Boolean,
    ) = PrivilegedCommandResult(
        ok = terminationConfirmed,
        code = if (terminationConfirmed) "COMMAND_CANCELLED" else "TERMINATION_UNKNOWN",
        message = if (terminationConfirmed) {
            "Privileged command was cancelled and stopped."
        } else {
            "Cancellation was requested, but process termination could not be confirmed."
        },
        data = PrivilegedCommandResultData(
            commandId = commandId,
            cancelled = true,
            durationMs = elapsedMillis(startedNanos),
            privilege = currentPrivilege().wireName,
        ),
    )

    private fun commandFailure(
        commandId: String,
        code: String,
        message: String,
        startedNanos: Long? = null,
    ) = PrivilegedCommandResult(
        ok = false,
        code = code,
        message = message,
        data = PrivilegedCommandResultData(
            commandId = commandId,
            durationMs = startedNanos?.let(::elapsedMillis) ?: 0,
            privilege = currentPrivilege().wireName,
        ),
    )

    private fun failure(code: String, message: String) = PrivilegedCommandResult(
        ok = false,
        code = code,
        message = message,
    )

    private fun encodeCommandResult(result: PrivilegedCommandResult): String =
        PrivilegedCommandJson.encodeResultForBinder(result)

    private fun awaitStream(future: Future<*>) {
        runCatching { future.get(STREAM_JOIN_MS, TimeUnit.MILLISECONDS) }
    }

    private fun currentPrivilege(): ExternalPrivilegeBridgePrivilege = when (AndroidProcess.myUid()) {
        ROOT_UID -> ExternalPrivilegeBridgePrivilege.Root
        SHELL_UID -> ExternalPrivilegeBridgePrivilege.Shell
        else -> ExternalPrivilegeBridgePrivilege.None
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos).coerceAtLeast(0)

    private fun mutate(
        packageName: String,
        userId: Int,
        protectedPackages: Array<out String>,
        command: List<String>,
        successMessage: String,
    ): String {
        invalidUser(userId)?.let { return encode(it) }
        val policy = ProtectedPackagePolicy(STATIC_PROTECTED_PACKAGES + protectedPackages)
        policy.validateMutationTarget(packageName)?.let { return encode(it) }
        val result = runFixedCommand(command, MUTATION_TIMEOUT_MS)
        return encode(if (result.ok) result.copy(message = successMessage) else result)
    }

    private fun runFixedCommand(command: List<String>, timeoutMs: Long): CommandResult {
        val canonical = command.joinToString(" ")
        HardlineCommandGuard.checkCommand(canonical)?.let { reason ->
            return CommandResult(false, "HARDLINE_BLOCKED", reason, "")
        }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            return CommandResult(
                false,
                "PROCESS_START_FAILED",
                error.message ?: "Command failed to start.",
                "",
            )
        }
        val output = ByteArrayOutputStream()
        val reader = thread(name = "shizuku-command-output", isDaemon = true) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            process.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val remaining = MAX_FIXED_OUTPUT_BYTES - output.size()
                    if (remaining > 0) output.write(buffer, 0, min(read, remaining))
                }
            }
        }
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                reader.join(1_000)
                CommandResult(
                    false,
                    "COMMAND_TIMEOUT",
                    "Privileged command timed out.",
                    output.toString(Charsets.UTF_8.name()),
                )
            } else {
                reader.join(1_000)
                val text = output.toString(Charsets.UTF_8.name()).trim()
                if (process.exitValue() == 0) {
                    CommandResult(true, "OK", "Command completed.", text)
                } else {
                    CommandResult(
                        false,
                        "COMMAND_FAILED",
                        text.ifBlank { "Privileged command failed." },
                        text,
                    )
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            CommandResult(false, "COMMAND_INTERRUPTED", "Privileged command was interrupted.", "")
        }
    }

    private fun invalidUser(userId: Int): ExternalPrivilegeActionResult? =
        if (userId in 0..MAX_USER_ID) null else ExternalPrivilegeActionResult(
            false,
            "INVALID_USER",
            "Android user id is outside the supported range.",
        )

    private fun encode(result: ExternalPrivilegeActionResult): String = buildJsonObject {
        put("ok", result.ok)
        put("code", result.code)
        put("message", result.message)
        put("data", buildJsonObject { })
    }.toString()

    private fun encode(result: CommandResult): String = buildJsonObject {
        put("ok", result.ok)
        put("code", result.code)
        put("message", result.message)
        put("data", buildJsonObject { put("output", result.output) })
    }.toString()

    private inner class RunningCommand(
        val commandId: String,
        val input: PrivilegedCommandInput,
        val process: Process,
        val processGroupId: Long?,
        val startedNanos: Long = System.nanoTime(),
    ) {
        val cancellationRequested = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val terminationConfirmed = AtomicBoolean(false)

        @Synchronized
        fun requestCancel(): Boolean {
            cancellationRequested.set(true)
            val confirmed = terminate()
            terminationConfirmed.set(confirmed)
            return confirmed
        }

        @Synchronized
        fun terminate(): Boolean {
            if (!process.isAlive) return true
            if (processGroupId != null) {
                signalGroup(processGroupId, "TERM")
                process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    signalGroup(processGroupId, "KILL")
                    process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
                }
                return !process.isAlive && !isGroupAlive(processGroupId)
            }
            process.destroy()
            process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            // Without a process group the main process can be confirmed, but descendants cannot.
            return false
        }

        fun forceTerminate() {
            terminate()
        }
    }

    private fun signalGroup(pid: Long, signal: String): Boolean = runCatching {
        val signalProcess = ProcessBuilder(
            KILL.absolutePath,
            "-$signal",
            "-$pid",
        ).start()
        signalProcess.waitFor(SIGNAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) && signalProcess.exitValue() == 0
    }.getOrDefault(false)

    private fun isGroupAlive(pid: Long): Boolean = signalGroup(pid, "0")

    /** Android's public java.lang.Process API does not expose pid() on API 26. */
    private fun processPid(process: Process): Long? = runCatching {
        generateSequence(process.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { clazz ->
                runCatching {
                    clazz.getDeclaredField("pid").apply { isAccessible = true }
                }.getOrNull()
            }
            .firstOrNull()
            ?.get(process)
            ?.let { it as? Number }
            ?.toLong()
    }.getOrNull()

    private data class LaunchCommand(
        val command: List<String>,
        val processGroup: Boolean,
    )

    private data class CommandResult(
        val ok: Boolean,
        val code: String,
        val message: String,
        val output: String,
    )

    private val ExternalPrivilegeBridgePrivilege.wireName: String
        get() = when (this) {
            ExternalPrivilegeBridgePrivilege.None -> "unavailable"
            ExternalPrivilegeBridgePrivilege.Shell -> "shell"
            ExternalPrivilegeBridgePrivilege.Root -> "root"
        }

    private companion object {
        private const val SHIZUKU_USER_SERVICE_DESTROY_TRANSACTION = 16_777_114
        private const val LIST_TIMEOUT_MS = 20_000L
        private const val MUTATION_TIMEOUT_MS = 10_000L
        private const val MAX_FIXED_OUTPUT_BYTES = 1_048_576
        private const val MAX_USER_ID = 999
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2_000
        private const val STREAM_TASKS_PER_COMMAND = 3
        private const val STREAM_JOIN_MS = 2_000L
        private const val TERMINATION_GRACE_MS = 750L
        private const val TERMINATION_WAIT_MS = 2_000L
        private const val SIGNAL_TIMEOUT_MS = 1_000L

        private val COMMAND_ID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
        private val SETSID = File("/system/bin/setsid")
        private val KILL = File("/system/bin/kill")
        private val STATIC_PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "moe.shizuku.privileged.api",
            "rikka.sui",
        )
    }
}
