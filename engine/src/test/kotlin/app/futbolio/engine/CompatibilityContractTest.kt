package app.futbolio.engine

import app.futbolio.engine.runtime.FutbolioEngine

import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.providers.ppv.PpvProvider
import app.futbolio.engine.adapters.providers.papaz.PapazProvider
import app.futbolio.engine.adapters.providers.taraftarium.TaraftariumProvider
import app.futbolio.engine.adapters.providers.futbolvivo.FutbolVivoProvider
import app.futbolio.engine.adapters.providers.stream720.Stream720Provider

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.*

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/** Sanitized documents and fixed identities survive package/module moves unchanged. */
class CompatibilityContractTest {
    private fun resource(path: String) = requireNotNull(javaClass.getResource("/providers/$path")).readText()
    private val now = 1893456000000L

    @Test fun listingAndAggregatedIdentitiesRemainCompatible() {
        val source = SourceConfig("fixture", "ppv", "Fixture", "https://fixture.example/")
        val listing = Listing(source, "Home vs Away", "Premier League", "https://fixture.example/game", starts = now)
        assertEquals("fut:fixture:254e293007bae4e03175e2ff", listing.id)
        val entry = MatchNormalizer.aggregate(listOf(listing), emptyList(), PresentationConfig(), now).single()
        assertEquals("fut:event:5c4e335ca697d1f2205e700f", entry.id)
    }

    @Test fun sanitizedCatalogsCoverEveryBundledKind() = runBlocking {
        val counts = linkedMapOf<String, Int>()
        counts["ppv"] = PpvProvider.parse(
            Configurations.sources().first { it.kind == "ppv" },
            codec.parseToJsonElement(resource("ppv/catalog.json")).obj(), now,
        ).size
        counts["papaz"] = PapazProvider.parse(
            Configurations.sources().first { it.kind == "papaz" }, resource("papaz/catalog.js"), "https://fixture.example/",
        ).size
        val fixture = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?) =
                UpstreamResponse(200, url, emptyMap(), resource("taraftarium/landing.html").byteInputStream())
        }
        counts["taraftarium"] = TaraftariumProvider.discover(
            Configurations.sources().first { it.kind == "taraftarium" }.copy(url = "https://fixture.example/"), fixture,
        ).listings.size
        counts["futbolvivo"] = FutbolVivoProvider.parse(
            Configurations.sources().first { it.kind == "futbolvivo" },
            codec.parseToJsonElement(resource("futbolvivo/agenda.json")).obj(),
        ).size
        counts["720pstream"] = Stream720Provider.parse(
            Configurations.sources().first { it.kind == "720pstream" }, resource("stream720/schedule.html"), "https://fixture.example/",
        ).size
        assertEquals(ProviderRegistry.kinds.toSet(), counts.keys)
        assertEquals(mapOf("ppv" to 1, "papaz" to 2, "taraftarium" to 1, "futbolvivo" to 1, "720pstream" to 1), counts)
    }

    @Test fun manifestIdentityAndCatalogNamesRemainCompatible() {
        val engine = FutbolioEngine(HostConfig(), emptyList(), PosterRenderer { _, _ -> byteArrayOf() }, FixtureUpstream())
        try {
            val manifest = engine.manifest()
            assertEquals("community.futbolio.local", manifest.s("id"))
            assertEquals(listOf("tv"), manifest.a("types").map { it.jsonPrimitive.content })
            val catalogs = manifest.a("catalogs")
            assertEquals(listOf("live", "upcoming", "turkish", "channels", "all-matches", "search"), catalogs.map { it.obj().s("id") })
            val turkish = catalogs.first { it.obj().s("id") == "turkish" }.obj()
                .a("extra").single { it.obj().s("name") == "genre" }.obj().a("options").map { it.jsonPrimitive.content }
            assertTrue(turkish.isNotEmpty())
            assertTrue(turkish.all { it.contains("Turkish") || it.contains("Süper") || it.contains("TFF") })
            val search = catalogs.last().obj().a("extra").single().obj()
            assertEquals("search", search.s("name")); assertTrue(search.b("isRequired"))
            assertFalse(catalogs.any { it.obj().s("id") == "provider-tests" })
            val league = app.futbolio.domain.match.Leagues.find("UEFA Europa League")
            val entry = app.futbolio.domain.catalog.MatchNormalizer.aggregate(listOf(app.futbolio.domain.catalog.Listing(
                Configurations.sources().first(), "Arsenal vs Chelsea", league.name, "https://fixture.example/match", starts = System.currentTimeMillis()
            )), emptyList(), app.futbolio.domain.settings.PresentationConfig(), System.currentTimeMillis()).single()
            val meta = app.futbolio.engine.adapters.stremio.wire.StremioWire(engine.host).meta(entry, app.futbolio.domain.settings.PresentationConfig(), System.currentTimeMillis())
            val links = meta.a("links")
            assertEquals(listOf("Competition", "Team", "Team"), links.map { it.obj().s("category") })
            assertTrue(links.first().obj().s("url").contains("all-matches"))
            assertTrue(links.first().obj().s("url").contains("UEFA%20Europa%20League"))
        } finally { engine.close() }
    }

    @Test fun importDoesNotAdoptLegacyNetworkSettingsOrRotateCustomProviderValues() {
        val imported = FutbolioConfig.import("""{"schema":1,"host":"0.0.0.0","port":9000,"publicUrl":"https://foreign.example/","sources":[{"id":"custom","kind":"ppv","name":"Custom","url":"https://fixture.example/"}],"presentation":{"scoresEnabled":false,"displayTimeZone":"UTC"}}""")
        assertEquals(listOf("custom"), imported.sources.map { it.id })
        assertFalse(imported.presentation.scoresEnabled)
        assertEquals("UTC", imported.presentation.displayTimeZone)
        assertEquals(imported, FutbolioConfig.import(imported.export()))
    }
}
