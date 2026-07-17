package me.rerere.rikkahub.data.phone

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.phoneCallDataStore by preferencesDataStore(name = "phone_call")

class DataStorePhoneCallPreferences(
    context: Context,
) : PhoneCallPreferences {
    private val store = context.applicationContext.phoneCallDataStore
    private val componentNameKey = stringPreferencesKey("selected_account_component")
    private val accountIdKey = stringPreferencesKey("selected_account_id")

    override val selectedAccount: Flow<PhoneAccountKey?> = store.data.map { preferences ->
        val componentName = preferences[componentNameKey]
        val accountId = preferences[accountIdKey]
        if (componentName.isNullOrBlank() || accountId.isNullOrBlank()) {
            null
        } else {
            PhoneAccountKey(componentName, accountId)
        }
    }

    override suspend fun currentAccount(): PhoneAccountKey? = selectedAccount.first()

    override suspend fun selectAccount(key: PhoneAccountKey?) {
        store.edit { preferences ->
            if (key == null) {
                preferences.remove(componentNameKey)
                preferences.remove(accountIdKey)
            } else {
                preferences[componentNameKey] = key.componentName
                preferences[accountIdKey] = key.accountId
            }
        }
    }
}
