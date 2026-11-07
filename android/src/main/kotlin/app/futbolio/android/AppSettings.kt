package app.futbolio.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.futbolio.application.api.FutbolioConfig
import app.futbolio.engine.api.*
import kotlinx.coroutines.flow.first

private val Context.futbolioStore by preferencesDataStore("futbolio-v1")
class AppSettings(context: Context) {
    private val store = context.applicationContext.futbolioStore
    companion object {
        private val portKey = intPreferencesKey("server-port")
        private val sourcesKey = stringPreferencesKey("providers")
        private val desiredKey = booleanPreferencesKey("desired-running")
    }
    /** Corrupt settings are reported to the UI, never silently overwritten. */
    suspend fun config(): FutbolioConfig = store.data.first()[sourcesKey]?.let { Configurations.import(it) } ?: Configurations.defaults()
    suspend fun save(config: FutbolioConfig) { config.validate(); store.edit { it[sourcesKey] = config.export() } }
    suspend fun desired() = store.data.first()[desiredKey] ?: false
    suspend fun desired(value: Boolean) { store.edit { it[desiredKey] = value } }
    suspend fun port() = (store.data.first()[portKey] ?: 7000).also { require(it in 1024..65535) }
    suspend fun port(value: Int) { require(value in 1024..65535); store.edit { it[portKey] = value } }
}
