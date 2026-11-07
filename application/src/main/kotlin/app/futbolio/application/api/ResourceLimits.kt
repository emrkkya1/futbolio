package app.futbolio.application.api

/** Bounded runtime policy, not user configuration. */
data class ResourceLimits(
    val sources: Int = 8,
    val listingsPerSource: Int = 600,
    val catalogRefreshMillis: Long = 60_000,
    val staleCatalogMillis: Long = 600_000,
    val activeResolutions: Int = 2,
    val pendingResolutions: Int = 8,
    val resolutionCacheEntries: Int = 64,
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
    val activeStreamAssessments: Int = 1,
    val assessedChoicesPerListing: Int = 4,
    val streamAssessmentMillis: Long = 6_000,
    val streamAssessmentBatchMillis: Long = 7_000,
    val prefetchMatches: Int = 4,
    val prefetchExpansionMatches: Int = 4,
    val measuredProvidersPerMatch: Int = 3,
    val prefetchChoicesPerListing: Int = 2,
    val prefetchCacheMillis: Long = 60_000,
    val prefetchIntervalMillis: Long = 5_000,
    val prefetchRefreshAheadMillis: Long = 10_000,
    val prefetchBatchMillis: Long = 45_000,
    val foregroundQuietMillis: Long = 10_000,
    val playbackQuietMillis: Long = 30_000,
) {
    init {
        require(listOf(sources, listingsPerSource, activeResolutions, pendingResolutions, resolutionCacheEntries,
            alternatePlayers, documentBytes, playlistBytes, mediaBufferBytes, desktopMediaSlots,
            androidMediaSlots, apiSlots, relayTokens, posterEntries, posterRenderSlots, diagnosticEvents,
            activeStreamAssessments, assessedChoicesPerListing, prefetchMatches, prefetchExpansionMatches,
            measuredProvidersPerMatch, prefetchChoicesPerListing).all { it > 0 })
        require(listOf(catalogRefreshMillis, staleCatalogMillis, resolutionCacheMillis,
            resolutionTimeoutMillis, mediaObjectBytes, mediaDeadlineMillis, relayIdleMillis,
            streamAssessmentMillis, streamAssessmentBatchMillis, prefetchCacheMillis, prefetchIntervalMillis,
            prefetchBatchMillis, prefetchRefreshAheadMillis, foregroundQuietMillis, playbackQuietMillis).all { it > 0 })
        require(staleCatalogMillis >= catalogRefreshMillis)
        require(pendingResolutions >= activeResolutions)
    }
}
