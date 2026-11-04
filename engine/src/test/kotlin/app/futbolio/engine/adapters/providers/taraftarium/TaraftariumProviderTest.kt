package app.futbolio.engine.adapters.providers.taraftarium

import app.futbolio.application.ports.*
import app.futbolio.engine.api.Configurations
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.domain.catalog.CatalogVisibility
import app.futbolio.domain.settings.PresentationConfig
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TaraftariumProviderTest {
    @Test fun optionalMatchApiFailureKeepsChannelsWithWarning() = runBlocking {
        val source = Configurations.sources().first { it.kind == "taraftarium" }.copy(url = "https://fixture.example/", apiUrl = "https://api.example/matches")
        val transport = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                if (url == source.apiUrl) error("Unavailable")
                return UpstreamResponse(200, url, emptyMap(), "<a href='/channel.html?id=fixture'>Fixture Sports 7/24</a>".byteInputStream())
            }
        }
        val result = TaraftariumProvider.discover(source, transport)
        assertTrue(result.listings.single().channel)
        assertTrue(result.warning.isNotBlank())
    }

    @Test fun alternateQualityAndRegionLabelsDoNotBecomeConflictingCompetitions() = runBlocking {
        val source = Configurations.sources().first { it.kind == "taraftarium" }.copy(url = "https://fixture.example/", apiUrl = "https://api.example/matches")
        val transport = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val body = if (url == source.apiUrl) """[
                    {"HomeTeam":"PSV","AwayTeam":"Club Brugge","Time":"23:00","URL":"/ch.html?id=ex5","type":"football","league":"UEFA Şampiyonlar Ligi | 2. Kanal"},
                    {"HomeTeam":"PSV","AwayTeam":"Club Brugge","Time":"23:00","URL":"/ch.html?id=geo","type":"football","league":"Sadece Türkiye'den izlenebilir | FHD"}
                ]""" else "<html>Fixture</html>"
                return UpstreamResponse(200, url, emptyMap(), body.byteInputStream())
            }
        }
        val now = Instant.parse("2026-11-04T20:30:00Z").toEpochMilli()
        val rows = TaraftariumProvider.discover(source, transport).listings
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.league == "UEFA Şampiyonlar Ligi" })
        val entry = MatchNormalizer.aggregate(rows, emptyList(), PresentationConfig(), now).single()
        assertEquals(2, entry.listings.size)
        assertFalse(entry.ambiguous)
        assertTrue(CatalogVisibility.visible(entry, now))
    }

    @Test fun playerMappingDoesNotSelectPreroll() {
        val html = "const CONFIG={baseUrl:'https://cdn.example/',prerollVideo:'https://ads.example/ad.m3u8'};return CONFIG.baseUrl+b+'/mono.m3u8'"
        assertEquals(listOf("https://cdn.example/fixture/mono.m3u8"), TaraftariumPlayerExtractor.extract(html, "https://fixture.example/channel.html?id=fixture"))
    }
}
