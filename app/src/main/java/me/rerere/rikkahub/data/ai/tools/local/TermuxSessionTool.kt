package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.UUID

internal const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
internal const val TERMUX_HOME = "/data/data/com.termux/files/home"

private const val DEFAULT_COLS = 200
private const val DEFAULT_ROWS = 50
private const val DEFAULT_READ_LINES = 200
private const val DEFAULT_TIMEOUT_S = 20
private const val MAX_TIMEOUT_S = 600
private const val SETTLE_MS = 600L
private const val POLL_INTERVAL_MS = 200L
private const val MAX_SESSIONS = 8
private const val TMUX_OP_TIMEOUT_MS = 8_000L
private const val INSTALL_TIMEOUT_MS = 180_000L
// Idle sessions are never explicitly killed by the model, so they would otherwise pin the
// MAX_SESSIONS budget forever. Reap any rk_ session whose tmux session_activity is older than
// this before enforcing the slot cap. 6h is long enough to leave a genuinely in-use shell
// (ssh, a REPL, a watched build) alone while clearing forgotten ones.
private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000

/** Builds the argv passed to the tmux executable for each session operation. Pure. */
internal object TmuxOps {
    fun sessionName(userName: String?): String {
        val suffix = userName?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (suffix.isNullOrBlank()) "rk_$id" else "rk_${suffix}_$id"
    }

    fun startArgv(session: String, cols: Int, rows: Int): Array<String> =
        arrayOf("new-session", "-d", "-s", session, "-x", cols.toString(), "-y", rows.toString())

    // -l sends the text literally (no tmux key-name interpretation); -- ends option parsing.
    fun sendTextArgv(session: String, text: String): Array<String> =
        arrayOf("send-keys", "-t", session, "-l", "--", text)

    // Each element is a tmux key name (e.g. "C-c", "Enter", "Up", "Tab").
    fun sendKeysArgv(session: String, keys: List<String>): Array<String> =
        (listOf("send-keys", "-t", session) + keys).toTypedArray()

    fun enterArgv(session: String): Array<String> =
        arrayOf("send-keys", "-t", session, "Enter")

    fun capturePaneArgv(session: String, lines: Int): Array<String> =
        arrayOf("capture-pane", "-t", session, "-p", "-S", "-${lines.coerceAtLeast(0)}")

    fun killArgv(session: String): Array<String> =
        arrayOf("kill-session", "-t", session)

    fun listArgv(): Array<String> =
        arrayOf("list-sessions", "-F", "#{session_name}\t#{session_created}\t#{session_activity}")
}

internal data class PaneSample(val elapsedMs: Long, val content: String)

internal sealed interface PollResult {
    data object Continue : PollResult
    data class Done(val reason: Reason, val content: String) : PollResult
    enum class Reason { SETTLED, MATCHED, TIMEOUT }
}

/** Regex match with substring fallback when the pattern is not a valid regex. */
internal fun waitForMatches(pane: String, pattern: String): Boolean {
    if (pattern.isEmpty()) return false
    val rx = runCatching { Regex(pattern) }.getOrNull()
    return if (rx != null) rx.containsMatchIn(pane) else pane.contains(pattern)
}

/**
 * Decide whether the polling loop should stop, given every pane snapshot taken so far
 * (chronological, each with its elapsed time since the send). Order of precedence:
 * wait_for match, then timeout. Screen settling is only a completion signal when no wait_for was
 * requested: long-running installers routinely keep the pane unchanged for seconds or minutes,
 * so treating a quiet pane as completion would turn a requested 180s wait into a ~600ms read.
 */
internal fun evaluatePoll(
    samples: List<PaneSample>,
    settleMs: Long,
    timeoutMs: Long,
    waitFor: String?,
): PollResult {
    val cur = samples.lastOrNull() ?: return PollResult.Continue
    if (!waitFor.isNullOrEmpty() && waitForMatches(cur.content, waitFor)) {
        return PollResult.Done(PollResult.Reason.MATCHED, cur.content)
    }
    if (waitFor.isNullOrEmpty()) {
        var stableSince = cur.elapsedMs
        for (i in samples.indices.reversed()) {
            if (samples[i].content == cur.content) stableSince = samples[i].elapsedMs else break
        }
        if (samples.size >= 2 && cur.elapsedMs - stableSince >= settleMs) {
            return PollResult.Done(PollResult.Reason.SETTLED, cur.content)
        }
    }
    if (cur.elapsedMs >= timeoutMs) {
        return PollResult.Done(PollResult.Reason.TIMEOUT, cur.content)
    }
    return PollResult.Continue
}

internal data class TmuxSessionInfo(val name: String, val created: Long, val lastActivity: Long)

internal fun parseSessions(stdout: String, prefix: String = "rk_"): List<TmuxSessionInfo> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[0].startsWith(prefix)) return@mapNotNull null
            TmuxSessionInfo(
                name = parts[0],
                created = parts[1].toLongOrNull() ?: 0L,
                lastActivity = parts[2].toLongOrNull() ?: 0L,
            )
        }.toList()

/** Sessions whose last tmux activity is older than [ttlMs] relative to [nowEpochSecs]. Pure. */
internal fun staleSessionsToReap(
    sessions: List<TmuxSessionInfo>,
    nowEpochSecs: Long,
    ttlMs: Long,
): List<TmuxSessionInfo> {
    val cutoffSecs = nowEpochSecs - ttlMs / 1000
    // session_activity is epoch seconds; treat a 0/unparsed activity as not-stale so a session
    // with a malformed timestamp is never reaped out from under an active user.
    return sessions.filter { it.lastActivity in 1 until cutoffSecs }
}

internal fun isSessionNotFound(stderr: String): Boolean {
    val s = stderr.lowercase()
    return s.contains("can't find session") ||
        s.contains("no server running") ||
        s.contains("session not found") ||
        s.contains("no current session")
}

internal suspend fun tmux(context: Context, argv: Array<String>, timeoutMs: Long = TMUX_OP_TIMEOUT_MS): CaptureResult =
    runCommandCapture(context, "$TERMUX_BIN/tmux", argv, TERMUX_HOME, timeoutMs)

/** Ensure tmux is installed; auto-install on first use. Returns null on success, an error string otherwise. */
private suspend fun ensureTmux(context: Context): String? {
    val check = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    if (check is CaptureResult.Success && check.stdout.isNotBlank()) return null
    // The install can run for the full INSTALL_TIMEOUT_MS (~180s). Keep the bound but surface
    // each outcome distinctly instead of silently blocking ~3 min and then reporting a generic
    // failure: a Denied means the permission path, a Timeout means the install is still going
    // (network / large download) so the caller can tell the user to retry shortly.
    val install = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", "pkg install -y tmux"), TERMUX_HOME, INSTALL_TIMEOUT_MS)
    if (install is CaptureResult.Denied) return "termux_permission_denied"
    if (install is CaptureResult.Timeout) return "tmux_installing"
    val recheck = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    return if (recheck is CaptureResult.Success && recheck.stdout.isNotBlank()) null else "tmux_install_failed"
}

private fun resolveTimeoutMs(input: JsonElement): Long {
    val raw = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
    val secs = when {
        raw == null || raw == 0 -> DEFAULT_TIMEOUT_S
        else -> raw.coerceIn(1, MAX_TIMEOUT_S)
    }
    return secs.toLong() * 1000
}

/**
 * UTF-8 byte width of the Unicode code point [cp]. Used to budget truncation by bytes while
 * iterating code points (NOT chars): an astral char (emoji, some CJK) is a surrogate PAIR of
 * two Java chars but a single 4-byte UTF-8 sequence. Measuring per-char would count each
 * surrogate half separately — overshooting the budget ~2x and letting a cut fall between the
 * two halves, corrupting the very emoji this boundary-snapping is meant to protect.
 */
private fun utf8Width(cp: Int): Int = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
}

/**
 * Keep at most [maxBytes] of UTF-8 from the end of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split, which
 * would otherwise corrupt CJK / emoji output. Returns the whole string when it already fits.
 * Pure.
 */
internal fun takeLastUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    // Walk back from the end one code point at a time, counting its true UTF-8 width, until
    // adding one more would exceed the budget; the surviving slice is byte-bounded and aligned.
    var bytes = 0
    var i = s.length
    while (i > 0) {
        val cp = s.codePointBefore(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i -= Character.charCount(cp)
    }
    return s.substring(i)
}

/**
 * Keep at most [maxBytes] of UTF-8 from the start of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split. Returns
 * the whole string when it already fits. Pure. Counterpart to [takeLastUtf8Bytes] for
 * head-keeping truncation.
 */
internal fun takeFirstUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i += Character.charCount(cp)
    }
    return s.substring(0, i)
}

private fun truncateOut(s: String): String {
    // capture-pane emits the full terminal height, so the screen arrives padded with a wall
    // of blank lines below the cursor. Drop trailing blank lines so each read does not burn
    // tokens on empty padding.
    val trimmed = s.trimEnd('\n', ' ', '\t')
    val max = TermuxRuntime.maxStdoutBytes
    // Bound on UTF-8 bytes, not chars: maxStdoutBytes is a byte budget, and a char-count cut
    // would over- or under-shoot for multibyte text and could split a code point.
    return if (trimmed.toByteArray(Charsets.UTF_8).size > max) {
        takeLastUtf8Bytes(trimmed, max) + "\n…[older scrollback truncated]"
    } else {
        trimmed
    }
}

private fun reasonTag(r: PollResult.Reason): String = when (r) {
    PollResult.Reason.MATCHED -> "MATCHED"
    PollResult.Reason.SETTLED -> "SETTLED"
    PollResult.Reason.TIMEOUT -> "TIMEOUT"
}

private data class SessionReadOutcome(
    val capture: CaptureResult,
    val reason: PollResult.Reason?,
    val elapsedMs: Long,
)

private const val COMMAND_COMPLETION_PREFIX = "__RIKKAHUB_COMMAND_DONE_"
private val COMMAND_COMPLETION_TOKEN = Regex("[a-f0-9]{32}")

internal data class TrackedTermuxCommand(
    val wireText: String,
    val completionToken: String,
    val waitForPattern: String,
)

internal enum class TrackedTermuxStatus(val wire: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    TIMED_OUT("timed_out"),
    UNKNOWN("unknown"),
}

internal data class TrackedTermuxResult(
    val status: TrackedTermuxStatus,
    val exitCode: Int?,
    val commandFinished: Boolean,
    val commandStillRunning: Boolean,
)

/**
 * Wrap a one-shot shell command with an unambiguous completion marker and real exit code.
 * The marker is split into two shell variables so the complete value does not appear in the
 * terminal's command echo and accidentally satisfy wait_for before the command has run.
 */
internal fun buildTrackedTermuxCommand(
    command: String,
    completionToken: String = UUID.randomUUID().toString().replace("-", ""),
): TrackedTermuxCommand {
    require(COMMAND_COMPLETION_TOKEN.matches(completionToken)) { "Invalid completion token" }
    val markerSuffix = "${completionToken}__"
    val marker = COMMAND_COMPLETION_PREFIX + markerSuffix
    val wireText = buildString {
        append("bash -lc ")
        append(shellSingleQuote(command))
        append("; __rikkahub_rc=\$?; __rikkahub_marker_a=")
        append(shellSingleQuote(COMMAND_COMPLETION_PREFIX))
        append("; __rikkahub_marker_b=")
        append(shellSingleQuote(markerSuffix))
        append("; printf '\\n%s%s:%s\\n' \"\$__rikkahub_marker_a\" \"\$__rikkahub_marker_b\" \"\$__rikkahub_rc\"")
    }
    return TrackedTermuxCommand(
        wireText = wireText,
        completionToken = completionToken,
        waitForPattern = Regex.escape(marker) + ":(-?\\d+)",
    )
}

internal fun parseTrackedTermuxExitCode(screen: String, completionToken: String): Int? {
    if (!COMMAND_COMPLETION_TOKEN.matches(completionToken)) return null
    val marker = COMMAND_COMPLETION_PREFIX + completionToken + "__"
    return Regex(Regex.escape(marker) + ":(-?\\d+)")
        .find(screen)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun classifyTrackedTermuxResult(
    screen: String,
    completionToken: String,
    reason: PollResult.Reason?,
): TrackedTermuxResult {
    val exitCode = parseTrackedTermuxExitCode(screen, completionToken)
    val status = when {
        exitCode == 0 -> TrackedTermuxStatus.SUCCEEDED
        exitCode != null -> TrackedTermuxStatus.FAILED
        reason == PollResult.Reason.TIMEOUT -> TrackedTermuxStatus.TIMED_OUT
        else -> TrackedTermuxStatus.UNKNOWN
    }
    return TrackedTermuxResult(
        status = status,
        exitCode = exitCode,
        commandFinished = exitCode != null,
        commandStillRunning = status == TrackedTermuxStatus.TIMED_OUT,
    )
}

private fun stripTrackedTermuxMarker(screen: String, completionToken: String): String {
    val marker = COMMAND_COMPLETION_PREFIX + completionToken + "__"
    return screen.replace(Regex(Regex.escape(marker) + ":-?\\d+"), "").trimEnd()
}

private fun trackedCommandEnvelope(
    capture: CaptureResult.Success,
    outcome: SessionReadOutcome,
    completionToken: String,
): List<UIMessagePart> {
    val result = classifyTrackedTermuxResult(capture.stdout, completionToken, outcome.reason)
    val timedOut = result.status == TrackedTermuxStatus.TIMED_OUT
    return listOf(UIMessagePart.Text(buildJsonObject {
        put("success", result.status == TrackedTermuxStatus.SUCCEEDED)
        put("status", result.status.wire)
        put("screen", truncateOut(stripTrackedTermuxMarker(capture.stdout, completionToken)))
        put("command_finished", result.commandFinished)
        put("command_still_running", result.commandStillRunning)
        put("matched_wait_for", result.commandFinished)
        put("timed_out", timedOut)
        put("elapsed_ms", outcome.elapsedMs)
        put("completion_token", completionToken)
        result.exitCode?.let { put("exit_code", it) }
        if (timedOut) {
            put(
                "recovery",
                "The full timeout elapsed and the command may still be running. Do not poll immediately. " +
                    "Later call termux_session_read with this completion_token to get its real exit status."
            )
        }
    }.toString()))
}

private fun waitOutcomeEnvelope(
    capture: CaptureResult.Success,
    outcome: SessionReadOutcome,
): List<UIMessagePart> {
    val waitStatus = when (outcome.reason) {
        PollResult.Reason.MATCHED -> "matched"
        PollResult.Reason.TIMEOUT -> "timed_out"
        PollResult.Reason.SETTLED -> "settled"
        null -> "snapshot"
    }
    return listOf(UIMessagePart.Text(buildJsonObject {
        put("success", waitStatus != "timed_out")
        put("status", waitStatus)
        put("screen", truncateOut(capture.stdout))
        put("matched_wait_for", outcome.reason == PollResult.Reason.MATCHED)
        put("timed_out", outcome.reason == PollResult.Reason.TIMEOUT)
        put("settled", outcome.reason == PollResult.Reason.SETTLED)
        put("elapsed_ms", outcome.elapsedMs)
    }.toString()))
}

/**
 * Poll capture-pane until settled / matched / timed out. Encodes the outcome in a
 * [CaptureResult.Success] where stdout is the screen and stderr carries the reason tag
 * (MATCHED / SETTLED / TIMEOUT). A capture failure (e.g. session gone) is returned as-is.
 */
private suspend fun readUntilDone(
    context: Context,
    session: String,
    lines: Int,
    waitFor: String?,
    timeoutMs: Long,
): SessionReadOutcome {
    val start = android.os.SystemClock.elapsedRealtime()
    val samples = ArrayList<PaneSample>()
    while (true) {
        val cap = tmux(context, TmuxOps.capturePaneArgv(session, lines))
        if (cap is CaptureResult.Success) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            samples.add(PaneSample(elapsed, cap.stdout))
            when (val d = evaluatePoll(samples, SETTLE_MS, timeoutMs, waitFor)) {
                is PollResult.Done -> return SessionReadOutcome(
                    capture = CaptureResult.Success(d.content, reasonTag(d.reason), 0),
                    reason = d.reason,
                    elapsedMs = elapsed,
                )
                PollResult.Continue -> {}
            }
        } else {
            return SessionReadOutcome(
                capture = cap,
                reason = null,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - start,
            )
        }
        if (android.os.SystemClock.elapsedRealtime() - start >= timeoutMs) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            return SessionReadOutcome(
                capture = CaptureResult.Success(samples.lastOrNull()?.content.orEmpty(), "TIMEOUT", 0),
                reason = PollResult.Reason.TIMEOUT,
                elapsedMs = elapsed,
            )
        }
        delay(POLL_INTERVAL_MS)
    }
}

private fun sessionErrorEnvelope(error: String, recovery: String) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", false)
        put("status", "failed")
        put("error", error)
        put("recovery", recovery)
    }.toString())
)

private fun preflight(context: Context): List<UIMessagePart>? =
    when (TermuxIntegration.state(context)) {
        TermuxIntegration.State.NOT_INSTALLED -> sessionErrorEnvelope(
            "termux_not_installed",
            "Install Termux from https://github.com/termux/termux-app/releases ."
        )
        TermuxIntegration.State.NO_PERMISSION -> sessionErrorEnvelope(
            "termux_permission_not_granted",
            "Toggle Termux on in Assistant -> Local tools, or run: adb shell pm grant ${context.packageName} com.termux.permission.RUN_COMMAND"
        )
        TermuxIntegration.State.READY -> null
    }

private suspend fun sessionNotFoundEnvelope(context: Context, session: String): List<UIMessagePart> {
    val live = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
    return sessionErrorEnvelope(
        "session_not_found",
        "Session '$session' is gone (killed or device rebooted). Live sessions: ${live.joinToString { it.name }.ifEmpty { "none" }}. Start a new one with termux_session_start."
    )
}

fun termuxSessionStartTool(context: Context): Tool = Tool(
    name = "termux_session_start",
    description = "Open a persistent, interactive Termux terminal session (tmux-backed, real pty). Use for ssh into a saved host, anything that prompts for a password/sudo, REPLs, or stateful shells. Returns a session_id; drive it with termux_session_send / termux_session_read. Auto-installs tmux on first use. Exchange files with RikkaHub through ~/storage/shared/RikkaHubExchange after termux-setup-storage.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("name", buildJsonObject { put("type", "string"); put("description", "Optional friendly label for the session.") })
            put("command", buildJsonObject { put("type", "string"); put("description", "Optional initial command line to run, e.g. 'ssh myhost'.") })
            put("cols", buildJsonObject { put("type", "integer"); put("description", "Terminal width (default $DEFAULT_COLS).") })
            put("rows", buildJsonObject { put("type", "integer"); put("description", "Terminal height (default $DEFAULT_ROWS).") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        ensureTmux(context)?.let { err ->
            val recovery = if (err == "tmux_installing") {
                "tmux is still installing (download in progress). Wait a moment and call termux_session_start again."
            } else {
                "tmux could not be installed. Open Termux, run 'pkg install tmux', and retry."
            }
            return@Tool sessionErrorEnvelope(err, recovery)
        }
        var live = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
        // Reap idle sessions before enforcing the cap: nothing else kills forgotten sessions,
        // so without this the MAX_SESSIONS budget fills permanently. session_activity is epoch
        // seconds, so compare against wall-clock seconds (not SystemClock.elapsedRealtime).
        val nowSecs = System.currentTimeMillis() / 1000
        val stale = staleSessionsToReap(live, nowSecs, SESSION_TTL_MS)
        if (stale.isNotEmpty()) {
            // Only drop a stale session from the live count if its kill actually succeeded.
            // A failed kill (tmux error, session wedged) leaves the session occupying a slot,
            // so optimistically subtracting it would let live.size dip below the true count and
            // transiently blow past MAX_SESSIONS. isSessionNotFound also counts as reaped: the
            // session is already gone, which is the outcome we wanted.
            val reaped = stale.filter { s ->
                val killed = tmux(context, TmuxOps.killArgv(s.name))
                killed is CaptureResult.Success ||
                    (killed is CaptureResult.OtherError && isSessionNotFound(killed.message))
            }
            live = live - reaped.toSet()
        }
        if (live.size >= MAX_SESSIONS) {
            return@Tool sessionErrorEnvelope("too_many_sessions", "Max $MAX_SESSIONS sessions. Kill one with termux_session_kill first. Live: ${live.joinToString { it.name }}")
        }
        val name = TmuxOps.sessionName(input.jsonObject["name"]?.jsonPrimitive?.contentOrNull)
        val cols = input.jsonObject["cols"]?.jsonPrimitive?.intOrNull ?: DEFAULT_COLS
        val rows = input.jsonObject["rows"]?.jsonPrimitive?.intOrNull ?: DEFAULT_ROWS
        val started = tmux(context, TmuxOps.startArgv(name, cols, rows))
        if (started !is CaptureResult.Success) {
            return@Tool sessionErrorEnvelope("session_start_failed", "tmux new-session failed.")
        }
        val initial = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (!initial.isNullOrBlank()) {
            HardlineCommandGuard.checkCommand(initial)?.let {
                return@Tool sessionErrorEnvelope("blocked_by_safety_floor", it)
            }
            tmux(context, TmuxOps.sendTextArgv(name, initial))
            tmux(context, TmuxOps.enterArgv(name))
        }
        // The session is already created at this point, so a failed screen read (Timeout/
        // Denied/OtherError from readUntilDone) must not crash or report start failure —
        // the model would retry the start and hit too_many_sessions.
        val read = readUntilDone(
            context,
            name,
            DEFAULT_READ_LINES,
            null,
            DEFAULT_TIMEOUT_S * 1000L,
        ).capture as? CaptureResult.Success
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true); put("session_id", name)
            put("screen", read?.let { truncateOut(it.stdout) } ?: "")
            if (read == null) put("note", "Session created, but the initial screen read failed. Use termux_session_read to see the screen.")
        }.toString()))
    }
)

fun termuxSessionSendTool(context: Context): Tool = Tool(
    name = "termux_session_send",
    description = "Type input into a session and read what comes back. For a non-interactive install, build, or deployment command, set wait_for_exit=true: the host waits for the real shell exit code and returns status=succeeded/failed/timed_out before the model continues. A timeout does not kill the command; use the returned completion_token with termux_session_read later and do not poll immediately. For interactive prompts, leave wait_for_exit=false and use wait_for. With wait_for present, an unchanged screen never counts as completion. Set enter=false to type without a newline and use keys for control keys (tmux names: 'C-c', 'Enter', 'Up', 'Tab').",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id from termux_session_start.") })
            put("input", buildJsonObject { put("type", "string"); put("description", "Text to type. Optional if keys is given.") })
            put("enter", buildJsonObject { put("type", "boolean"); put("description", "Press Enter after input. Default true.") })
            put("keys", buildJsonObject { put("type", "array"); put("description", "tmux key names to send (e.g. ['C-c']).") ; put("items", buildJsonObject { put("type", "string") }) })
            put("wait_for", buildJsonObject { put("type", "string"); put("description", "Return as soon as this substring/regex appears on screen.") })
            put("wait_for_exit", buildJsonObject { put("type", "boolean"); put("description", "For a one-shot non-interactive command, wait for its real exit code and return succeeded/failed/timed_out. Do not use for passwords, REPL input, cd, export, ssh, or other stateful/interactive input.") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Default $DEFAULT_TIMEOUT_S, max $MAX_TIMEOUT_S.") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id from termux_session_start.")
        val text = input.jsonObject["input"]?.jsonPrimitive?.contentOrNull
        val enter = input.jsonObject["enter"]?.jsonPrimitive?.booleanOrNull ?: true
        val keys = input.jsonObject["keys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val waitForExit = input.jsonObject["wait_for_exit"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutMs = resolveTimeoutMs(input)
        if (waitForExit && (text.isNullOrBlank() || !enter || keys.isNotEmpty())) {
            return@Tool sessionErrorEnvelope(
                "invalid_exit_tracking_request",
                "wait_for_exit requires one non-empty command, enter=true, and no control keys. " +
                    "Use ordinary wait_for for interactive input."
            )
        }
        val tracked = if (waitForExit) buildTrackedTermuxCommand(text.orEmpty()) else null
        val textToSend = tracked?.wireText ?: text
        val effectiveWaitFor = tracked?.waitForPattern ?: waitFor
        if (!textToSend.isNullOrEmpty()) {
            HardlineCommandGuard.checkCommand(text.orEmpty())?.let {
                return@Tool sessionErrorEnvelope("blocked_by_safety_floor", it)
            }
            val sent = tmux(context, TmuxOps.sendTextArgv(session, textToSend))
            if (sent is CaptureResult.OtherError && isSessionNotFound(sent.message)) {
                return@Tool sessionNotFoundEnvelope(context, session)
            }
        }
        // Check the keys/enter sends for a dead session too. Previously these failures were
        // swallowed and only surfaced indirectly by the later read, so a not-found returned a
        // generic read_failed instead of the actionable session_not_found envelope.
        if (keys.isNotEmpty()) {
            val sentKeys = tmux(context, TmuxOps.sendKeysArgv(session, keys))
            if (sentKeys is CaptureResult.OtherError && isSessionNotFound(sentKeys.message)) {
                return@Tool sessionNotFoundEnvelope(context, session)
            }
        }
        if (enter) {
            val sentEnter = tmux(context, TmuxOps.enterArgv(session))
            if (sentEnter is CaptureResult.OtherError && isSessionNotFound(sentEnter.message)) {
                return@Tool sessionNotFoundEnvelope(context, session)
            }
        }
        val outcome = readUntilDone(context, session, DEFAULT_READ_LINES, effectiveWaitFor, timeoutMs)
        val read = outcome.capture
        if (read is CaptureResult.OtherError && isSessionNotFound(read.message)) {
            return@Tool sessionNotFoundEnvelope(context, session)
        }
        val r = read as? CaptureResult.Success
            ?: return@Tool sessionErrorEnvelope("read_failed", "Input was sent, but the screen read failed. Use termux_session_read to see the result.")
        tracked?.let { trackedCommandEnvelope(r, outcome, it.completionToken) }
            ?: waitOutcomeEnvelope(r, outcome)
    }
)

fun termuxSessionReadTool(context: Context): Tool = Tool(
    name = "termux_session_read",
    description = "Re-read a session without sending input. Pass completion_token returned by a timed-out wait_for_exit command to wait for its real succeeded/failed/timed_out result. Otherwise optional wait_for waits until that pattern or the full timeout; a quiet screen does not complete it. Without either field this returns a snapshot immediately. Do not immediately poll again after a real timeout.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id.") })
            put("wait_for", buildJsonObject { put("type", "string"); put("description", "Optional substring/regex to wait for.") })
            put("completion_token", buildJsonObject { put("type", "string"); put("description", "Token returned by termux_session_send wait_for_exit after a timeout; waits for and parses the real command exit code.") })
            put("timeout_seconds", buildJsonObject { put("type", "integer"); put("description", "Used with wait_for or completion_token. Default $DEFAULT_TIMEOUT_S, max $MAX_TIMEOUT_S.") })
            put("lines", buildJsonObject { put("type", "integer"); put("description", "Scrollback lines (default $DEFAULT_READ_LINES).") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id from termux_session_start.")
        val waitFor = input.jsonObject["wait_for"]?.jsonPrimitive?.contentOrNull
        val completionToken = input.jsonObject["completion_token"]?.jsonPrimitive?.contentOrNull
        if (completionToken != null && !COMMAND_COMPLETION_TOKEN.matches(completionToken)) {
            return@Tool sessionErrorEnvelope(
                "invalid_completion_token",
                "Pass the unmodified completion_token returned by termux_session_send."
            )
        }
        val effectiveWaitFor = completionToken?.let {
            Regex.escape(COMMAND_COMPLETION_PREFIX + it + "__") + ":(-?\\d+)"
        } ?: waitFor
        val lines = input.jsonObject["lines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_READ_LINES
        val outcome = if (effectiveWaitFor.isNullOrEmpty()) {
            val started = android.os.SystemClock.elapsedRealtime()
            SessionReadOutcome(
                capture = tmux(context, TmuxOps.capturePaneArgv(session, lines)),
                reason = null,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
            )
        } else {
            readUntilDone(context, session, lines, effectiveWaitFor, resolveTimeoutMs(input))
        }
        val read = outcome.capture
        if (read is CaptureResult.OtherError && isSessionNotFound(read.message)) {
            return@Tool sessionNotFoundEnvelope(context, session)
        }
        val r = read as? CaptureResult.Success
            ?: return@Tool sessionErrorEnvelope("read_failed", "Could not read session.")
        completionToken?.let { trackedCommandEnvelope(r, outcome, it) }
            ?: waitOutcomeEnvelope(r, outcome)
    }
)

fun termuxSessionKillTool(context: Context): Tool = Tool(
    name = "termux_session_kill",
    description = "End a Termux session opened by termux_session_start.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            put("session_id", buildJsonObject { put("type", "string"); put("description", "Session id to kill.") })
        })
    },
    execute = { input ->
        preflight(context)?.let { return@Tool it }
        val session = input.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool sessionErrorEnvelope("missing_session_id", "Pass session_id.")
        tmux(context, TmuxOps.killArgv(session))
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("killed", session) }.toString()))
    }
)

fun termuxSessionListTool(context: Context): Tool = Tool(
    name = "termux_session_list",
    description = "List live Termux sessions opened by the agent (id, name, last activity).",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = { _ ->
        preflight(context)?.let { return@Tool it }
        val list = (tmux(context, TmuxOps.listArgv()) as? CaptureResult.Success)?.let { parseSessions(it.stdout) } ?: emptyList()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("sessions", buildJsonArray {
                list.forEach { s ->
                    add(buildJsonObject {
                        put("session_id", s.name); put("created", s.created); put("last_activity", s.lastActivity)
                    })
                }
            })
        }.toString()))
    }
)
