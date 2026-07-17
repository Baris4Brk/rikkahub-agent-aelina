package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.telegram.TelegramBotClient
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import sun.misc.Unsafe

class TelegramUploadPathSafetyTest {
    private val protectedDatabasePath =
        "/data/user/0/${BuildConfig.APPLICATION_ID}/databases/rikka_hub"

    @Test
    fun `telegram photo cannot exfiltrate core second user data`() {
        val result = execute(telegramSendPhotoTool(ghost(), ghost()))

        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `telegram document cannot exfiltrate core second user data`() {
        val result = execute(telegramSendDocumentTool(ghost(), ghost()))

        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    private fun execute(tool: me.rerere.ai.core.Tool) = Json.parseToJsonElement(
        execTool(tool, """{"path":"$protectedDatabasePath"}"""),
    ).jsonObject

    private inline fun <reified T> ghost(): T = unsafe.allocateInstance(T::class.java) as T

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
