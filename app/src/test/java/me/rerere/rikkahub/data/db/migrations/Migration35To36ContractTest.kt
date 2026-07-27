package me.rerere.rikkahub.data.db.migrations

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration35To36ContractTest {
    @Test
    fun `schema 36 declares the recent-window composite index`() {
        val schema = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/36.json")
            .takeIf(File::exists)
            ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/36.json")
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 36"))
        assertTrue(text.contains("index_message_node_conversation_id_node_index"))
        assertTrue(text.contains("efb4f396f5f1d0fcce7479fbf5ef9238"))
    }
}
