package app.futbolio.application.configuration

import app.futbolio.application.api.FutbolioConfig
import app.futbolio.domain.provider.SourceConfig

/** Versioned, idempotent defaults upgrades; storage syntax stays in the codec adapter. */
object ConfigurationMigrations {
    fun upgrade(config: FutbolioConfig, hadRevision: Boolean, oldRevision: Int, defaults: List<SourceConfig>): FutbolioConfig {
        fun bundled(kind: String) = defaults.firstOrNull { it.kind == kind }
        var sources = config.sources
        if (!hadRevision && sources.map { it.id }.containsAll(listOf("ppv", "papaz", "taraftarium")) &&
            sources.none { it.kind == "futbolvivo" } && sources.size < 8) {
            bundled("futbolvivo")?.let { sources = sources + it }
        }
        if (oldRevision < 3) {
            val papaz = bundled("papaz")
            if (papaz != null) sources = sources.map { source ->
                if (source.id == "papaz" && source.kind == "papaz" && source.url.trimEnd('/') == "https://papazsports.co" && source.fallbackUrls.isEmpty())
                    source.copy(url = papaz.url, fallbackUrls = papaz.fallbackUrls) else source
            }
        }
        if (oldRevision < 4 && sources.any { it.id == "ppv" && it.kind == "ppv" } &&
            sources.none { it.id == "720pstream" || it.kind == "720pstream" } && sources.size < 8) {
            bundled("720pstream")?.let { sources = sources + it }
        }
        return config.copy(sources = sources, defaultsRevision = 4)
    }
}
