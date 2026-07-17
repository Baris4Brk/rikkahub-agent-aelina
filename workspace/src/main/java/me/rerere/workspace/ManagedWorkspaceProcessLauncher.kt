package me.rerere.workspace

import java.io.File

data class ManagedWorkspaceProcessContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
)

interface ManagedWorkspaceProcessLauncher {
    fun startManagedProcess(context: ManagedWorkspaceProcessContext): Process
}
