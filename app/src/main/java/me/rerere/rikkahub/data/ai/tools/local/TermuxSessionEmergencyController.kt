package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context

data class TermuxStopAllResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val requestedCount: Int,
    val stoppedCount: Int,
    val remainingSessionIds: List<String> = emptyList(),
)

interface TermuxSessionEmergencyController {
    suspend fun stopAllAgentSessions(): TermuxStopAllResult
    suspend fun stopOwnedSessions(sessionPrefix: String): TermuxStopAllResult
}

class AndroidTermuxSessionEmergencyController(
    private val context: Context,
) : TermuxSessionEmergencyController {
    override suspend fun stopAllAgentSessions(): TermuxStopAllResult {
        return stopMatchingSessions { true }
    }

    override suspend fun stopOwnedSessions(sessionPrefix: String): TermuxStopAllResult {
        require(sessionPrefix.startsWith("rk_su_")) { "Invalid owned-session prefix" }
        return stopMatchingSessions { it.startsWith(sessionPrefix) }
    }

    private suspend fun stopMatchingSessions(matches: (String) -> Boolean): TermuxStopAllResult {
        return when (TermuxIntegration.state(context)) {
            TermuxIntegration.State.NOT_INSTALLED -> TermuxStopAllResult(
                ok = true,
                code = "TERMUX_NOT_INSTALLED",
                message = "Termux is not installed; no agent sessions exist.",
                requestedCount = 0,
                stoppedCount = 0,
            )
            TermuxIntegration.State.NO_PERMISSION -> TermuxStopAllResult(
                ok = false,
                code = "TERMUX_PERMISSION_DENIED",
                message = "RUN_COMMAND permission is unavailable; session termination cannot be confirmed.",
                requestedCount = 0,
                stoppedCount = 0,
            )
            TermuxIntegration.State.READY -> stopAgentTermuxSessions(
                listSessions = {
                    when (val result = tmux(context, TmuxOps.listArgv())) {
                        is CaptureResult.Success -> parseSessions(result.stdout).map { it.name }.filter(matches)
                        is CaptureResult.OtherError -> if (isSessionNotFound(result.message)) emptyList() else error(result.message)
                        CaptureResult.Denied -> error("Termux RUN_COMMAND permission denied")
                        CaptureResult.Timeout -> error("Timed out listing Termux sessions")
                    }
                },
                killSession = { sessionId ->
                    when (val result = tmux(context, TmuxOps.killArgv(sessionId))) {
                        is CaptureResult.Success -> true
                        is CaptureResult.OtherError -> isSessionNotFound(result.message)
                        CaptureResult.Denied,
                        CaptureResult.Timeout -> false
                    }
                },
            )
        }
    }
}

internal suspend fun stopAgentTermuxSessions(
    listSessions: suspend () -> List<String>,
    killSession: suspend (String) -> Boolean,
): TermuxStopAllResult {
    val before = listSessions().distinct()
    if (before.isEmpty()) {
        return TermuxStopAllResult(
            ok = true,
            code = "TERMUX_SESSIONS_STOPPED",
            message = "No agent Termux sessions were running.",
            requestedCount = 0,
            stoppedCount = 0,
        )
    }
    before.forEach { killSession(it) }
    val remaining = listSessions().filter { it in before }.distinct()
    val stopped = before.size - remaining.size
    return TermuxStopAllResult(
        ok = remaining.isEmpty(),
        code = if (remaining.isEmpty()) "TERMUX_SESSIONS_STOPPED" else "TERMUX_TERMINATION_UNKNOWN",
        message = if (remaining.isEmpty()) {
            "Stopped $stopped agent Termux session(s)."
        } else {
            "Could not confirm termination of ${remaining.size} Termux session(s)."
        },
        requestedCount = before.size,
        stoppedCount = stopped,
        remainingSessionIds = remaining,
    )
}
