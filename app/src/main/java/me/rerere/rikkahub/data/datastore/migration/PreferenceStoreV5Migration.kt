package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.DEFAULT_USER_CONTEXT_WINDOW_TOKENS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private const val LEGACY_DEFAULT_CONTEXT_WINDOW_TOKENS = 100_000

class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.PROVIDERS] = migrateLegacyModelContextWindows(
            prefs[SettingsStore.PROVIDERS] ?: "[]",
        )
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/**
 * Early builds serialized 100K on every model because it was then the field default. Upgrade
 * that one legacy default once; values deliberately changed after V5 remain fully user-owned.
 */
internal fun migrateLegacyModelContextWindows(providersJson: String): String = runCatching {
    val providers = JsonInstant.parseToJsonElement(providersJson) as? JsonArray
        ?: return@runCatching providersJson
    val migrated = JsonArray(providers.map { providerElement ->
        val provider = providerElement as? JsonObject ?: return@map providerElement
        val models = provider["models"] as? JsonArray ?: return@map provider
        val migratedModels = JsonArray(models.map { modelElement ->
            val model = modelElement as? JsonObject ?: return@map modelElement
            val persistedWindow = model["userContextWindowTokens"]
                ?.jsonPrimitive
                ?.intOrNull
            if (persistedWindow != LEGACY_DEFAULT_CONTEXT_WINDOW_TOKENS) {
                model
            } else {
                JsonObject(
                    model.toMutableMap().apply {
                        put(
                            "userContextWindowTokens",
                            JsonPrimitive(DEFAULT_USER_CONTEXT_WINDOW_TOKENS),
                        )
                    },
                )
            }
        })
        JsonObject(provider.toMutableMap().apply { put("models", migratedModels) })
    })
    JsonInstant.encodeToString(migrated)
}.getOrDefault(providersJson)
