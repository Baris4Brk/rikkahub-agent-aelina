package me.rerere.rikkahub.service

import android.content.Context
import androidx.core.content.ContextCompat
import me.rerere.workspace.WorkspaceProcessHost

class AndroidWorkspaceProcessHost(
    private val context: Context,
) : WorkspaceProcessHost {
    override fun ensureForegroundHost(): Result<Unit> = runCatching {
        if (!WorkspaceProcessService.isRunning) {
            ContextCompat.startForegroundService(context, WorkspaceProcessService.startIntent(context))
        } else if (WorkspaceProcessService.isStopRequested) {
            context.startService(WorkspaceProcessService.startIntent(context))
        }
    }

    override fun stopForegroundHost() {
        if (WorkspaceProcessService.isRunning) {
            runCatching { context.startService(WorkspaceProcessService.stopIntent(context)) }
        }
    }
}
