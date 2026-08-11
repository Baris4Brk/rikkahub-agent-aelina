package me.rerere.rikkahub.execution

import android.content.Context
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.tools.local.CaptureResult
import me.rerere.rikkahub.data.ai.tools.local.runCommandCapture
import me.rerere.rikkahub.data.ai.tools.local.shellSingleQuote

data class TermuxSupervisorIdentity(
    val nativeId: String,
    val pid: Long,
    val processGroupId: Long,
    val processStartTicks: Long,
)

data class TermuxSupervisorStatus(
    val identity: TermuxSupervisorIdentity,
    val state: String,
    val running: Boolean,
    val exitCode: Int? = null,
    val identityVerified: Boolean,
)

data class TermuxSupervisorLogs(
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

interface TermuxManagedSupervisor {
    suspend fun start(
        nativeId: String,
        token: String,
        command: String,
        workingDirectory: String,
    ): Result<TermuxSupervisorIdentity>

    suspend fun status(nativeId: String, token: String): Result<TermuxSupervisorStatus>
    suspend fun stop(nativeId: String, token: String, force: Boolean): Result<TermuxSupervisorStatus>
    suspend fun logs(nativeId: String, token: String, tailBytes: Int): Result<TermuxSupervisorLogs>
}

class AndroidTermuxManagedSupervisor(
    context: Context,
) : TermuxManagedSupervisor {
    private val appContext = context.applicationContext
    private val installMutex = Mutex()
    @Volatile private var installedHash: String? = null

    override suspend fun start(
        nativeId: String,
        token: String,
        command: String,
        workingDirectory: String,
    ): Result<TermuxSupervisorIdentity> = runCatching {
        validateIdentity(nativeId, token)
        require(command.isNotBlank() && '\u0000' !in command) { "termux_command_invalid" }
        require(workingDirectory.isNotBlank() && '\u0000' !in workingDirectory) {
            "termux_working_directory_invalid"
        }
        ensureInstalled().getOrThrow()
        val payload = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        val response = invoke("start", nativeId, token, workingDirectory, payload).getOrThrow()
        response.requireOk()
        response.toIdentity(nativeId)
    }

    override suspend fun status(
        nativeId: String,
        token: String,
    ): Result<TermuxSupervisorStatus> = runCatching {
        validateIdentity(nativeId, token)
        ensureInstalled().getOrThrow()
        invoke("status", nativeId, token).getOrThrow().toStatus(nativeId)
    }

    override suspend fun stop(
        nativeId: String,
        token: String,
        force: Boolean,
    ): Result<TermuxSupervisorStatus> = runCatching {
        validateIdentity(nativeId, token)
        ensureInstalled().getOrThrow()
        invoke("stop", nativeId, token, if (force) "1" else "0")
            .getOrThrow().toStatus(nativeId)
    }

    override suspend fun logs(
        nativeId: String,
        token: String,
        tailBytes: Int,
    ): Result<TermuxSupervisorLogs> = runCatching {
        validateIdentity(nativeId, token)
        ensureInstalled().getOrThrow()
        val capped = tailBytes.coerceIn(1, 256 * 1024)
        val stdout = invoke("logs", nativeId, token, "stdout", capped.toString()).getOrThrow()
        val stderr = invoke("logs", nativeId, token, "stderr", capped.toString()).getOrThrow()
        stdout.requireOk()
        stderr.requireOk()
        TermuxSupervisorLogs(
            stdout = stdout.bodyAfterHeader(),
            stderr = stderr.bodyAfterHeader(),
            truncated = stdout.fields["truncated"] == "1" || stderr.fields["truncated"] == "1",
        )
    }

    private suspend fun ensureInstalled(): Result<Unit> = runCatching {
        if (installedHash == SCRIPT_SHA256) return@runCatching
        installMutex.withLock {
            if (installedHash == SCRIPT_SHA256) return@withLock
            val encoded = Base64.getEncoder().encodeToString(SUPERVISOR_SCRIPT.toByteArray())
            val command = "set -eu; mkdir -p ${shellSingleQuote(SUPERVISOR_DIRECTORY)}; " +
                "tmp=${shellSingleQuote("$SUPERVISOR_PATH.tmp")}; " +
                "printf %s ${shellSingleQuote(encoded)} | base64 -d > \"\$tmp\"; " +
                "actual=\$(sha256sum \"\$tmp\" | awk '{print \$1}'); " +
                "[ \"\$actual\" = ${shellSingleQuote(SCRIPT_SHA256)} ] || exit 42; " +
                "chmod 700 \"\$tmp\"; mv -f \"\$tmp\" ${shellSingleQuote(SUPERVISOR_PATH)}; " +
                "printf 'ok=1\\nhash=%s\\n' \"\$actual\""
            val result = runCommandCapture(
                ctx = appContext,
                executable = TERMUX_SH,
                arguments = arrayOf("-c", command),
                workingDir = TERMUX_HOME,
                timeoutMs = MANAGEMENT_TIMEOUT_MS,
            )
            val output = (result as? CaptureResult.Success)
                ?.takeIf { it.exitCode == 0 }
                ?.stdout
                ?: error("termux_supervisor_install_failed")
            val response = SupervisorResponse.parse(output)
            response.requireOk()
            require(response.fields["hash"] == SCRIPT_SHA256) { "termux_supervisor_hash_mismatch" }
            installedHash = SCRIPT_SHA256
        }
    }

    private suspend fun invoke(vararg args: String): Result<SupervisorResponse> = runCatching {
        val command = buildString {
            append(shellSingleQuote(SUPERVISOR_PATH))
            args.forEach { argument -> append(' ').append(shellSingleQuote(argument)) }
        }
        when (val result = runCommandCapture(
            ctx = appContext,
            executable = TERMUX_SH,
            arguments = arrayOf("-c", command),
            workingDir = TERMUX_HOME,
            timeoutMs = MANAGEMENT_TIMEOUT_MS,
        )) {
            is CaptureResult.Success -> {
                val parsed = SupervisorResponse.parse(result.stdout)
                if (result.exitCode != 0 && parsed.fields["ok"] != "1") {
                    error(parsed.fields["error"] ?: "termux_supervisor_failed")
                }
                parsed
            }
            CaptureResult.Timeout -> error("termux_supervisor_timeout")
            CaptureResult.Denied -> error("termux_permission_denied")
            is CaptureResult.OtherError -> error("termux_supervisor_transport_failed")
        }
    }

    private fun validateIdentity(nativeId: String, token: String) {
        require(nativeId.matches(ID_PATTERN)) { "termux_run_id_invalid" }
        require(token.matches(TOKEN_PATTERN)) { "termux_token_invalid" }
    }

    private data class SupervisorResponse(
        val fields: Map<String, String>,
        val raw: String,
    ) {
        fun requireOk() {
            require(fields["ok"] == "1") { fields["error"] ?: "termux_supervisor_failed" }
        }

        fun toIdentity(nativeId: String) = TermuxSupervisorIdentity(
            nativeId = nativeId,
            pid = fields["pid"]?.toLongOrNull() ?: error("termux_identity_missing"),
            processGroupId = fields["pgid"]?.toLongOrNull() ?: error("termux_identity_missing"),
            processStartTicks = fields["start_ticks"]?.toLongOrNull()
                ?: error("termux_identity_missing"),
        )

        fun toStatus(nativeId: String): TermuxSupervisorStatus {
            val identity = toIdentity(nativeId)
            val state = fields["state"] ?: "unknown"
            return TermuxSupervisorStatus(
                identity = identity,
                state = state,
                running = state in setOf("starting", "running", "stop_requested"),
                exitCode = fields["exit_code"]?.toIntOrNull(),
                identityVerified = fields["identity_verified"] == "1",
            )
        }

        fun bodyAfterHeader(): String = raw.substringAfter("\n--body--\n", missingDelimiterValue = "")

        companion object {
            fun parse(raw: String): SupervisorResponse {
                val header = raw.substringBefore("\n--body--\n")
                val fields = header.lineSequence().mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }.toMap()
                return SupervisorResponse(fields, raw)
            }
        }
    }

    companion object {
        private const val TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val SUPERVISOR_DIRECTORY = "$TERMUX_HOME/.cache/rikkahub-managed-v1"
        private const val SUPERVISOR_PATH = "$SUPERVISOR_DIRECTORY/supervisor.sh"
        private const val MANAGEMENT_TIMEOUT_MS = 15_000L
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{8,96}")
        private val TOKEN_PATTERN = Regex("[a-f0-9]{64}")

        internal val SUPERVISOR_SCRIPT: String = """
            #!$TERMUX_SH
            set -u
            base="§HOME/.cache/rikkahub-managed-v1/runs"
            mkdir -p "§base"
            mode="§{1:-}"
            run="§{2:-}"
            token="§{3:-}"
            valid_id() { printf %s "§1" | grep -Eq '^[A-Za-z0-9_-]{8,96}§'; }
            fail() { printf 'ok=0\nerror=%s\n' "§1"; exit "§{2:-2}"; }
            hash_token() { printf %s "§1" | sha256sum | awk '{print §1}'; }
            start_ticks() { sed 's/^.*) //' "/proc/§1/stat" 2>/dev/null | awk '{print §20}'; }
            current_pgid() { ps -o pgid= -p "§1" 2>/dev/null | tr -d ' '; }
            [ -n "§mode" ] || fail invalid_mode
            valid_id "§run" || fail invalid_run_id
            printf %s "§token" | grep -Eq '^[a-f0-9]{64}§' || fail invalid_token
            dir="§base/§run"
            verify_token() {
              [ -f "§dir/token.sha256" ] || fail run_not_found 3
              actual="§(hash_token "§token")"
              expected="§(cat "§dir/token.sha256")"
              [ "§actual" = "§expected" ] || fail token_mismatch 3
            }
            load_identity() {
              [ -f "§dir/identity" ] || fail identity_missing 3
              . "§dir/identity"
            }
            verify_identity() {
              load_identity
              if [ ! -r "/proc/§pid/stat" ]; then return 1; fi
              now_ticks="§(start_ticks "§pid")"
              now_pgid="§(current_pgid "§pid")"
              [ "§now_ticks" = "§start_ticks" ] || fail identity_mismatch 4
              [ "§now_pgid" = "§pgid" ] || fail identity_mismatch 4
              return 0
            }
            case "§mode" in
              start)
                work="§{4:-}"
                payload="§{5:-}"
                [ ! -e "§dir" ] || fail run_exists
                mkdir -m 700 "§dir" || fail run_create_failed
                hash_token "§token" > "§dir/token.sha256"
                printf %s "§payload" | base64 -d > "§dir/command.sh" || fail command_decode_failed
                chmod 600 "§dir/command.sh"
                printf %s "§work" > "§dir/workdir"
                printf 'state=starting\n' > "§dir/status"
                nohup "$SUPERVISOR_PATH" monitor "§run" "§token" >/dev/null 2>&1 </dev/null &
                n=0
                while [ ! -f "§dir/identity" ] && [ "§n" -lt 80 ]; do sleep 0.05; n=§((n+1)); done
                [ -f "§dir/identity" ] || fail start_identity_timeout
                printf 'ok=1\n'; cat "§dir/identity"; cat "§dir/status"
                ;;
              monitor)
                verify_token
                work="§(cat "§dir/workdir")"
                cd "§work" || { printf 'state=failed\nexit_code=126\n' > "§dir/status"; exit 0; }
                command -v setsid >/dev/null 2>&1 || { printf 'state=failed\nexit_code=127\n' > "§dir/status"; exit 0; }
                setsid sh "§dir/command.sh" >"§dir/stdout.log" 2>"§dir/stderr.log" </dev/null &
                pid=§!
                pgid=""
                start_ticks=""
                n=0
                # A short command can exit before /proc/§pid/stat is observable. Give the
                # process a bounded hand-off window before deciding whether it is terminal.
                while [ "§n" -lt 40 ]; do
                  pgid="§(current_pgid "§pid")"
                  start_ticks="§(start_ticks "§pid")"
                  if [ -n "§pgid" ] && [ -n "§start_ticks" ]; then break; fi
                  if ! kill -0 "§pid" 2>/dev/null; then break; fi
                  sleep 0.01
                  n=§((n+1))
                done
                if [ -n "§pgid" ] && [ -n "§start_ticks" ]; then
                  case "§pid:§pgid:§start_ticks" in *[!0-9:]*) fail identity_invalid;; esac
                  printf 'pid=%s\npgid=%s\nstart_ticks=%s\n' "§pid" "§pgid" "§start_ticks" > "§dir/identity.tmp"
                  mv -f "§dir/identity.tmp" "§dir/identity"
                  rm -f "§dir/command.sh"
                  printf 'state=running\n' > "§dir/status"
                  wait "§pid"; code=§?
                  printf 'state=exited\nexit_code=%s\n' "§code" > "§dir/status"
                else
                  # If metadata never appeared while the process was alive, do not leave an
                  # unmanaged command behind. A terminal command has no real start tick, so use
                  # a numeric sentinel rather than the exit code (which is not process identity).
                  if kill -0 "§pid" 2>/dev/null; then
                    kill -TERM "§pid" 2>/dev/null || true
                    wait "§pid"; code=§?
                    printf 'state=failed\nexit_code=%s\n' "§code" > "§dir/status"
                  else
                    wait "§pid"; code=§?
                    printf 'state=exited\nexit_code=%s\n' "§code" > "§dir/status"
                  fi
                  fallback_pgid="§{pgid:-0}"
                  case "§fallback_pgid" in *[!0-9]*|'') fallback_pgid=0;; esac
                  fallback_start_ticks="0"
                  printf 'pid=%s\npgid=%s\nstart_ticks=%s\n' "§pid" "§fallback_pgid" "§fallback_start_ticks" > "§dir/identity.tmp"
                  mv -f "§dir/identity.tmp" "§dir/identity"
                  rm -f "§dir/command.sh"
                fi
                ;;
              status)
                verify_token
                load_identity
                if verify_identity; then verified=1; else verified=0; fi
                printf 'ok=1\n'; cat "§dir/identity"
                if [ -f "§dir/status" ]; then cat "§dir/status"; else printf 'state=unknown\n'; fi
                printf 'identity_verified=%s\n' "§verified"
                ;;
              stop)
                force="§{4:-0}"
                verify_token
                load_identity
                if verify_identity; then
                  printf 'state=stop_requested\n' > "§dir/status"
                  if [ "§force" = 1 ]; then kill -KILL -- "-§pgid" 2>/dev/null || true
                  else kill -TERM -- "-§pgid" 2>/dev/null || true
                  fi
                  sleep 0.1
                fi
                if verify_identity; then verified=1; else verified=0; fi
                if [ "§verified" = 0 ]; then printf 'state=stopped\n' > "§dir/status"; fi
                printf 'ok=1\n'; cat "§dir/identity"; cat "§dir/status"
                printf 'identity_verified=%s\n' "§verified"
                ;;
              logs)
                stream="§{4:-stdout}"; limit="§{5:-32768}"
                verify_token
                case "§limit" in ''|*[!0-9]*) fail invalid_tail;; esac
                [ "§limit" -ge 1 ] && [ "§limit" -le 262144 ] || fail invalid_tail
                case "§stream" in stdout) file="§dir/stdout.log";; stderr) file="§dir/stderr.log";; *) fail invalid_stream;; esac
                size=0; [ -f "§file" ] && size="§(wc -c < "§file" | tr -d ' ')"
                truncated=0; [ "§size" -gt "§limit" ] && truncated=1
                printf 'ok=1\ntruncated=%s\n--body--\n' "§truncated"
                [ -f "§file" ] && tail -c "§limit" "§file"
                ;;
              *) fail invalid_mode;;
            esac
        """.trimIndent().replace('§', '$') + "\n"

        internal val SCRIPT_SHA256: String = MessageDigest.getInstance("SHA-256")
            .digest(SUPERVISOR_SCRIPT.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
