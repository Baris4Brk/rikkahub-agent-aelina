package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotManagedProcessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `managed command retains kill on exit and full rootfs cwd`() {
        val runner = ProotShellRunner(temporaryFolder.root)
        val command = runner.buildCommand(
            proot = temporaryFolder.newFile("proot"),
            filesDir = temporaryFolder.newFolder("files"),
            linuxDir = temporaryFolder.newFolder("linux"),
            cwd = runner.managedProotCwd("/root/project"),
            commandText = "python3 server.py",
        )

        assertTrue("--kill-on-exit" in command)
        assertEquals("/root/project", command[command.indexOf("-w") + 1])
        assertEquals("python3 server.py", command.last())
    }

    @Test
    fun `relative managed cwd stays under workspace`() {
        val runner = ProotShellRunner(temporaryFolder.root)
        assertEquals("/workspace/project", runner.managedProotCwd("project"))
        assertEquals("/workspace", runner.managedProotCwd(""))
    }

    @Test
    fun `shared storage mount is present only for an authorized command`() {
        val shared = temporaryFolder.newFolder("shared-storage")
        val runner = ProotShellRunner(
            nativeLibraryDir = temporaryFolder.root,
            sharedStorageBindMount = WorkspaceBindMount(shared, "/sdcard"),
        )
        fun command(allowed: Boolean) = runner.buildCommand(
            proot = temporaryFolder.newFile("proot-$allowed"),
            filesDir = temporaryFolder.newFolder("files-$allowed"),
            linuxDir = temporaryFolder.newFolder("linux-$allowed"),
            cwd = "/workspace",
            commandText = "pwd",
            allowSharedStorage = allowed,
        )

        assertFalse(command(false).any { it.endsWith(":/sdcard") })
        assertTrue(command(true).any { it.endsWith(":/sdcard") })
    }
}
