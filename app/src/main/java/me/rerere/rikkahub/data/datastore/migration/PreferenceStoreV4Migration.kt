package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS] = migrateAssistantsWebSearch(
            assistantsJson = prefs[SettingsStore.ASSISTANTS] ?: "[]",
            legacyEnabled = prefs[SettingsStore.ENABLE_WEB_SEARCH] == true,
        )
        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * Moves the legacy global web-search switch onto each already-persisted assistant.
 * Explicit per-assistant values win, which makes this safe for restored newer backups.
 */
internal fun migrateAssistantsWebSearch(
    assistantsJson: String,
    legacyEnabled: Boolean,
): String = runCatching {
    val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
        ?: return@runCatching assistantsJson
    val migrated = JsonArray(root.map { element ->
        val assistant = element as? JsonObject ?: return@map element
        if ("enableWebSearch" in assistant) {
            assistant
        } else {
            JsonObject(
                assistant.toMutableMap().apply {
                    put("enableWebSearch", JsonPrimitive(legacyEnabled))
                },
            )
        }
    })
    JsonInstant.encodeToString(migrated)
}.getOrDefault(assistantsJson)
