package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceMountResolverTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `bind paths resolve to the same host directories used by proot`() {
        val files = temporaryFolder.newFolder("files")
        val linux = temporaryFolder.newFolder("linux")
        val skills = temporaryFolder.newFolder("skills")
        val outputs = temporaryFolder.newFolder("outputs")
        val resolver = WorkspaceMountResolver(
            extraBindMounts = listOf(
                WorkspaceBindMount(skills, "/skills"),
                WorkspaceBindMount(outputs, "/tool_outputs"),
            ),
        )

        assertEquals(files.resolve("notes/a.txt").canonicalFile, resolver.resolve(files, linux, "/workspace/notes/a.txt", false))
        assertEquals(skills.resolve("demo/SKILL.md").canonicalFile, resolver.resolve(files, linux, "/skills/demo/SKILL.md", false))
        assertEquals(outputs.resolve("run.json").canonicalFile, resolver.resolve(files, linux, "/tool_outputs/run.json", false))
        assertEquals(linux.resolve("etc/hosts").canonicalFile, resolver.resolve(files, linux, "/etc/hosts", false))
    }

    @Test
    fun `shared storage is resolvable only for an authorized invocation`() {
        val files = temporaryFolder.newFolder("files-shared")
        val linux = temporaryFolder.newFolder("linux-shared")
        val shared = temporaryFolder.newFolder("shared")
        val resolver = WorkspaceMountResolver(
            sharedStorageBindMount = WorkspaceBindMount(shared, "/sdcard"),
        )

        assertEquals(
            shared.resolve("Download/a.txt").canonicalFile,
            resolver.resolve(files, linux, "/sdcard/Download/a.txt", true),
        )
        assertEquals(
            linux.resolve("sdcard/Download/a.txt").canonicalFile,
            resolver.resolve(files, linux, "/sdcard/Download/a.txt", false),
        )
    }

    @Test
    fun `virtual and escaping paths are rejected`() {
        val files = temporaryFolder.newFolder("files-unsafe")
        val linux = temporaryFolder.newFolder("linux-unsafe")
        val resolver = WorkspaceMountResolver()

        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(files, linux, "/proc/self/status", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolve(files, linux, "/workspace/../private.txt", false)
        }
    }
}
