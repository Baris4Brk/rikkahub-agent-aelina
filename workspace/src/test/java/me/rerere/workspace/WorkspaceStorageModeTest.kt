package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceStorageModeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `shared mode resolves files outside private metadata and survives recreation`() {
        val privateRoot = temp.newFolder("private")
        val sharedRoot = temp.newFolder("shared")
        val first = WorkspaceManager(privateRoot, sharedRoot)
        first.ensureWorkspace("workspace-1", WorkspaceStorageMode.SHARED)
        first.writeText("workspace-1", "hello.txt", "same bytes")

        val recreated = WorkspaceManager(privateRoot, sharedRoot)
        assertEquals(WorkspaceStorageMode.SHARED, recreated.storageMode("workspace-1"))
        assertEquals("same bytes", recreated.readText("workspace-1", "hello.txt"))
        assertTrue(recreated.filesDir("workspace-1").canonicalPath.startsWith(sharedRoot.canonicalPath))
        assertTrue(recreated.linuxDir("workspace-1").canonicalPath.startsWith(privateRoot.canonicalPath))
    }

    @Test
    fun `existing workspace without marker remains private`() {
        val privateRoot = temp.newFolder("legacy-private")
        val manager = WorkspaceManager(privateRoot, temp.newFolder("legacy-shared"))
        assertEquals(WorkspaceStorageMode.PRIVATE, manager.storageMode("legacy"))
        manager.ensureWorkspace("legacy")
        assertTrue(manager.filesDir("legacy").canonicalPath.startsWith(privateRoot.canonicalPath))
    }
}
