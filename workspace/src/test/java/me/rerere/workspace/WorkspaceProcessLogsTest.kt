package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceProcessLogsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writer rotates without exceeding per-file limit`() {
        val file = temporaryFolder.newFile("stdout.log")
        RotatingLogWriter(file, maxFileBytes = 8).use { writer ->
            val bytes = "abcdefghijklmnopqrst".toByteArray()
            writer.write(bytes, 0, bytes.size)
        }

        val backup = java.io.File(file.parentFile, "stdout.log.1")
        assertTrue(file.length() <= 8)
        assertTrue(backup.length() <= 8)
        assertEquals("qrst", file.readText())
        assertEquals("ijklmnop", backup.readText())
    }

    @Test
    fun `both log streams share one return budget`() {
        val stdout = temporaryFolder.newFile("stdout.log").apply { writeText("12345678") }
        val stderr = temporaryFolder.newFile("stderr.log").apply { writeText("abcdefgh") }

        val logs = readWorkspaceProcessLogs(stdout, stderr, WorkspaceProcessLogStream.BOTH, 8)

        assertEquals("5678", logs.stdout)
        assertEquals("efgh", logs.stderr)
        assertTrue(logs.truncated)
    }

    @Test
    fun `short requested stream is not marked truncated`() {
        val stdout = temporaryFolder.newFile("stdout.log").apply { writeText("ok") }
        val stderr = temporaryFolder.newFile("stderr.log")

        val logs = readWorkspaceProcessLogs(stdout, stderr, WorkspaceProcessLogStream.STDOUT, 16)

        assertEquals("ok", logs.stdout)
        assertFalse(logs.truncated)
    }
}
