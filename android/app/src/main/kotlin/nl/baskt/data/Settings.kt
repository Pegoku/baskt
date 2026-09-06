package nl.baskt.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "baskt-settings")

data class AppSettings(val baseUrl: String, val token: String) {
    val configured: Boolean get() = baseUrl.isNotBlank()
}

class SettingsStore(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("baseUrl")
    private val tokenKey = stringPreferencesKey("token")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(baseUrl = prefs[baseUrlKey] ?: DEFAULT_BASE_URL, token = prefs[tokenKey] ?: "")
    }

    suspend fun save(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[baseUrlKey] = baseUrl.trim().trimEnd('/')
            prefs[tokenKey] = token.trim()
        }
    }

    companion object {
        /** Android emulator alias for the host machine. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:3000"
    }
}
