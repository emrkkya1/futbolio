package app.futbolio.engine.adapters.providers

import app.futbolio.application.ports.*
import app.futbolio.application.api.ProviderDescriptor
import app.futbolio.application.api.ProviderSetting
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.adapters.providers.ppv.PpvProvider
import app.futbolio.engine.adapters.providers.papaz.PapazProvider
import app.futbolio.engine.adapters.providers.taraftarium.TaraftariumProvider
import app.futbolio.engine.adapters.providers.futbolvivo.FutbolVivoProvider
import app.futbolio.engine.adapters.providers.stream720.Stream720Provider

internal class ProviderRegistration(val descriptor: ProviderDescriptor, factory: () -> Provider) {
    val source get() = descriptor.source
    val adapter by lazy(factory)
}

internal class ProviderRegistry(registrations: List<ProviderRegistration> = bundled) : ProviderDirectory {
    override val descriptors = registrations.map { it.descriptor }
    private val adapters = registrations.associate { it.source.kind to it.adapter }

    init {
        require(descriptors.map { it.kind }.distinct().size == descriptors.size)
        require(descriptors.map { it.source.id }.distinct().size == descriptors.size)
    }

    /** Fixture convenience; production registration always supplies explicit metadata. */
    constructor(adapters: Map<String, Provider>) : this(adapters.map { (kind, adapter) ->
        ProviderRegistration(ProviderDescriptor(SourceConfig(kind, kind, kind, "https://fixture.example/"))) { adapter }
    })

    override fun get(kind: String): Provider = adapters[kind] ?: throw IllegalArgumentException("Unsupported provider adapter")

    companion object {
        val bundled = listOf(
            ProviderRegistration(ProviderDescriptor(SourceConfig("ppv", "ppv", "PPV", "https://ppv.st/", "https://api.ppv.st/api/streams"), setOf(ProviderSetting.API_URL))) { PpvProvider },
            ProviderRegistration(ProviderDescriptor(SourceConfig("papaz", "papaz", "PapazSports", "https://bit.ly/m/papazsports", dataUrl = "https://gr0k.app/script2.js",
                fallbackUrls = listOf("https://linktr.ee/papazsports", "https://bit.ly/3QeeImt")), setOf(ProviderSetting.DATA_URL))) { PapazProvider },
            ProviderRegistration(ProviderDescriptor(SourceConfig("taraftarium", "taraftarium", "Taraftarium24", "https://taraftarium24.io/"), setOf(ProviderSetting.API_URL))) { TaraftariumProvider },
            // The agenda's date/time fields are US Eastern wall time, not UTC (including DST).
            ProviderRegistration(ProviderDescriptor(SourceConfig("futbolvivo", "futbolvivo", "FutbolVivo", "https://futbolvivo.online/", timeZone = "America/New_York"), setOf(ProviderSetting.API_URL))) { FutbolVivoProvider },
            ProviderRegistration(ProviderDescriptor(SourceConfig("720pstream", "720pstream", "720pstream", "https://720pstream.cx/soccer-online", timeZone = "UTC"))) { Stream720Provider },
        )
        val kinds get() = bundled.map { it.source.kind }
    }
}
