package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMigratorTest {
    @Test
    fun `legacy backup web search switch is copied to assistants without a value`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"enableWebSearch":true,"assistants":[{"id":"legacy"}]}""",
        )
        val assistant = JsonInstant.parseToJsonElement(migrated)
            .jsonObject
            .getValue("assistants")
            .jsonArray
            .single()
            .jsonObject

        assertTrue(assistant.getValue("enableWebSearch").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `explicit per assistant web search value wins during backup import`() {
        val migrated = SettingsJsonMigrator.migrate(
            """{"enableWebSearch":true,"assistants":[{"id":"current","enableWebSearch":false}]}""",
        )
        val assistant = JsonInstant.parseToJsonElement(migrated)
            .jsonObject
            .getValue("assistants")
            .jsonArray
            .single()
            .jsonObject

        assertFalse(assistant.getValue("enableWebSearch").jsonPrimitive.content.toBoolean())
    }
}
