package me.rerere.rikkahub.execution

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.execOneShot
import me.rerere.rikkahub.data.ai.tools.local.runCancellableSshOp
import me.rerere.rikkahub.data.ai.tools.local.shellSingleQuote

/**
 * Small remote supervisor protocol for saved SSH profiles.
 *
 * The remote command is entirely host-owned code. The user command is delivered only over stdin,
 * never interpolated into the control shell. Every later operation reopens the saved profile and
 * authenticates with a derived token before inspecting or signalling the exact PID/PGID/start-tick
 * identity. Hosts without Linux /proc and setsid fail closed instead of pretending to be managed.
 */
internal class AndroidSshManagedSupervisor(
    context: Context,
) : SshManagedSupervisor {
    private val appContext = context.applicationContext

    override suspend fun start(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
        command: String,
    ): Result<SshSupervisorIdentity> = runCatching {
        require(command.isNotBlank() && '\u0000' !in command) { "ssh_command_invalid" }
        val output = execute(connection, startCommand(nativeId, token), command).getOrThrow()
        parseStatus(output).identity
    }

    override suspend fun status(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
    ): Result<SshSupervisorStatus> = runCatching {
        parseStatus(execute(connection, statusCommand(nativeId, token)).getOrThrow())
    }

    override suspend fun stop(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
        force: Boolean,
    ): Result<SshSupervisorStatus> = runCatching {
        parseStatus(execute(connection, stopCommand(nativeId, token, force)).getOrThrow())
    }

    override suspend fun logs(
        connection: SshSavedConnection,
        nativeId: String,
        token: String,
        tailBytes: Int,
    ): Result<SshSupervisorLogs> = runCatching {
        val bounded = tailBytes.coerceIn(1, MAX_LOG_BYTES)
        val stdout = parseLog(
            execute(connection, logsCommand(nativeId, token, "stdout", bounded)).getOrThrow()
        )
        val stderr = parseLog(
            execute(connection, logsCommand(nativeId, token, "stderr", bounded)).getOrThrow()
        )
        SshSupervisorLogs(
            stdout = stdout.body,
            stderr = stderr.body,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    private suspend fun execute(
        connection: SshSavedConnection,
        command: String,
        stdin: String? = null,
    ): Result<String> = runCatching {
        val payload = runCancellableSshOp(connection.timeoutMs.toLong()) { sessionRef ->
            execOneShot(
                context = appContext,
                host = connection.host,
                port = connection.port,
                user = connection.user,
                auth = connection.auth,
                command = command,
                timeoutMs = connection.timeoutMs,
                sessionRef = sessionRef,
                stdin = stdin,
            )
        }
        payload["error"]?.jsonPrimitive?.contentOrNull?.let { error ->
            throw IllegalStateException(normalizeRemoteError(error))
        }
        val exitCode = payload["exit_code"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalStateException("ssh_managed_response_invalid")
        val stdout = payload["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (exitCode != 0) {
            val stderr = payload["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw IllegalStateException(normalizeRemoteError(stderr))
        }
        stdout
    }

    private fun normalizeRemoteError(raw: String): String = when {
        "capability_unavailable" in raw -> "ssh_managed_capability_unavailable"
        "identity_mismatch" in raw -> "execution_identity_mismatch"
        "token_mismatch" in raw -> "execution_token_unavailable"
        "run_not_found" in raw -> "execution_not_found"
        "command_timeout" in raw -> "ssh_managed_timeout"
        else -> "ssh_managed_remote_command_failed"
    }

    private data class ParsedLog(val body: String, val truncated: Boolean)

    private fun parseLog(output: String): ParsedLog {
        val separator = output.indexOf(LOG_BODY_MARKER)
        require(separator >= 0) { "ssh_managed_log_response_invalid" }
        val metadata = parseKeyValues(output.substring(0, separator))
        return ParsedLog(
            body = output.substring(separator + LOG_BODY_MARKER.length).removePrefix("\n"),
            truncated = metadata["truncated"] == "1",
        )
    }

    private fun parseStatus(output: String): SshSupervisorStatus {
        val values = parseKeyValues(output)
        val identity = SshSupervisorIdentity(
            pid = values.requirePositiveLong("pid"),
            processGroupId = values.requirePositiveLong("pgid"),
            processStartTicks = values.requirePositiveLong("start_ticks"),
        )
        return SshSupervisorStatus(
            identity = identity,
            state = values["state"]?.takeIf(String::isNotBlank) ?: "unknown",
            running = values["running"] == "1",
            identityVerified = values["identity_verified"] == "1",
            exitCode = values["exit_code"]?.toIntOrNull(),
        )
    }

    private fun parseKeyValues(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0) put(line.substring(0, separator), line.substring(separator + 1))
        }
    }

    private fun Map<String, String>.requirePositiveLong(key: String): Long =
        get(key)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalStateException("ssh_managed_identity_invalid")

    companion object {
        private const val MAX_LOG_BYTES = 256 * 1024
        private const val LOG_BODY_MARKER = "--body--"
        private val NATIVE_ID = Regex("[A-Za-z0-9_-]{8,96}")
        private val TOKEN = Regex("[a-f0-9]{64}")

        internal fun startCommand(nativeId: String, token: String): String {
            validate(nativeId, token)
            val monitor = monitorScript()
            return """
                set -u
                run=${shellSingleQuote(nativeId)}
                token=${shellSingleQuote(token)}
                base="${'$'}{XDG_STATE_HOME:-${'$'}HOME/.local/state}/rikkahub-managed-v1"
                dir="${'$'}base/${'$'}run"
                fail() { printf 'error=%s\n' "${'$'}1" >&2; exit "${'$'}{2:-125}"; }
                for command_name in setsid ps sed awk sha256sum tail nohup; do
                  command -v "${'$'}command_name" >/dev/null 2>&1 || fail capability_unavailable
                done
                [ -r "/proc/${'$'}${'$'}/stat" ] || fail capability_unavailable
                umask 077
                mkdir -p "${'$'}base" || fail state_root_unavailable
                [ ! -e "${'$'}dir" ] || fail run_exists
                mkdir "${'$'}dir" || fail run_create_failed
                printf %s "${'$'}token" | sha256sum | awk '{print ${'$'}1}' > "${'$'}dir/token.sha256"
                cat > "${'$'}dir/command.sh" || fail command_write_failed
                chmod 600 "${'$'}dir/command.sh"
                printf %s ${shellSingleQuote(monitor)} > "${'$'}dir/monitor.sh"
                chmod 700 "${'$'}dir/monitor.sh"
                printf 'state=starting\n' > "${'$'}dir/status"
                nohup sh "${'$'}dir/monitor.sh" "${'$'}dir" >/dev/null 2>&1 </dev/null &
                n=0
                while [ ! -f "${'$'}dir/identity" ] && [ "${'$'}n" -lt 100 ]; do
                  sleep 0.05
                  n=${'$'}((n+1))
                done
                [ -f "${'$'}dir/identity" ] || fail start_identity_timeout
                cat "${'$'}dir/identity"
                cat "${'$'}dir/status"
                printf 'identity_verified=1\nrunning=1\n'
            """.trimIndent()
        }

        internal fun statusCommand(nativeId: String, token: String): String =
            controlPrelude(nativeId, token) + "\n" + statusBody()

        internal fun stopCommand(nativeId: String, token: String, force: Boolean): String {
            val signal = if (force) "KILL" else "TERM"
            return controlPrelude(nativeId, token) + "\n" + """
                load_identity
                probe_identity
                if [ "${'$'}identity_verified" != 1 ]; then
                  printf 'error=identity_mismatch\nidentity_verified=0\nrunning=1\n' >&2
                  exit 41
                fi
                if [ "${'$'}running" = 1 ]; then
                  printf 'state=stop_requested\n' > "${'$'}dir/status"
                  kill -$signal -- "-${'$'}pgid" 2>/dev/null || true
                  sleep 0.1
                  probe_identity
                fi
                if [ "${'$'}running" = 0 ]; then printf 'state=stopped\n' > "${'$'}dir/status"; fi
                print_status
            """.trimIndent()
        }

        internal fun logsCommand(
            nativeId: String,
            token: String,
            stream: String,
            tailBytes: Int,
        ): String {
            validate(nativeId, token)
            require(stream == "stdout" || stream == "stderr")
            require(tailBytes in 1..MAX_LOG_BYTES)
            return controlPrelude(nativeId, token) + "\n" + """
                file="${'$'}dir/${stream}.log"
                size=0
                [ -f "${'$'}file" ] && size=${'$'}(wc -c < "${'$'}file" | tr -d ' ')
                truncated=0
                [ "${'$'}size" -gt $tailBytes ] && truncated=1
                printf 'truncated=%s\n--body--\n' "${'$'}truncated"
                [ -f "${'$'}file" ] && tail -c $tailBytes "${'$'}file"
            """.trimIndent()
        }

        private fun controlPrelude(nativeId: String, token: String): String {
            validate(nativeId, token)
            return """
                set -u
                run=${shellSingleQuote(nativeId)}
                token=${shellSingleQuote(token)}
                base="${'$'}{XDG_STATE_HOME:-${'$'}HOME/.local/state}/rikkahub-managed-v1"
                dir="${'$'}base/${'$'}run"
                fail() { printf 'error=%s\n' "${'$'}1" >&2; exit "${'$'}{2:-125}"; }
                [ -d "${'$'}dir" ] || fail run_not_found 3
                command -v sha256sum >/dev/null 2>&1 || fail capability_unavailable
                actual=${'$'}(printf %s "${'$'}token" | sha256sum | awk '{print ${'$'}1}')
                expected=${'$'}(cat "${'$'}dir/token.sha256" 2>/dev/null) || fail token_missing 3
                [ "${'$'}actual" = "${'$'}expected" ] || fail token_mismatch 3
                value() { sed -n "s/^${'$'}1=//p" "${'$'}dir/identity" | head -n 1; }
                load_identity() {
                  [ -f "${'$'}dir/identity" ] || fail identity_missing 3
                  pid=${'$'}(value pid); pgid=${'$'}(value pgid); start_ticks=${'$'}(value start_ticks)
                  case "${'$'}pid:${'$'}pgid:${'$'}start_ticks" in *[!0-9:]*) fail identity_invalid 4;; esac
                }
                probe_identity() {
                  if [ ! -r "/proc/${'$'}pid/stat" ]; then
                    identity_verified=1; running=0; return
                  fi
                  now_ticks=${'$'}(sed 's/^.*) //' "/proc/${'$'}pid/stat" | awk '{print ${'$'}20}')
                  now_pgid=${'$'}(ps -o pgid= -p "${'$'}pid" | tr -d ' ')
                  if [ "${'$'}now_ticks" = "${'$'}start_ticks" ] && [ "${'$'}now_pgid" = "${'$'}pgid" ]; then
                    identity_verified=1; running=1
                  else
                    identity_verified=0; running=1
                  fi
                }
                print_status() {
                  state=${'$'}(sed -n 's/^state=//p' "${'$'}dir/status" 2>/dev/null | head -n 1)
                  exit_code=${'$'}(sed -n 's/^exit_code=//p' "${'$'}dir/status" 2>/dev/null | head -n 1)
                  [ -n "${'$'}state" ] || state=unknown
                  printf 'pid=%s\npgid=%s\nstart_ticks=%s\nstate=%s\n' "${'$'}pid" "${'$'}pgid" "${'$'}start_ticks" "${'$'}state"
                  [ -z "${'$'}exit_code" ] || printf 'exit_code=%s\n' "${'$'}exit_code"
                  printf 'identity_verified=%s\nrunning=%s\n' "${'$'}identity_verified" "${'$'}running"
                }
            """.trimIndent()
        }

        private fun statusBody(): String = """
            load_identity
            probe_identity
            print_status
        """.trimIndent()

        private fun monitorScript(): String = """
            #!/bin/sh
            set -u
            dir=${'$'}1
            setsid sh "${'$'}dir/command.sh" >"${'$'}dir/stdout.log" 2>"${'$'}dir/stderr.log" </dev/null &
            pid=${'$'}!
            pgid=${'$'}(ps -o pgid= -p "${'$'}pid" | tr -d ' ')
            start_ticks=${'$'}(sed 's/^.*) //' "/proc/${'$'}pid/stat" | awk '{print ${'$'}20}')
            case "${'$'}pid:${'$'}pgid:${'$'}start_ticks" in *[!0-9:]*) printf 'state=failed\nexit_code=124\n' > "${'$'}dir/status"; exit 0;; esac
            printf 'pid=%s\npgid=%s\nstart_ticks=%s\n' "${'$'}pid" "${'$'}pgid" "${'$'}start_ticks" > "${'$'}dir/identity.tmp"
            mv "${'$'}dir/identity.tmp" "${'$'}dir/identity"
            rm -f "${'$'}dir/command.sh"
            printf 'state=running\n' > "${'$'}dir/status"
            wait "${'$'}pid"
            code=${'$'}?
            printf 'state=exited\nexit_code=%s\n' "${'$'}code" > "${'$'}dir/status"
        """.trimIndent() + "\n"

        private fun validate(nativeId: String, token: String) {
            require(nativeId.matches(NATIVE_ID)) { "ssh_managed_id_invalid" }
            require(token.matches(TOKEN)) { "ssh_managed_token_invalid" }
        }
    }
}
