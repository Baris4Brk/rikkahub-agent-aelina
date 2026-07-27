package me.rerere.rikkahub.execution

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.SshCancellationHooks
import me.rerere.rikkahub.data.ai.tools.SshToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.local.BoundedOutputStream
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import me.rerere.rikkahub.data.ai.tools.local.isUsable
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.openSshSession
import me.rerere.rikkahub.data.ai.tools.local.probeReachability
import me.rerere.rikkahub.data.ai.tools.local.runOnSession
import me.rerere.rikkahub.data.ai.tools.local.shellSingleQuote
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ManagedExecutionRegistration
import me.rerere.rikkahub.data.execution.ManagedExecutionReservation

internal data class SshExecutionSpec(
    val host: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
    val command: String,
    val stdin: String?,
    val background: Boolean,
    val timeoutMs: Int,
    val savedProfileName: String? = null,
)

internal fun interface SshExecutionSpecResolver {
    suspend fun resolve(args: JsonElement): Result<SshExecutionSpec>
}

internal data class RemoteSshProcessIdentity(
    val pid: Long,
    val processGroupId: Long,
    val processStartTicks: Long,
)

internal data class StartedSshExecution(
    val identity: RemoteSshProcessIdentity,
    val result: Deferred<ToolResult>,
    val hooks: SshCancellationHooks,
)

internal fun interface SshCancelableExecutionBackend {
    suspend fun start(spec: SshExecutionSpec, executionId: String): Result<StartedSshExecution>
}

internal class SshCancelableStartableTool(
    private val legacyTool: Tool,
    private val specResolver: SshExecutionSpecResolver,
    private val backend: SshCancelableExecutionBackend,
    private val scope: CoroutineScope,
    private val managedBackgroundStarter: SshManagedBackgroundStarter? = null,
    private val registration: ManagedExecutionRegistration? = null,
    private val unmanagedRegistry: SshUnmanagedExecutionRegistry? = null,
) : StartableTool {
    override suspend fun start(
        args: JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionHandle {
        val spec = specResolver.resolve(args).getOrElse { failure ->
            return rejected(context, failure.message ?: "ssh_arguments_invalid")
        }
        if (spec.background) {
            // Only a saved profile can be recovered without serialising credentials. Ad-hoc
            // password/private-key calls deliberately retain the old detached behaviour.
            if (spec.savedProfileName != null && managedBackgroundStarter != null) {
                return managedBackgroundStarter.start(spec, context)
            }
            if (registration != null && unmanagedRegistry != null) {
                return startTemporaryBackground(spec, context, registration, unmanagedRegistry)
            }
            return LegacyToolExecutionHandle(
                executionId = "ssh:unmanaged_${context.runId}",
                result = scope.async { legacyTool.execute(args) },
            )
        }
        val executionId = "ssh-live-${context.runId}-${UUID.randomUUID()}"
        val started = backend.start(spec, executionId).getOrElse { failure ->
            return rejected(context, failure.message ?: "ssh_start_failed")
        }
        return SshToolExecutionHandle(executionId, started.result, started.hooks)
    }

    private suspend fun startTemporaryBackground(
        spec: SshExecutionSpec,
        context: ToolExecutionContext,
        registration: ManagedExecutionRegistration,
        registry: SshUnmanagedExecutionRegistry,
    ): ToolExecutionHandle {
        val nativeId = "unmanaged_${UUID.randomUUID().toString().replace("-", "")}"
        val executionId = managedExecutionId(ManagedExecutionRuntime.SSH, nativeId)
        registration.reserve(
            context = context,
            reservation = ManagedExecutionReservation(
                executionId = executionId,
                runtime = ExecutionRuntime.SSH,
                completionPolicy = CompletionPolicy.DETACH_BACKGROUND,
            ),
        )
        val started = backend.start(spec, executionId).getOrElse { failure ->
            runCatching { registration.failed(executionId, "ssh_unmanaged_start_failed") }
            return rejected(context, failure.message ?: "ssh_start_failed")
        }
        runCatching {
            registration.running(
                executionId = executionId,
                runtimeInstanceMarker = started.identity.processStartTicks.toString(),
            )
        }
        val owner = SshUnmanagedOwner(
            assistantId = context.capabilitySubject?.id ?: context.assistantId,
            conversationId = context.conversationId.toString(),
            origin = context.callOrigin,
        )
        registry.register(executionId, owner, started)
        return SshUnmanagedBackgroundToolHandle(
            executionId = executionId,
            acknowledgement = listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("mode", "unmanaged_background")
                put("execution_id", executionId)
                put("recovery", "unsupported_after_app_restart")
            }.toString())),
            registry = registry,
            owner = owner,
            runId = context.runId.toString(),
            scope = scope,
        )
    }

    private fun rejected(
        context: ToolExecutionContext,
        code: String,
    ) = LegacyToolExecutionHandle(
        executionId = "ssh-rejected-${context.runId}",
        result = scope.async {
            listOf(UIMessagePart.Text(buildJsonObject { put("error", code) }.toString()))
        },
    )
}

class SshManagedStartableFactory(
    context: Context,
    private val repository: SshHostRepository,
    private val scope: CoroutineScope,
    ledger: ManagedExecutionLedger,
    tokenProvider: ExecutionTokenProvider,
    private val registration: ManagedExecutionRegistration? = null,
    private val unmanagedRegistry: SshUnmanagedExecutionRegistry? = null,
) {
    private val backend = AndroidSshCancelableExecutionBackend(context, scope)
    private val managedBackgroundStarter = SshManagedBackgroundStarter(
        supervisor = AndroidSshManagedSupervisor(context),
        ledger = ledger,
        tokenProvider = tokenProvider,
        scope = scope,
        registration = registration,
    )

    fun createInline(legacyTool: Tool): StartableTool = SshCancelableStartableTool(
        legacyTool,
        inlineResolver(),
        backend,
        scope,
        registration = registration,
        unmanagedRegistry = unmanagedRegistry,
    )

    fun createSaved(legacyTool: Tool): StartableTool = SshCancelableStartableTool(
        legacyTool,
        savedResolver(),
        backend,
        scope,
        managedBackgroundStarter,
        registration,
        unmanagedRegistry,
    )

    private fun inlineResolver() = SshExecutionSpecResolver { args ->
        runCatching {
            val obj = args.jsonObject
            val auth = SshAuth(
                password = obj.string("password"),
                privateKey = obj.string("private_key"),
                passphrase = obj.string("passphrase"),
            )
            require(auth.isUsable()) { "ssh_credentials_missing" }
            obj.toSpec(auth = auth)
        }
    }

    private fun savedResolver() = SshExecutionSpecResolver { args ->
        runCatching {
            val obj = args.jsonObject
            val name = obj.string("name") ?: error("ssh_profile_name_missing")
            val host = repository.getByName(name) ?: error("ssh_saved_profile_not_found")
            val auth = SshAuth(host.password, host.privateKey, host.passphrase)
            require(auth.isUsable()) { "ssh_saved_credentials_missing" }
            obj.toSpec(
                auth = auth,
                host = host.host,
                port = host.port,
                user = host.user,
                savedProfileName = host.name,
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObject.toSpec(
        auth: SshAuth,
        host: String = string("host") ?: error("ssh_host_missing"),
        port: Int = get("port")?.jsonPrimitive?.intOrNull ?: 22,
        user: String = string("user") ?: error("ssh_user_missing"),
        savedProfileName: String? = null,
    ): SshExecutionSpec {
        val command = string("command") ?: error("ssh_command_missing")
        require(command.isNotBlank() && '\u0000' !in command) { "ssh_command_invalid" }
        val stdin = string("stdin")
        val background = string("background")?.toBooleanStrictOrNull() ?: false
        require(!background || stdin == null) { "ssh_background_stdin_conflict" }
        return SshExecutionSpec(
            host = host,
            port = port.coerceIn(1, 65_535),
            user = user,
            auth = auth,
            command = command,
            stdin = stdin,
            background = background,
            timeoutMs = ((get("timeout_seconds")?.jsonPrimitive?.intOrNull ?: 30)
                .coerceIn(1, 300) * 1_000),
            savedProfileName = savedProfileName,
        )
    }
}

internal class AndroidSshCancelableExecutionBackend(
    context: Context,
    private val scope: CoroutineScope,
) : SshCancelableExecutionBackend {
    private val appContext = context.applicationContext

    override suspend fun start(
        spec: SshExecutionSpec,
        executionId: String,
    ): Result<StartedSshExecution> = runCatching {
        val outcome = probeReachability(appContext, spec.host, spec.port)
        if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) {
            error("ssh_tcp_unreachable")
        }
        val session = withContext(kotlinx.coroutines.Dispatchers.IO) {
            openSshSession(
                newJSch(appContext),
                spec.host,
                spec.port,
                spec.user,
                spec.auth,
                spec.timeoutMs,
                outcome.winningNetwork,
            )
        }
        val channel = session.openChannel("exec") as ChannelExec
        val identityCapture = IdentityCapturingOutputStream()
        val stderr = BoundedOutputStream(MAX_STDERR_BYTES)
        channel.setCommand(wrapCancelableCommand(executionId, spec.command))
        channel.outputStream = identityCapture
        channel.setErrStream(stderr)
        channel.setInputStream(ByteArrayInputStream((spec.stdin ?: "").toByteArray(Charsets.UTF_8)))
        try {
            withContext(kotlinx.coroutines.Dispatchers.IO) { channel.connect(spec.timeoutMs) }
            val identity = withTimeoutOrNull(IDENTITY_TIMEOUT_MS) {
                identityCapture.identity.await()
            } ?: error("ssh_remote_identity_timeout")
            val killJob = AtomicReference<Deferred<Boolean>?>(null)
            val result = scope.async {
                while (!channel.isClosed) delay(CHANNEL_POLL_MS)
                val exitCode = channel.exitStatus
                val payload = buildJsonObject {
                    put("success", exitCode == 0)
                    put("exit_code", exitCode)
                    put("stdout", identityCapture.output.snapshot())
                    put("stderr", stderr.snapshot())
                }
                runCatching { channel.disconnect() }
                runCatching { session.disconnect() }
                listOf(UIMessagePart.Text(payload.toString()))
            }
            val hooks = SshCancellationHooks(
                closeChannel = {
                    // Keep the control channel alive until the exact remote process group has
                    // been signalled. Closing it first can lose the only verified identity.
                },
                terminateRemoteProcessGroup = { force ->
                    val task = scope.async {
                        val stopped = terminateRemote(spec, identity, force)
                        if (stopped) {
                            runCatching { channel.disconnect() }
                            runCatching { session.disconnect() }
                        }
                        stopped
                    }
                    killJob.set(task)
                    true
                },
                awaitRemoteExit = {
                    val scheduled = killJob.get()
                    if (scheduled != null) scheduled.await()
                    else !remoteStillRunning(spec, identity)
                },
            )
            StartedSshExecution(identity, result, hooks)
        } catch (failure: Throwable) {
            runCatching { channel.disconnect() }
            runCatching { session.disconnect() }
            throw failure
        }
    }

    private suspend fun terminateRemote(
        spec: SshExecutionSpec,
        identity: RemoteSshProcessIdentity,
        force: Boolean,
    ): Boolean {
        val signal = if (force) "KILL" else "TERM"
        val output = runControl(
            spec,
            identityCheckScript(identity) +
                "kill -$signal -- -${identity.processGroupId} 2>/dev/null || true; " +
                "sleep 0.1; " + identityStateScript(identity),
        ) ?: return false
        return output.contains("running=0") && output.contains("identity_verified=1")
    }

    private suspend fun remoteStillRunning(
        spec: SshExecutionSpec,
        identity: RemoteSshProcessIdentity,
    ): Boolean = runControl(spec, identityStateScript(identity))
        ?.contains("running=1") ?: true

    private suspend fun runControl(spec: SshExecutionSpec, command: String): String? {
        val outcome = probeReachability(appContext, spec.host, spec.port)
        if (outcome.winningNetwork == null && outcome.failures.isNotEmpty()) return null
        return runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            val session = openSshSession(
                newJSch(appContext), spec.host, spec.port, spec.user, spec.auth,
                spec.timeoutMs, outcome.winningNetwork,
            )
            try {
                val result = runOnSession(session, command, spec.timeoutMs)
                result["stdout"]?.jsonPrimitive?.contentOrNull
            } finally {
                session.disconnect()
            }
        }
    }

    private class IdentityCapturingOutputStream : OutputStream() {
        val identity = CompletableDeferred<RemoteSshProcessIdentity>()
        val output = BoundedOutputStream(MAX_STDOUT_BYTES)
        private val header = StringBuilder()
        private var resolved = false

        @Synchronized
        override fun write(value: Int) {
            if (resolved) {
                output.write(value)
                return
            }
            val char = value.toChar()
            if (char == '\n') {
                val match = IDENTITY_PATTERN.matchEntire(header.toString())
                if (match != null) {
                    identity.complete(
                        RemoteSshProcessIdentity(
                            pid = match.groupValues[1].toLong(),
                            processGroupId = match.groupValues[2].toLong(),
                            processStartTicks = match.groupValues[3].toLong(),
                        )
                    )
                    resolved = true
                } else {
                    identity.completeExceptionally(
                        IllegalStateException("ssh_remote_identity_invalid")
                    )
                    resolved = true
                }
                return
            }
            if (header.length >= MAX_IDENTITY_LINE) {
                identity.completeExceptionally(IllegalStateException("ssh_remote_identity_invalid"))
                resolved = true
            } else {
                header.append(char)
            }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            for (index in offset until offset + length) write(bytes[index].toInt() and 0xff)
        }
    }

    companion object {
        const val MAX_STDOUT_BYTES = 8_000
        const val MAX_STDERR_BYTES = 2_000
        const val MAX_IDENTITY_LINE = 180
        const val IDENTITY_TIMEOUT_MS = 10_000L
        const val CHANNEL_POLL_MS = 50L
        val IDENTITY_PATTERN = Regex("__RIKKAHUB_ID__:([0-9]+):([0-9]+):([0-9]+)")

        internal fun wrapCancelableCommand(executionId: String, command: String): String {
            val directory = "/tmp/rikkahub-${executionId.filter(Char::isLetterOrDigit).takeLast(32)}"
            return "set -u; d=${shellSingleQuote(directory)}; mkdir -m 700 \"\$d\" || exit 125; " +
                "cat >\"\$d/in\"; " +
                "setsid sh -c ${shellSingleQuote(command)} >\"\$d/out\" 2>\"\$d/err\" <\"\$d/in\" & " +
                "pid=\$!; pgid=\$(ps -o pgid= -p \"\$pid\" | tr -d ' '); " +
                "ticks=\$(sed 's/^.*) //' \"/proc/\$pid/stat\" | awk '{print \$20}'); " +
                "case \"\$pid:\$pgid:\$ticks\" in *[!0-9:]*) exit 124;; esac; " +
                "printf '__RIKKAHUB_ID__:%s:%s:%s\\n' \"\$pid\" \"\$pgid\" \"\$ticks\"; " +
                "wait \"\$pid\"; code=\$?; cat \"\$d/out\"; cat \"\$d/err\" >&2; " +
                "rm -rf \"\$d\"; exit \"\$code\""
        }

        internal fun identityCheckScript(identity: RemoteSshProcessIdentity): String =
            "if [ ! -r /proc/${identity.pid}/stat ]; then echo 'identity_verified=1 running=0'; exit 0; fi; " +
                "ticks=\$(sed 's/^.*) //' /proc/${identity.pid}/stat | awk '{print \$20}'); " +
                "pgid=\$(ps -o pgid= -p ${identity.pid} | tr -d ' '); " +
                "[ \"\$ticks\" = '${identity.processStartTicks}' ] && " +
                "[ \"\$pgid\" = '${identity.processGroupId}' ] || " +
                "{ echo 'identity_verified=0 running=1'; exit 41; }; "

        internal fun identityStateScript(identity: RemoteSshProcessIdentity): String =
            identityCheckScript(identity) +
                "if [ -r /proc/${identity.pid}/stat ]; then echo 'identity_verified=1 running=1'; " +
                "else echo 'identity_verified=1 running=0'; fi"
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull
