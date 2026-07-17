package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
