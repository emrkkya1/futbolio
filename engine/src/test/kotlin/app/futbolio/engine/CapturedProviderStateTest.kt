package app.futbolio.engine

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.adapters.providers.futbolvivo.FutbolVivoProvider
import app.futbolio.engine.adapters.providers.papaz.PapazProvider
import app.futbolio.engine.adapters.providers.ppv.PpvProvider
import app.futbolio.engine.adapters.providers.stream720.Stream720Provider
import app.futbolio.engine.adapters.providers.taraftarium.TaraftariumProvider
import app.futbolio.engine.api.Configurations
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/** Dated real-provider state extracts: always offline and independent of today's schedule. */
class CapturedProviderStateTest {
    private val observed = Instant.parse("2026-11-04T21:30:00Z").toEpochMilli()
    private fun state(name: String) = requireNotNull(javaClass.getResource("/providers/states/2026-11-04/$name")).readText()
    private fun source(kind: String) = Configurations.sources().first { it.kind == kind }.copy(url = "https://$kind.example/")
    private fun player(noise: Boolean): String {
        val captured = state("720-player.html")
        if (!noise) return captured
        return Regex("<script data-regression-noise-bytes=\"(\\d+)\">.*?</script>", RegexOption.DOT_MATCHES_ALL).replace(captured) { match ->
            val size = match.groupValues[1].toInt()
            val statement = "function unrelated(){notLiteral();}"
            "<script>${statement.repeat((size + statement.length - 1) / statement.length).take(size)}</script>"
        }
    }

    @Test fun capturedCatalogsKeepEveryProviderOnTheTwoLiveMatchesAfterLocalMidnight() = runBlocking {
        val tara = source("taraftarium").copy(apiUrl = "https://api.example/matches")
        val transport = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val body = when (url) {
                    tara.url -> "<html>Captured homepage without decorative content</html>"
                    tara.apiUrl -> state("taraftarium-matches.json")
                    else -> error("Unexpected offline fixture request")
                }
                return UpstreamResponse(200, url, emptyMap(), body.byteInputStream())
            }
        }
        val rows = PpvProvider.parse(source("ppv"), codec.parseToJsonElement(state("ppv-catalog.json")).obj(), observed) +
            PapazProvider.parsePortal(source("papaz"), state("papaz-portal.html"), source("papaz").url) +
            TaraftariumProvider.discover(tara, transport).listings +
            FutbolVivoProvider.parse(source("futbolvivo"), codec.parseToJsonElement(state("futbolvivo-agenda.json")).obj()) +
            Stream720Provider.parse(source("720pstream"), state("720-schedule.html"), source("720pstream").url)
        val anchored = rows.map { if (it.starts == 0L) it.copy(observedAt = observed) else it }
        val entries = MatchNormalizer.aggregate(anchored, emptyList(), PresentationConfig(), observed)
        assertEquals(setOf("PSV Eindhoven vs Club Brugge", "RB Leipzig vs Manchester City"), entries.map { it.title }.toSet())
        for (entry in entries) {
            assertEquals(setOf("ppv", "papaz", "taraftarium", "futbolvivo", "720pstream"), entry.listings.map { it.source.kind }.toSet())
            assertEquals(6, entry.listings.size)
            assertEquals(Instant.parse("2026-11-04T20:00:00Z").toEpochMilli(), entry.starts)
            assertTrue(CatalogVisibility.visible(entry, observed))
            assertFalse(entry.ambiguous)
        }
    }

    @Test fun captured720ConfigurationSurvivesBothCleanAndAdHeavyResponses() {
        val clean = Stream720Provider.playerData(player(false))
        assertEquals(clean, Stream720Provider.playerData(player(true)))
        assertEquals("https://cdn.example/pavel/fixture-stream/manifest.ts", clean.media)
        assertEquals("fixture-csrf-token", clean.token)
        assertEquals("fixture-strUnqId", Web.query(clean.auth, "stream"))
        assertEquals("fixture-edgeHostId", Web.query(clean.auth, "host_id"))
        assertFalse(clean.toString().contains(clean.token))
    }

    @Test fun captured720EventEmbedPlayerAndAuthCompleteTheProductionResolutionPath() = runBlocking {
        val row = Stream720Provider.parse(source("720pstream"), state("720-schedule.html"), source("720pstream").url).first()
        val requests = mutableListOf<Pair<String, SourceHeaders>>()
        val transport = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                requests += url to headers
                val body = when (Web.url(url).host) {
                    "720pstream.example" -> state("720-event.html")
                    "embed.example" -> state("720-embed.html")
                    "player.example" -> player(true)
                    "auth.example" -> state("720-auth.json")
                    "cdn.example" -> "#EXTM3U\n#EXTINF:4,\npart.ts"
                    else -> error("Unexpected offline fixture request")
                }
                return UpstreamResponse(200, url, emptyMap(), body.byteInputStream())
            }
        }
        val choice = Stream720Provider.resolve(row, transport).single()
        assertTrue(isHls(choice))
        assertEquals("fixture-renewed-device", Web.query(choice.url, "u_id"))
        val session = assertNotNull(choice.session)
        session.open(choice.url).use { assertEquals(200, it.status) }
        val authorization = requests.single { Web.url(it.first).host == "auth.example" }
        assertEquals("fixture-csrf-token", authorization.second.scopedAuthFor(authorization.first))
        assertNull(requests.last().second.scopedAuthFor(choice.url))
    }
}
