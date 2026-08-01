package me.rerere.rikkahub.tts

import kotlinx.coroutines.runBlocking
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TtsArtifactStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `save and load preserve the exact original audio chunks`() = runBlocking {
        val store = TtsArtifactStore(temporaryFolder.newFolder("tts_library"))
        val original = listOf(
            TTSResponse(byteArrayOf(1, 2, 3), AudioFormat.MP3, sampleRate = 32_000),
            TTSResponse(byteArrayOf(4, 5, 6, 7), AudioFormat.MP3, sampleRate = 32_000),
        )

        val entry = store.save(
            text = "一段需要永久保存的语音",
            responses = original,
            createdAtMs = 123L,
            artifactId = "artifact_001",
        )
        val loaded = store.load(entry.artifactId)!!

        assertEquals("一段需要永久保存的语音", loaded.entry.text)
        assertEquals(7L, loaded.entry.totalBytes)
        assertEquals(2, loaded.audio.size)
        assertArrayEquals(original[0].audioData, loaded.audio[0].audioData)
        assertArrayEquals(original[1].audioData, loaded.audio[1].audioData)
    }

    @Test
    fun `list retains every entry and orders newest first`() = runBlocking {
        val store = TtsArtifactStore(temporaryFolder.newFolder("tts_library"))
        repeat(5) { index ->
            store.save(
                text = "entry $index",
                responses = listOf(TTSResponse(byteArrayOf(index.toByte()), AudioFormat.WAV)),
                createdAtMs = index.toLong(),
                artifactId = "artifact_$index",
            )
        }

        assertEquals(
            listOf("artifact_4", "artifact_3", "artifact_2", "artifact_1", "artifact_0"),
            store.list(limit = 100).map(TtsLibraryEntry::artifactId),
        )
        assertEquals(
            listOf("artifact_2", "artifact_1"),
            store.list(limit = 2, offset = 2).map(TtsLibraryEntry::artifactId),
        )
        assertEquals(5, temporaryFolder.root.resolve("tts_library").listFiles().orEmpty().size)
    }

    @Test
    fun `corrupted audio is never replayed`() = runBlocking {
        val root = temporaryFolder.newFolder("tts_library")
        val store = TtsArtifactStore(root)
        store.save(
            text = "integrity",
            responses = listOf(TTSResponse(byteArrayOf(1, 2, 3), AudioFormat.MP3)),
            artifactId = "artifact_safe",
        )
        root.resolve("artifact_safe/000.mp3").writeBytes(byteArrayOf(9, 9, 9))

        val failure = runCatching { store.load("artifact_safe") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(store.get("../../outside"))
    }

    @Test
    fun `delete removes only the selected private artifact`() = runBlocking {
        val root = temporaryFolder.newFolder("tts_library")
        val store = TtsArtifactStore(root)
        store.save(
            text = "first",
            responses = listOf(TTSResponse(byteArrayOf(1), AudioFormat.MP3)),
            artifactId = "artifact_first",
        )
        store.save(
            text = "second",
            responses = listOf(TTSResponse(byteArrayOf(2), AudioFormat.MP3)),
            artifactId = "artifact_second",
        )

        assertTrue(store.delete("artifact_first"))
        assertNull(store.get("artifact_first"))
        assertEquals("artifact_second", store.get("artifact_second")?.artifactId)
        assertTrue(!store.delete("../../outside"))
    }
}
