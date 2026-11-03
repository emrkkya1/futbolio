package app.futbolio.engine

import app.futbolio.engine.adapters.providers.ProviderRegistry

import app.futbolio.application.ports.*
import app.futbolio.application.api.*
import app.futbolio.engine.runtime.FutbolioEngine
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.*
import app.futbolio.engine.api.Configurations

import kotlinx.coroutines.*
import java.net.ServerSocket
import kotlin.test.*

/** Characterization/race tests written before restructuring the affected services. */
class RestructureRegressionTest {
    @Test fun invalidationCancelsAnObsoleteResolution() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val resolver = StreamResolver(scope, net, ProviderRegistry())
            val source = Configurations.sources().first { it.kind == "futbolvivo" }
            val row = Listing(source, "Home vs Away", "Football", "https://fixture.example/match")
            val resolution = async { resolver.resolve(row) }
            withTimeout(2000) { entered.await() }
            resolver.trim()
            withTimeout(1000) { cancelled.await() }
            assertFailsWith<CancellationException> { resolution.await() }
        } finally { scope.cancel() }
    }

    @Test fun reconfigureClearsWorkbenchDataButRetainsIssuedMediaCapabilities() = runBlocking<Unit> {
        val fixture = FixtureUpstream()
        val engine = FutbolioEngine(
            HostConfig(port = ServerSocket(0).use { it.localPort }),
            listOf(fixture.source), PosterRenderer { _, _ -> byteArrayOf() }, fixture,
        )
        try {
            engine.start()
            assertEquals(ProbePhase.COMPLETE, engine.workbench.test(fixture.source).phase)
            assertTrue(engine.workbench.entries().isNotEmpty())
            val url = engine.tokens.register("https://cdn.example/live.m3u8", SourceHeaders())
            val token = url.substringAfter("/hls/").substringBefore('/')
            engine.configure(emptyList())
            assertTrue(engine.workbench.entries().isEmpty())
            assertTrue(engine.workbench.reports.value.isEmpty())
            assertTrue(engine.workbench.candidates.value.isEmpty())
            assertNotNull(engine.tokens.get(token), "Existing viewers survive ordinary provider edits")
        } finally { engine.close() }
    }

    @Test fun closingRuntimeIsTerminalAndRevokesCapabilities() = runBlocking<Unit> {
        val fixture = FixtureUpstream()
        val engine = FutbolioEngine(
            HostConfig(port = ServerSocket(0).use { it.localPort }),
            emptyList(), PosterRenderer { _, _ -> byteArrayOf() }, fixture,
        )
        engine.start()
        engine.tokens.register("https://cdn.example/live.m3u8", SourceHeaders())
        engine.close()
        engine.close()
        try {
            assertEquals(0, engine.tokens.size())
            assertFailsWith<IllegalStateException> { engine.start() }
        } finally { engine.close() }
    }
}
