package app.futbolio.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.futbolio.application.api.FutbolioConfig
import app.futbolio.engine.api.*
import kotlinx.coroutines.flow.first

private val Context.futbolioStore by preferencesDataStore("futbolio-v1")
class AppSettings(context: Context) {
    private val store = context.applicationContext.futbolioStore
    companion object {
        const val MANIFEST = "http://127.0.0.1:7000/manifest.json"
        private val sourcesKey = stringPreferencesKey("providers")
        private val desiredKey = booleanPreferencesKey("desired-running")
    }
    /** Corrupt settings are reported to the UI, never silently overwritten. */
    suspend fun config(): FutbolioConfig = store.data.first()[sourcesKey]?.let { Configurations.import(it) } ?: Configurations.defaults()
    suspend fun save(config: FutbolioConfig) { config.validate(); store.edit { it[sourcesKey] = config.export() } }
    suspend fun desired() = store.data.first()[desiredKey] ?: false
    suspend fun desired(value: Boolean) { store.edit { it[desiredKey] = value } }
}
