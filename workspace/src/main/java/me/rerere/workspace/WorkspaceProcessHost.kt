package me.rerere.workspace

interface WorkspaceProcessHost {
    fun ensureForegroundHost(): Result<Unit>
    fun stopForegroundHost()
}

object NoOpWorkspaceProcessHost : WorkspaceProcessHost {
    override fun ensureForegroundHost(): Result<Unit> = Result.success(Unit)
    override fun stopForegroundHost() = Unit
}
