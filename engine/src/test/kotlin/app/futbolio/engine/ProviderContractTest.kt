package app.futbolio.engine

import app.futbolio.engine.adapters.providers.ProviderRegistry

import app.futbolio.application.ports.*
import app.futbolio.application.api.*
import app.futbolio.engine.runtime.FutbolioEngine
import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.api.*
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.*
import kotlin.test.*

class ProviderContractTest {
    @Test fun oneRegistrationListDrivesDefaultsRegistryAndLiveTests() {
        val registered = ProviderRegistry.bundled
        assertEquals(registered.map { it.source }, Configurations.sources())
        assertEquals(registered.map { it.source.kind }, ProviderRegistry.kinds)
        assertEquals(registered.size, registered.map { it.source.kind }.distinct().size)
        val registry = ProviderRegistry()
        registered.forEach { assertSame(it.adapter, registry.get(it.source.kind)); it.source.validate() }
        assertEquals(registered.filter { it.source.enabled }.map { it.source }, Configurations.sources().filter { it.enabled })
    }

    @Test fun newAdapterUsesEntireExistingVerifierWithoutProviderNameBranches() = runBlocking {
        val source = SourceConfig("new-adapter", "future-adapter", "Future adapter", "https://future.example/")
        var discovered = 0; var resolved = 0
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult {
                discovered++
                return ProviderResult(listOf(Listing(source, "Home vs Away", "Football", "https://future.example/game", starts = System.currentTimeMillis() - 60_000)))
            }
            override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
                resolved++
                return listOf(StreamChoice(item.title, "https://media.example/live.m3u8", mediaType = "hls"))
            }
        }
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val bytes = if (url.endsWith(".m3u8")) "#EXTM3U\n#EXTINF:4,\npart1.ts\n#EXTINF:4,\npart2.ts".toByteArray()
                    else ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0 }
                return UpstreamResponse(200, url, emptyMap(), bytes.inputStream())
            }
        }
        val registry = ProviderRegistry(mapOf(source.kind to adapter))
        val report = LiveProviderVerifier(net, providers = registry).verify(source)
        assertEquals("PASS", report.outcome)
        assertEquals(1, discovered); assertEquals(1, resolved)
        assertEquals(2, report.mediaObjects); assertEquals(3008L, report.mediaBytes)
    }

    @Test fun recoveryAlsoUsesTheProviderContract() = runBlocking {
        val source = SourceConfig("new-adapter", "future-adapter", "Future", "https://future.example/")
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult = error("Landing offline")
            override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> = emptyList()
            override suspend fun recover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(Listing(source, "Recovered", "Football", "https://future.example/recovered")))
        }
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No static network guesses expected")
        }
        assertEquals("Recovered", DomainRecovery(net, ProviderRegistry(mapOf(source.kind to adapter))).discover(source).listings.single().title)
    }

    @Test fun futureAdapterRunsThroughEngineValidationCatalogAndWorkbench() = runBlocking {
        val source = SourceConfig("future-instance", "future-kind", "Future", "https://fixture.example/")
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(
                Listing(source, "Home vs Away", "Football", "https://fixture.example/game", starts = System.currentTimeMillis()),
            ))
            override suspend fun resolve(item: Listing, net: Upstream) = listOf(StreamChoice("Fixture", "https://media.example/live.m3u8", mediaType = "hls"))
        }
        val directory = ProviderRegistry(mapOf(source.kind to adapter))
        val transport = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val bytes = if (url.endsWith(".m3u8")) "#EXTM3U\n#EXTINF:4,\npart1.ts\n#EXTINF:4,\npart2.ts".toByteArray()
                else ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0 }
                return UpstreamResponse(200, url, emptyMap(), bytes.inputStream())
            }
        }
        val engine = FutbolioEngine(HostConfig(port = java.net.ServerSocket(0).use { it.localPort }), listOf(source),
            PosterRenderer { _, _ -> byteArrayOf() }, transport, providers = directory)
        try {
            engine.start()
            engine.catalog.refresh().await()
            assertEquals(1, engine.catalog.items().size)
            engine.configure(listOf(source.copy(name = "Future updated")))
            engine.testProvider(source.id)
            val report = withTimeout(2000) { engine.providerReports.first { it[source.id]?.phase == ProbePhase.COMPLETE } }.getValue(source.id)
            assertEquals(ProbeStage.SEGMENTS_FETCHED, report.listings.single().choices.single().stage)
            assertTrue(engine.probeCandidates.value.getValue(source.id).isNotEmpty())
        } finally { engine.close() }
    }
}
