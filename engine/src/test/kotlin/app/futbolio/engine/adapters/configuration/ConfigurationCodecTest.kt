package app.futbolio.engine.adapters.configuration

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.api.Configurations
import kotlinx.serialization.json.*
import kotlin.test.*

class ConfigurationCodecTest {
    @Test fun timesAndScoresDefaultsOnForOldConfigsAndPersistsOff() {
        val defaults = Configurations.defaults()
        assertTrue(defaults.presentation.showTimesAndScores)
        val disabled = defaults.copy(presentation = defaults.presentation.copy(showTimesAndScores = false))
        assertEquals(disabled, Configurations.import(Configurations.export(disabled)))
        val json = Json.parseToJsonElement(Configurations.export(disabled)).jsonObject
        val old = JsonObject(json + ("presentation" to JsonObject(json.getValue("presentation").jsonObject - "showTimesAndScores")))
        assertTrue(Configurations.import(old.toString()).presentation.showTimesAndScores)
    }

    @Test fun explicitDtoPreservesSchemaAndAllCurrentFields() {
        val config = Configurations.defaults().let { it.copy(sources = it.sources.map { source -> source.copy(enabled = false) }) }
        val encoded = Configurations.export(config)
        val json = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("schema", "sources", "presentation", "defaultsRevision"), json.keys)
        assertEquals(setOf("id", "kind", "name", "url", "apiUrl", "dataUrl", "enabled", "fallbackUrls", "numberedDomainWindow", "timeZone"), json["sources"]!!.jsonArray.first().jsonObject.keys)
        assertEquals(config, Configurations.import(encoded))
    }

    @Test fun codecAcceptsInjectedAdapterMetadataWithoutBundledRegistryChanges() {
        val source = SourceConfig("custom-instance", "future-kind", "Future", "https://fixture.example/")
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(emptyList())
            override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> = emptyList()
        }
        val directory = object : ProviderDirectory {
            override val descriptors = listOf(ProviderDescriptor(source))
            override fun get(kind: String): Provider { require(kind == source.kind); return adapter }
        }
        val codec = ConfigurationCodec(directory)
        val config = FutbolioConfig(sources = listOf(source))
        assertEquals(config, codec.import(codec.export(config)))
    }

    @Test fun legacyDefaultUpgradesAreIdempotentAndRespectExplicitRemoval() {
        val defaults = Configurations.defaults()
        val old = Configurations.export(defaults.copy(defaultsRevision = 3, sources = defaults.sources.filterNot { it.kind == "720pstream" }))
        val upgraded = Configurations.import(old)
        assertEquals(1, upgraded.sources.count { it.kind == "720pstream" })
        assertEquals(upgraded, Configurations.import(Configurations.export(upgraded)))
        val removed = defaults.copy(sources = defaults.sources.filterNot { it.kind == "720pstream" })
        assertEquals(removed, Configurations.import(Configurations.export(removed)))
    }
}
