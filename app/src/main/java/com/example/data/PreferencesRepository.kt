package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.CantoneseFormat
import com.example.model.RoutingMode
import com.example.model.ThemePreference
import com.example.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.cantoPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cantoscribe_preferences"
)

class PreferencesRepository(private val appContext: Context) {

    private object Keys {
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val DEFAULT_FORMAT = stringPreferencesKey("default_format")
        val INCLUDE_WRITTEN_AUTO = booleanPreferencesKey("include_written_auto")
        val AUTO_COPY_ON_FINISH = booleanPreferencesKey("auto_copy_on_finish")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val CUSTOM_GLOSSARY = stringPreferencesKey("custom_glossary")
        val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        val ACTIVE_WHISPER_MODEL_ID = stringPreferencesKey("active_whisper_model_id")
    }

    val preferencesFlow: Flow<UserPreferences> = appContext.cantoPrefsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferences(
                routingMode = prefs[Keys.ROUTING_MODE]
                    ?.let { runCatching { RoutingMode.valueOf(it) }.getOrNull() }
                    ?: RoutingMode.AUTO,
                defaultFormat = prefs[Keys.DEFAULT_FORMAT]
                    ?.let { runCatching { CantoneseFormat.valueOf(it) }.getOrNull() }
                    ?: CantoneseFormat.SPOKEN_CANTONESE,
                includeWrittenInAuto = prefs[Keys.INCLUDE_WRITTEN_AUTO] ?: true,
                autoCopyOnFinish = prefs[Keys.AUTO_COPY_ON_FINISH] ?: true,
                hapticsEnabled = prefs[Keys.HAPTICS_ENABLED] ?: true,
                customGlossary = prefs[Keys.CUSTOM_GLOSSARY].orEmpty(),
                themePreference = prefs[Keys.THEME_PREFERENCE]
                    ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
                    ?: ThemePreference.SYSTEM,
                activeWhisperModelId = prefs[Keys.ACTIVE_WHISPER_MODEL_ID]?.takeIf { it.isNotBlank() }
            )
        }

    suspend fun currentPreferences(): UserPreferences = preferencesFlow.first()

    suspend fun setRoutingMode(mode: RoutingMode) {
        appContext.cantoPrefsDataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }

    suspend fun setDefaultFormat(format: CantoneseFormat) {
        appContext.cantoPrefsDataStore.edit { it[Keys.DEFAULT_FORMAT] = format.name }
    }

    suspend fun setIncludeWrittenInAuto(include: Boolean) {
        appContext.cantoPrefsDataStore.edit { it[Keys.INCLUDE_WRITTEN_AUTO] = include }
    }

    suspend fun setAutoCopyOnFinish(enabled: Boolean) {
        appContext.cantoPrefsDataStore.edit { it[Keys.AUTO_COPY_ON_FINISH] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        appContext.cantoPrefsDataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setCustomGlossary(glossary: String) {
        appContext.cantoPrefsDataStore.edit { it[Keys.CUSTOM_GLOSSARY] = glossary.trim() }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        appContext.cantoPrefsDataStore.edit { it[Keys.THEME_PREFERENCE] = theme.name }
    }

    suspend fun setActiveWhisperModelId(modelId: String?) {
        appContext.cantoPrefsDataStore.edit { prefs ->
            if (modelId.isNullOrBlank()) {
                prefs.remove(Keys.ACTIVE_WHISPER_MODEL_ID)
            } else {
                prefs[Keys.ACTIVE_WHISPER_MODEL_ID] = modelId
            }
        }
    }
}
