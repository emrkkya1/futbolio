package app.futbolio.application.ports

import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.application.api.ProviderDescriptor

data class ProviderResult(val listings: List<Listing>, val warning: String = "", val activeUrl: String = "")

/** Providers return inline media only. Shared application policy supplies browser fallback. */
interface Provider {
    suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult
    suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice>
    suspend fun recover(source: SourceConfig, net: Upstream): ProviderResult? = null
}

interface ProviderDirectory {
    val descriptors: List<ProviderDescriptor>
    fun get(kind: String): Provider
}
