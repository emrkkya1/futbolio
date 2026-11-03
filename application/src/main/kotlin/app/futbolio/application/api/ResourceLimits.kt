package app.futbolio.application.api

/** Runtime policy, not user configuration. Defaults preserve the existing resource envelope. */
data class ResourceLimits(
    val sources: Int = 8,
    val listingsPerSource: Int = 600,
    val catalogRefreshMillis: Long = 60_000,
    val staleCatalogMillis: Long = 600_000,
    val activeResolutions: Int = 2,
    val pendingResolutions: Int = 8,
    val resolutionCacheEntries: Int = 32,
    val resolutionCacheMillis: Long = 20_000,
    val resolutionTimeoutMillis: Long = 25_000,
    val alternatePlayers: Int = 4,
    val documentBytes: Int = 2 * 1024 * 1024,
    val playlistBytes: Int = 512 * 1024,
    val mediaBufferBytes: Int = 32 * 1024,
    val mediaObjectBytes: Long = 64L * 1024 * 1024,
    val mediaDeadlineMillis: Long = 45_000,
    val desktopMediaSlots: Int = 8,
    val androidMediaSlots: Int = 4,
    val apiSlots: Int = 8,
    val relayTokens: Int = 4096,
    val relayIdleMillis: Long = 1_800_000,
    val posterEntries: Int = 32,
    val posterRenderSlots: Int = 2,
    val diagnosticEvents: Int = 30,
) {
    init {
        require(listOf(sources, listingsPerSource, activeResolutions, pendingResolutions, resolutionCacheEntries,
            alternatePlayers, documentBytes, playlistBytes, mediaBufferBytes, desktopMediaSlots,
            androidMediaSlots, apiSlots, relayTokens, posterEntries, posterRenderSlots, diagnosticEvents).all { it > 0 })
        require(listOf(catalogRefreshMillis, staleCatalogMillis, resolutionCacheMillis,
            resolutionTimeoutMillis, mediaObjectBytes, mediaDeadlineMillis, relayIdleMillis).all { it > 0 })
        require(staleCatalogMillis >= catalogRefreshMillis)
        require(pendingResolutions >= activeResolutions)
    }
}
