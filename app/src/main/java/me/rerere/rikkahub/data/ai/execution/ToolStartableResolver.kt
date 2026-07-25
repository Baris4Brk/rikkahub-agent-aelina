package me.rerere.rikkahub.data.ai.execution

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.execution.SshManagedStartableFactory
import me.rerere.rikkahub.execution.TermuxManagedStartableFactory
import me.rerere.rikkahub.execution.LinuxManagedStartableFactory

/** Resolves the cancellable adapter for the exact tool definition exposed to a caller. */
fun interface ToolStartableResolver {
    fun resolve(tool: Tool, context: ToolExecutionContext): StartableTool?

    companion object {
        val NONE = ToolStartableResolver { _, _ -> null }
    }
}

class DefaultToolStartableResolver(
    private val termuxFactory: TermuxManagedStartableFactory,
    private val sshFactory: SshManagedStartableFactory,
    private val linuxFactory: LinuxManagedStartableFactory,
) : ToolStartableResolver {
    override fun resolve(tool: Tool, context: ToolExecutionContext): StartableTool? = when (tool.name) {
        "termux_run_command" -> termuxFactory.create(tool)
        "linux_run" -> linuxFactory.create(tool)
        "ssh_exec" -> sshFactory.createInline(tool)
        "ssh_exec_saved" -> sshFactory.createSaved(tool)
        else -> null
    }
}
