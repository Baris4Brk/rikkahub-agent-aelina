package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceProcessPersistenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `definition round trips through atomic persistence`() {
        val manager = WorkspaceManager(temporaryFolder.newFolder("workspaces"))
        val persistence = WorkspaceProcessPersistence(manager)
        manager.ensureWorkspace("root-a")
        val definition = definition(id = "wp_12345678", workspaceId = "workspace-a")

        persistence.write("root-a", definition)

        assertEquals(definition, persistence.read("root-a", definition.id))
        assertFalse(
            persistence.processDirectory("root-a", definition.id)
                .listFiles().orEmpty().any { it.name.contains(".tmp-") },
        )
    }

    @Test
    fun `corrupt definition is quarantined without crashing scan`() {
        val manager = WorkspaceManager(temporaryFolder.newFolder("workspaces"))
        val persistence = WorkspaceProcessPersistence(manager, clockMillis = { 42L })
        manager.ensureWorkspace("root-a")
        val file = persistence.definitionFile("root-a", "wp_12345678")
        file.parentFile?.mkdirs()
        file.writeText("{broken")

        assertNull(persistence.read("root-a", "wp_12345678"))
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "definition.json.corrupt-42").isFile)
    }

    @Test
    fun `scan keeps workspace definitions separated`() {
        val manager = WorkspaceManager(temporaryFolder.newFolder("workspaces"))
        val persistence = WorkspaceProcessPersistence(manager)
        manager.ensureWorkspace("root-a")
        manager.ensureWorkspace("root-b")
        persistence.write("root-a", definition("wp_12345678", "workspace-a"))
        persistence.write("root-b", definition("wp_abcdefgh", "workspace-b"))

        val stored = persistence.scan(
            mapOf(
                "workspace-a" to "root-a",
                "workspace-b" to "root-b",
            ),
        )

        assertEquals(setOf("workspace-a", "workspace-b"), stored.map { it.definition.workspaceId }.toSet())
    }

    private fun definition(id: String, workspaceId: String) = WorkspaceProcessDefinition(
        id = id,
        workspaceId = workspaceId,
        name = "server",
        command = "python3 server.py",
        createdAt = 1L,
    )
}
