package nl.baskt.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "baskt-settings")

data class AppSettings(val baseUrl: String, val token: String, val language: String = "system", val currentBasketId: String = "default", val speakReplies: Boolean = false, val ttsModel: String = "inworld/realtime-tts-1.5-mini", val ttsVoice: String = "Ashley") {
    val configured: Boolean get() = baseUrl.isNotBlank()

    /** Language code sent to the server: the chosen one, or the device language when set to "system". */
    val resolvedLanguage: String
        get() = if (language == "system") java.util.Locale.getDefault().language.ifBlank { "en" } else language
}

val LANGUAGE_OPTIONS = listOf(
    "system" to "System default",
    "en" to "English",
    "nl" to "Nederlands",
    "es" to "Español",
    "ca" to "Català",
    "de" to "Deutsch",
    "fr" to "Français",
)

class SettingsStore(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("baseUrl")
    private val tokenKey = stringPreferencesKey("token")
    private val languageKey = stringPreferencesKey("language")
    private val basketKey = stringPreferencesKey("currentBasketId")

    private val speakKey = booleanPreferencesKey("speakReplies")
    private val ttsModelKey = stringPreferencesKey("ttsModel")
    private val ttsVoiceKey = stringPreferencesKey("ttsVoice")

    suspend fun saveSpeakReplies(enabled: Boolean) { context.dataStore.edit { it[speakKey] = enabled } }
    suspend fun saveVoice(model: String, voice: String) {
        context.dataStore.edit { it[ttsModelKey] = model; it[ttsVoiceKey] = voice }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(baseUrl = prefs[baseUrlKey] ?: DEFAULT_BASE_URL, token = prefs[tokenKey] ?: "", language = prefs[languageKey] ?: "system", currentBasketId = prefs[basketKey] ?: "default", speakReplies = prefs[speakKey] ?: false, ttsModel = prefs[ttsModelKey] ?: "inworld/realtime-tts-1.5-mini", ttsVoice = prefs[ttsVoiceKey] ?: "Ashley")
    }

    suspend fun saveCurrentBasket(id: String) {
        context.dataStore.edit { prefs -> prefs[basketKey] = id }
    }

    suspend fun save(baseUrl: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[baseUrlKey] = baseUrl.trim().trimEnd('/')
            prefs[tokenKey] = token.trim()
        }
    }

    suspend fun saveLanguage(language: String) {
        context.dataStore.edit { prefs -> prefs[languageKey] = language }
    }

    companion object {
        /** Android emulator alias for the host machine. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:3000"
    }
}
