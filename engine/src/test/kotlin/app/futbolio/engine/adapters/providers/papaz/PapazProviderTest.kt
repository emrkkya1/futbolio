package app.futbolio.engine.adapters.providers.papaz

import app.futbolio.engine.api.Configurations
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.catalog.ListingKind
import app.futbolio.application.ports.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PapazProviderTest {
    private val source = Configurations.sources().first { it.kind == "papaz" }
    @Test fun fixtureProducesMatchesAndChannelsWithoutExecutingScripts() {
        val script = requireNotNull(javaClass.getResource("/providers/papaz/catalog.js")).readText()
        val rows = PapazProvider.parse(source, script, "https://fixture.example/")
        assertEquals(2, rows.size)
        assertFalse(rows.first().channel)
        assertTrue(rows.last().channel)
        assertTrue(PapazProvider.parse(source, "const channels=[process.exit()];", "https://fixture.example/").isEmpty())
    }

    @Test fun publishedTestMappingRemainsExplicit() {
        val html = "this.baseurls=['https://cdn.example/'];if(id==='androstreamlivebs1'&&!useRealStream)return 'test.m3u8';"
        assertEquals("https://cdn.example/test.m3u8", PapazPlayerExtractor.extract(html, "https://fixture.example/event?id=androstreamlivebs1").first())
        assertEquals("https://cdn.example/androstreamlivebs1.m3u8", PapazPlayerExtractor.extract(html, "https://fixture.example/event?id=androstreamlivebs1&real=1").first())
    }

    @Test fun htmlCleanupDoesNotChangeHistoricListingIdentity() {
        val raw = "<b>Fixture Sports</b>"
        val html = """<li class="channel-item" data-target="vip" data-source="1" data-name="&lt;b&gt;Fixture Sports&lt;/b&gt;"></li>"""
        val listing = PapazProvider.parsePortal(source, html, "https://fixture.example/").single()
        val historic = Listing(source, raw, "Turkish sports channels", "https://fixture.example/?futbolio_channel=1", ListingKind.CHANNEL)
        assertEquals("Fixture Sports", listing.title)
        assertEquals(historic.id, listing.id)
    }

    @Test fun discoveryUsesResolvedDomainAndItsPublishedPlayerInsteadOfBitlyEventUrls() = runBlocking {
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val body = when (url) {
                    source.url -> """<script>window.launchpadData={"buttons":[{"title":"Siteye Giriş","bitlink":"bit.ly/current"}]};</script>"""
                    "https://bit.ly/current" -> """<div class="channel-item" data-target="viptv" data-source="100001" data-name="Fixture Sports"></div>"""
                    else -> error("Unexpected request")
                }
                return UpstreamResponse(200, if (url == "https://bit.ly/current") "https://current.example/" else url, emptyMap(), body.byteInputStream())
            }
        }
        val result = PapazProvider.discover(source, net)
        assertEquals("https://current.example/", result.activeUrl)
        assertTrue(result.listings.single().page.startsWith("https://current.example/"))
        assertEquals("viptv", result.listings.single().players.single().target)
    }

    @Test fun unavailableEntryCannotProduceBrokenBitlyWebsiteFallbacks() = runBlocking {
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("Must not fetch legacy listing script")
        }
        assertNull(PapazProvider.recover(source, net))
    }
}
