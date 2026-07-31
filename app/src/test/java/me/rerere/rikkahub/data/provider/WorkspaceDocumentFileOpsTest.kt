package me.rerere.rikkahub.data.provider

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDocumentFileOpsTest {
    private val temp = Files.createTempDirectory("workspace-document-ops").toFile()

    @After
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `copy verifies a complete directory without deleting the source`() {
        val sourceBase = File(temp, "source").apply { mkdirs() }
        val destinationBase = File(temp, "destination").apply { mkdirs() }
        val source = File(sourceBase, "folder").apply {
            mkdirs()
            File(this, "a.txt").writeText("alpha")
            File(this, "nested").mkdirs()
            File(this, "nested/b.txt").writeText("beta")
        }

        val copied = WorkspaceDocumentFileOps.copyVerified(
            source,
            sourceBase,
            File(destinationBase, "folder"),
            destinationBase,
        )

        assertTrue(source.exists())
        assertEquals("alpha", File(copied, "a.txt").readText())
        assertEquals("beta", File(copied, "nested/b.txt").readText())
    }

    @Test
    fun `cross Workspace move publishes the copy before removing the source`() {
        val sourceBase = File(temp, "source").apply { mkdirs() }
        val destinationBase = File(temp, "destination").apply { mkdirs() }
        val source = File(sourceBase, "voice.wav").apply { writeBytes(ByteArray(512) { it.toByte() }) }

        val moved = WorkspaceDocumentFileOps.moveVerified(
            source,
            sourceBase,
            File(destinationBase, source.name),
            destinationBase,
        )

        assertFalse(source.exists())
        assertTrue(moved.exists())
        assertEquals(512, moved.length())
        assertTrue(sourceBase.listFiles().orEmpty().none { it.name.startsWith(".l2s.") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `copy rejects a destination outside the declared Workspace`() {
        val sourceBase = File(temp, "source").apply { mkdirs() }
        val destinationBase = File(temp, "destination").apply { mkdirs() }
        val source = File(sourceBase, "a.txt").apply { writeText("a") }

        WorkspaceDocumentFileOps.copyVerified(
            source,
            sourceBase,
            File(temp, "escaped.txt"),
            destinationBase,
        )
    }
}
