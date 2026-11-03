package app.futbolio.application.ports

import app.futbolio.application.api.FutbolioConfig

interface ConfigurationStore {
    suspend fun read(): FutbolioConfig?
    suspend fun write(config: FutbolioConfig)
}
