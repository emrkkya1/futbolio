package app.futbolio.engine

import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.providers.futbolvivo.FutbolVivoProvider
import app.futbolio.engine.adapters.providers.shared.StaticStreamExtractor

import app.futbolio.application.ports.*
import app.futbolio.application.api.*
import app.futbolio.application.workbench.ProviderWorkbench
import app.futbolio.application.resolution.deduplicateStreams
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.*
import app.futbolio.engine.adapters.scores.espn.EspnScoreFeed

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class DiscoveryV3Test {
    private val now = Instant.parse("2026-11-01T19:00:00Z").toEpochMilli()
    private val s = Configurations.sources().first()
    private fun row(source: SourceConfig = s, title: String = "Manchester Utd - Chelsea", league: String = "Premier League", start: Long = now) = Listing(source, title, league, "${source.url}match/1", starts = start)
    @Test fun mergesCrossProviderFixture() {
        val rows = listOf(row(title = "Man Utd - Chelsea"), row(s.copy(id = "other"), "Manchester United vs Chelsea"))
        val entries = MatchNormalizer.aggregate(rows, emptyList(), PresentationConfig(), now)
        assertEquals(1, entries.size); assertEquals(2, entries[0].listings.size); assertEquals("Manchester United vs Chelsea", entries[0].title)
    }
    @Test fun reversedListingsShareStreamsButYouthTeamsRemainSeparate() {
        val entries = MatchNormalizer.aggregate(listOf(row(title = "Arsenal - Chelsea"), row(s.copy(id = "b"), "Arsenal U19 - Chelsea U19"), row(s.copy(id = "c"), "Chelsea - Arsenal")), emptyList(), PresentationConfig(), now)
        assertEquals(2, entries.size)
    }
    @Test fun doesNotMergeDifferentDays() { assertEquals(2, MatchNormalizer.aggregate(listOf(row(), row(s.copy(id = "b"), start = now + 24 * 3_600_000)), emptyList(), PresentationConfig(), now).size) }
    @Test fun unknownKickoffRemainsSeparate() { assertEquals(2, MatchNormalizer.aggregate(listOf(row(start = 0), row(s.copy(id = "b"), start = 0)), emptyList(), PresentationConfig(), now).size) }
    @Test fun normalizesMultilingualLeague() { assertEquals("tur.1", Leagues.find("Trendyol Süper Lig").id); assertEquals("arg.1", Leagues.find("Liga Profesional Argentina").id) }
    @Test fun handlesAwayAtHomeTitles() { val teams = MatchNormalizer.teams("Chelsea at Arsenal", PresentationConfig())!!; assertEquals("Arsenal", teams.first.name); assertEquals("Chelsea", teams.second.name) }
    @Test fun aliasesPreserveQualifiers() { assertNotEquals(MatchNormalizer.teamKey("Arsenal", PresentationConfig()), MatchNormalizer.teamKey("Arsenal Women", PresentationConfig())) }
    @Test fun sourceTimeUsesIanaZoneAcrossMidnight() {
        val listing = row(start = 0).copy(source = s.copy(timeZone = "Europe/Istanbul"), timeLabel = "22:00")
        assertEquals(now, MatchNormalizer.kickoff(listing, now))
    }
    @Test fun futbolVivoUsesUtcAndPreservesOptions() {
        val payload = codec.parseToJsonElement("""{"events":[{"date":"2026-11-01","time":"19:00:00","title":"LaLiga: Athletic Club vs Real Sociedad","sport":"futbol","embeds":[{"name":"ESPN","lang":"Español","iframe":"https://player.example/live"}]},{"title":"NBA","sport":"basket","embeds":[]}]}""").obj()
        val rows = FutbolVivoProvider.parse(Configurations.sources().first { it.kind == "futbolvivo" }, payload)
        assertEquals(1, rows.size); assertEquals(now, rows[0].starts); assertEquals("LaLiga", rows[0].league); assertEquals("Athletic Club vs Real Sociedad", rows[0].title); assertEquals("Español", rows[0].players.single().language)
    }
    @Test fun domainCandidatesAreBoundedAndOptIn() {
        val recovery = DomainRecovery(FixtureUpstream(), ProviderRegistry()); val source = SourceConfig("test", "papaz", "Test", "https://papaz100.example/")
        assertEquals(listOf(source.url), recovery.candidates(source))
        val candidates = recovery.candidates(source.copy(numberedDomainWindow = 3))
        assertEquals(6, candidates.size); assertTrue(candidates.all { Web.url(it).host.endsWith(".example") })
    }
    @Test fun validatesFallbackLimitsAndTimezones() { assertFails { s.copy(fallbackUrls = List(6) { "https://example.com/" }).validate() }; assertFails { s.copy(timeZone = "made-up").validate() } }
    @Test fun recoversThroughExplicitFallback() = runBlocking {
        val fixture = FixtureUpstream(); val source = fixture.source.copy(url = "https://unavailable.example/", fallbackUrls = listOf(fixture.source.url))
        val result = DomainRecovery(fixture, ProviderRegistry()).discover(source); assertEquals(fixture.source.url, result.activeUrl); assertEquals(1, result.listings.size)
    }
    @Test fun deduplicatesOnlySameMediaAndHeaderProfile() {
        val a = StreamChoice("A", "https://cdn.example/x.m3u8?a=1&b=2", providers = listOf("PPV"))
        val b = a.copy(title = "B", url = "https://cdn.example/x.m3u8?b=2&a=1", providers = listOf("Papaz"))
        assertEquals(listOf("PPV", "Papaz"), deduplicateStreams(listOf(a, b)).single().providers)
        assertEquals(2, deduplicateStreams(listOf(a, b.copy(headers = SourceHeaders("https://other.example/")))).size)
        assertEquals(2, deduplicateStreams(listOf(a, b.copy(url = "https://cdn.example/x.m3u8?a=9&b=2"))).size)
    }
    private fun fixture(state: MatchState, observed: Long = now) = ScoreFixture("123", Leagues.find("Premier League"), Team("Manchester United", "1", "https://a.espncdn.com/logo.png", listOf("Man Utd")), Team("Chelsea", "2"), now, state, "1", "0", "HT", observed)
    @Test fun halftimeVisibleAndEndedHidden() {
        assertEquals(MatchState.HALFTIME, MatchNormalizer.aggregate(listOf(row(title = "Man Utd - Chelsea")), listOf(fixture(MatchState.HALFTIME)), PresentationConfig(), now).single().state(now))
        assertTrue(MatchNormalizer.aggregate(listOf(row(title = "Man Utd - Chelsea")), listOf(fixture(MatchState.FINISHED)), PresentationConfig(), now).isEmpty())
        assertEquals(1, MatchNormalizer.aggregate(listOf(row(title = "Man Utd - Chelsea")), listOf(fixture(MatchState.FINISHED)), PresentationConfig(hideFinished = false), now).size)
    }
    @Test fun finalStatusDoesNotRevertWhenItAges() { val entries = MatchNormalizer.aggregate(listOf(row(title = "Man Utd - Chelsea")), listOf(fixture(MatchState.FINISHED, now - 600_000)), PresentationConfig(), now); assertTrue(entries.isEmpty()) }
    @Test fun externalMatchMustBeUnambiguous() { val entries = MatchNormalizer.aggregate(listOf(row(title = "Man Utd - Chelsea")), listOf(fixture(MatchState.LIVE), fixture(MatchState.LIVE).copy(id = "456")), PresentationConfig(), now); assertNull(entries.single().fixture) }
    @Test fun scoreboardParsesStatusTeamsAndLogos() {
        val json = """{"events":[{"id":"1","date":"2026-11-01T19:00Z","status":{"type":{"name":"STATUS_HALFTIME","state":"in","completed":false,"shortDetail":"HT"}},"competitions":[{"competitors":[{"homeAway":"home","score":"1","team":{"id":"10","displayName":"Arsenal","logo":"https://a.espncdn.com/a.png"}},{"homeAway":"away","score":"0","team":{"id":"11","displayName":"Chelsea"}}]}]}]}"""
        val f = EspnScoreFeed.parse(json, Leagues.find("Premier League"), now).single(); assertEquals(MatchState.HALFTIME, f.state); assertEquals(now, f.starts); assertEquals("Arsenal", f.home.name); assertTrue(f.home.logo.startsWith("https://"))
    }
    @Test fun workbenchSeparatesTransportFromPlayback() = runBlocking {
        val fixture = FixtureUpstream(); val workbench = ProviderWorkbench(this, fixture, StreamResolver(this, fixture, ProviderRegistry()), ProviderRegistry(), SegmentProbe(fixture))
        val report = workbench.test(fixture.source); assertEquals(ProbePhase.COMPLETE, report.phase)
        val listing = report.listings.single(); assertTrue(listing.choices.isNotEmpty())
        val choice = listing.choices.first { it.stage == ProbeStage.SEGMENTS_FETCHED }
        assertEquals(PlaybackStatus.NOT_CONFIRMED, choice.playback)
        workbench.confirmPlayback(fixture.source.id, choice.id, true)
        assertEquals(PlaybackStatus.CONFIRMED_BY_USER, workbench.reports.value[fixture.source.id]!!.listings.single().choices.first { it.id == choice.id }.playback)
        assertFalse(report.export().contains("cdn.example")); assertEquals(1, workbench.entries().size)
    }
    @Test fun extractorFindsClapprSrcFromAnyDomain() {
        val html = """
        <!DOCTYPE html><html><body><div id="player"></div>
        <script>var player = cdnpeer.clappr(Clappr, {"id":"2yaqpqwt","src":"https://y2fzdgxl.fubo18.com:443/espn3/mono.m3u8?token=abc123-bd-1793584000-1793566000","g":"0j8a"});</script>
        </body></html>"""
        val result = StaticStreamExtractor.extract(html, "https://la18hd.su/vivo/canal.php?stream=espn3")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("y2fzdgxl.fubo18.com"), result.toString())
        assertTrue(result[0].contains("mono.m3u8"), result.toString())
    }
    @Test fun extractorFindsJwplayerFileFromAnyDomain() {
        val html = """
        <!DOCTYPE html><html><body><script>
        jwplayer("player").setup({file:"https://cdn.example.com/stream.m3u8?token=xyz",image:"poster.jpg"});
        </script></body></html>"""
        val result = StaticStreamExtractor.extract(html, "https://example.com/embed")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("cdn.example.com"), result.toString())
        assertTrue(result[0].contains("stream.m3u8"), result.toString())
    }
    @Test fun extractorIgnoresNonStreamUrls() {
        val html = """
        <script src="https://cdn.jsdelivr.net/npm/clappr.min.js"></script>
        <link rel="stylesheet" href="https://cdn.example.com/style.css">
        <img src="https://cdn.example.com/logo.png">
        <script>var player = cdnpeer.clappr(Clappr, {"src":"https://cdn.example.com/live.m3u8?token=abc"});</script>"""
        val result = StaticStreamExtractor.extract(html, "https://example.com/")
        assertEquals(1, result.size)
        assertTrue(result[0].contains("live.m3u8"), result.toString())
    }
}
