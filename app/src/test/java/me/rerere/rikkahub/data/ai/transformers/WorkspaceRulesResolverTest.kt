package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRulesResolverTest {
    @Test
    fun `cache invalidates on metadata change`() = runBlocking {
        val source = MutableWorkspaceRulesSource().apply {
            put("/workspace/RULES.md", "first", modifiedAtMs = 1L)
        }
        val resolver = WorkspaceRulesResolver(source)

        resolver.resolve("workspace-1", null)
        resolver.resolve("workspace-1", null)
        assertEquals(1, source.readCount("/workspace/RULES.md"))

        source.put("/workspace/RULES.md", "second", modifiedAtMs = 2L)
        val changed = resolver.resolve("workspace-1", null)
        assertEquals(2, source.readCount("/workspace/RULES.md"))
        assertEquals("second", changed.rules.single().content)
    }

    @Test
    fun `invalid UTF8 and traversal are ignored while high priority rules keep bounded space`() =
        runBlocking {
            val source = MutableWorkspaceRulesSource().apply {
                putBytes("/workspace/AGENTS.md", byteArrayOf(0xC3.toByte(), 0x28), 1L)
                put("/workspace/RULES.md", "L".repeat(20_000), 1L)
                put("/workspace/.rikkahub/AGENTS.md", "H".repeat(20_000), 1L)
            }
            val snapshot = WorkspaceRulesResolver(source).resolve(
                workspaceId = "workspace-1",
                cwd = "/workspace/../../outside",
            )

            assertEquals(
                listOf("/workspace/RULES.md", "/workspace/.rikkahub/AGENTS.md"),
                snapshot.rules.map { it.source },
            )
            assertTrue(snapshot.rules.all { it.content.length <= WORKSPACE_RULE_FILE_MAX_CHARS })
            assertTrue(snapshot.rules.all { it.truncated })
            assertTrue(snapshot.totalContentChars <= WORKSPACE_RULE_TOTAL_MAX_CHARS)
            assertTrue(source.statPaths.none { ".." in it })
        }

    @Test
    fun `rules merge from low to high priority and render as escaped XML`() = runBlocking {
        val source = FakeWorkspaceRulesSource(
            mapOf(
                "/workspace/RULES.md" to "root & <safe>",
                "/workspace/AGENTS.md" to "root agents",
                "/workspace/project/AGENTS.md" to "project agents",
                "/workspace/project/sub/AGENTS.md" to "nearest agents",
                "/workspace/.rikkahub/AGENTS.md" to "workspace override",
            ),
        )
        val resolver = WorkspaceRulesResolver(source)

        val snapshot = resolver.resolve(
            workspaceId = "workspace-1",
            cwd = "/workspace/project/sub",
        )

        assertEquals(
            listOf(
                "/workspace/RULES.md",
                "/workspace/AGENTS.md",
                "/workspace/project/AGENTS.md",
                "/workspace/project/sub/AGENTS.md",
                "/workspace/.rikkahub/AGENTS.md",
            ),
            snapshot.rules.map { it.source },
        )
        assertTrue(snapshot.toPrompt().contains("root &amp; &lt;safe&gt;"))
    }

    private class FakeWorkspaceRulesSource(
        files: Map<String, String>,
    ) : WorkspaceRulesFileSource {
        private val entries = files.mapValues { (_, content) ->
            content.toByteArray(Charsets.UTF_8)
        }

        override suspend fun stat(
            workspaceId: String,
            path: String,
        ): WorkspaceRuleFileMetadata? = entries[path]?.let { bytes ->
            WorkspaceRuleFileMetadata(sizeBytes = bytes.size.toLong(), modifiedAtMs = 1L)
        }

        override suspend fun read(
            workspaceId: String,
            path: String,
            maxBytes: Int,
        ): ByteArray? = entries[path]?.take(maxBytes)?.toByteArray()
    }

    private class MutableWorkspaceRulesSource : WorkspaceRulesFileSource {
        private data class Entry(val bytes: ByteArray, val modifiedAtMs: Long)

        private val entries = linkedMapOf<String, Entry>()
        private val reads = linkedMapOf<String, Int>()
        val statPaths = mutableListOf<String>()

        fun put(path: String, content: String, modifiedAtMs: Long) {
            putBytes(path, content.toByteArray(Charsets.UTF_8), modifiedAtMs)
        }

        fun putBytes(path: String, bytes: ByteArray, modifiedAtMs: Long) {
            entries[path] = Entry(bytes, modifiedAtMs)
        }

        fun readCount(path: String): Int = reads[path] ?: 0

        override suspend fun stat(
            workspaceId: String,
            path: String,
        ): WorkspaceRuleFileMetadata? {
            statPaths += path
            return entries[path]?.let {
                WorkspaceRuleFileMetadata(it.bytes.size.toLong(), it.modifiedAtMs)
            }
        }

        override suspend fun read(
            workspaceId: String,
            path: String,
            maxBytes: Int,
        ): ByteArray? {
            reads[path] = readCount(path) + 1
            return entries[path]?.bytes?.copyOfRange(
                0,
                minOf(maxBytes, entries.getValue(path).bytes.size),
            )
        }
    }
}
