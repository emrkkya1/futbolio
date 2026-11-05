package app.futbolio.engine

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.scores.espn.EspnScoreFeed
import app.futbolio.engine.adapters.stremio.wire.StremioWire
import app.futbolio.engine.runtime.FutbolioEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class EspnScoreEnrichmentTest {
    private val now = Instant.parse("2026-11-05T20:00:00Z").toEpochMilli()
    private val league = Leagues.find("Premier League")
    private fun json() = requireNotNull(javaClass.getResource("/scores/espn-scoreboard.json")).readText()

    @Test fun parserReadsScoresHalftimeFinalsAndStoppageTimeAndSkipsMalformedEvents() {
        val parsed = EspnScoreFeed.parse(json(), league, now)
        assertEquals(4, parsed.size)
        assertEquals("47'", parsed.first().minute)
        assertEquals("2 - 1", parsed.first().scoreText(now))
        assertEquals("HT", parsed[1].clockText(now))
        assertEquals("45+2'", parsed[1].minute)
        assertEquals(MatchState.FINISHED, parsed[2].state)
        assertEquals("90+4'", parsed[2].minute)
        assertEquals(MatchState.SCHEDULED, parsed[3].state)
        assertEquals("", parsed[3].clockText(now))
    }

    @Test fun parserRetainsAllOneHundredFiftyEventsAndDoesNotTrustClockMarkup() {
        val root = codec.parseToJsonElement(json()).obj()
        val event = root.a("events").first()
        val large = buildJsonObject { put("events", JsonArray(List(160) { event })) }.toString()
        assertEquals(150, EspnScoreFeed.parse(large, league, now).size)
        val unsafe = json().replace("47'", "<script>bad</script>")
        assertEquals("", EspnScoreFeed.parse(unsafe, league, now).first().minute)
    }

    @Test fun wireRefreshesPostersForScoreAndStatusButNotMinuteTicks() {
        val fixture = EspnScoreFeed.parse(json(), league, now).first()
        val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
        val base = MatchNormalizer.aggregate(listOf(Listing(source, "Arsenal vs Chelsea", league.name,
            source.url, starts = fixture.starts)), emptyList(), PresentationConfig(), now).single()
        val wire = StremioWire(HostConfig())
        val plain = wire.meta(base, PresentationConfig(), now)
        val live = wire.meta(base.copy(fixture = fixture), PresentationConfig(), now)
        assertEquals("Arsenal vs Chelsea", live.s("name"))
        assertTrue(live.s("description").contains("Score: Arsenal 2 - 1 Chelsea"))
        assertTrue(live.s("description").contains("Match time: 47'"))
        assertNotEquals(plain.s("poster"), live.s("poster"))
        val nextMinute = wire.meta(base.copy(fixture = fixture.copy(minute = "48'")), PresentationConfig(), now)
        assertEquals(live.s("poster"), nextMinute.s("poster"))
        assertTrue(nextMinute.s("description").contains("Match time: 48'"))
        val nextGoal = wire.meta(base.copy(fixture = fixture.copy(homeScore = "3")), PresentationConfig(), now)
        assertNotEquals(live.s("poster"), nextGoal.s("poster"))
        val halftime = wire.meta(base.copy(fixture = fixture.copy(state = MatchState.HALFTIME)), PresentationConfig(), now)
        assertEquals("HT | Arsenal vs Chelsea", halftime.s("name"))
        assertTrue(halftime.s("description").contains("Halftime"))
        assertTrue(halftime.s("description").contains("Match time: HT"))
        assertNotEquals(live.s("poster"), halftime.s("poster"))
        val stale = wire.meta(base.copy(fixture = fixture.copy(observedAt = now - 300_000)), PresentationConfig(), now)
        assertEquals(plain.s("poster"), stale.s("poster"))
        assertFalse(stale.s("description").contains("Score:"))
        val hidden = wire.meta(base.copy(fixture = fixture), PresentationConfig(showTimesAndScores = false), now)
        assertFalse(hidden.s("description").contains("Score:"))
        assertFalse(hidden.s("description").contains("Match time:"))
        assertTrue(hidden.s("description").contains("Kickoff:"))
        assertTrue(hidden.s("description").contains("verified fixture data"))
        assertTrue(hidden.s("poster").contains("details=off"))
        assertNotEquals(live.s("poster"), hidden.s("poster"))
    }

    @Test fun realLocalCatalogAndPosterPathsEnrichBeforeRespondingWithoutExternalNetwork() = runBlocking {
        val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
        val titles = listOf("Arsenal vs Chelsea", "Man Utd vs Liverpool", "PSV vs Club Brugge", "Unknown A vs Unknown B")
        val starts = now - CatalogVisibility.HOUR
        val adapter = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(titles.mapIndexed { index, title ->
                Listing(source, title, league.name, "${source.url}$index", starts = starts)
            })
            override suspend fun resolve(item: Listing, net: Upstream) = emptyList<StreamChoice>()
        }
        var payload = json(); var calls = 0
        val transport = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                assertEquals("https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?limit=150", url)
                calls++
                return UpstreamResponse(200, url, emptyMap(), payload.byteInputStream())
            }
        }
        val ticks = AtomicLong()
        val clock = object : TimeSource {
            override fun wallMillis() = now + ticks.get()
            override fun monotonicMillis() = ticks.get()
        }
        val rendered = mutableListOf<PosterModel>()
        val renderer = object : PosterRenderer {
            override fun render(title: String, source: String) = byteArrayOf(1)
            override fun renderMatch(model: PosterModel): ByteArray { rendered += model; return byteArrayOf(1) }
        }
        val engine = FutbolioEngine(HostConfig(port = ServerSocket(0).use { it.localPort }), listOf(source), renderer,
            transport, time = clock, providers = ProviderRegistry(mapOf(source.kind to adapter)))
        fun get(url: String): String = (URI(url).toURL().openConnection() as HttpURLConnection).let { connection ->
            try { connection.connectTimeout = 3000; connection.readTimeout = 5000; connection.inputStream.bufferedReader().readText() }
            finally { connection.disconnect() }
        }
        fun metas() = codec.parseToJsonElement(get("${engine.host.base}/catalog/tv/football.json")).obj().a("metas").map { it.obj() }
        try {
            engine.start(); engine.catalog.refresh().await()
            val first = metas()
            assertEquals(setOf("Arsenal vs Chelsea", "HT | Manchester United vs Liverpool", "Unknown A vs Unknown B"), first.map { it.s("name") }.toSet())
            val live = first.single { it.s("name") == "Arsenal vs Chelsea" }
            get(live.s("poster"))
            assertEquals("2 - 1", rendered.last().score)
            assertEquals("47'", rendered.last().minute)
            val halftime = first.single { it.s("name").startsWith("HT |") }
            get(halftime.s("poster"))
            assertEquals("0 - 0", rendered.last().score)
            assertEquals("HT", rendered.last().minute)
            val unknown = first.single { it.s("name").startsWith("Unknown") }
            assertFalse(unknown.s("description").contains("Score:"))
            get(unknown.s("poster")); assertEquals("", rendered.last().score)
            metas(); assertEquals(1, calls)
            ticks.addAndGet(30_000); payload = payload.replace("47'", "48'")
            val updated = metas().single { it.s("id") == live.s("id") }
            assertEquals(live.s("poster"), updated.s("poster"), "Minute ticks must not trigger mass thumbnail reloads")
            get(updated.s("poster")); assertEquals("48'", rendered.last().minute)
            assertEquals(2, calls)
            engine.configure(listOf(source), PresentationConfig(showTimesAndScores = false))
            engine.catalog.refresh().await()
            val withoutDetails = metas()
            assertEquals(first.map { it.s("name") }.toSet(), withoutDetails.map { it.s("name") }.toSet(), "Display preference must not change catalog membership")
            val hidden = withoutDetails.single { it.s("id") == live.s("id") }
            get(hidden.s("poster"))
            assertEquals("", rendered.last().score)
            assertEquals("", rendered.last().minute)
            assertTrue(rendered.last().live)
            assertTrue(rendered.last().kickoffTime.isNotBlank())
            assertFalse(hidden.s("description").contains("Score:"))
            assertFalse(hidden.s("description").contains("Match time:"))
            engine.configure(listOf(source), PresentationConfig(showTimesAndScores = true))
            engine.catalog.refresh().await()
            val shown = metas().single { it.s("id") == live.s("id") }
            get(shown.s("poster"))
            assertEquals("2 - 1", rendered.last().score)
            assertEquals("48'", rendered.last().minute)
        } finally { engine.shutdown() }
    }
}
