package app.futbolio.domain.match

import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import java.time.Instant
import kotlin.test.*

class CatalogCurationTest {
    private val now = Instant.parse("2026-11-03T16:00:00Z").toEpochMilli()
    private val hour = CatalogVisibility.HOUR
    private val options = PresentationConfig()
    private fun row(id: String = "a", title: String = "Galatasaray vs Stuttgart", league: String = "UEFA Europa League", start: Long = now) =
        Listing(SourceConfig(id, "fixture", id, "https://fixture.example/"), title, league, "https://fixture.example/$id", starts = start)
    private fun aggregate(vararg rows: Listing) = MatchNormalizer.aggregate(rows.toList(), emptyList(), options.copy(hideFinished = false), now)
    private fun visible(entry: CatalogEntry, at: Long = now) = CatalogSelection.select(listOf(entry), CatalogQuery("football"), at).isNotEmpty()

    @Test fun upcomingWindowIsInclusiveAtTwentyFourHoursAndNeverIncludesUndatedMatches() {
        assertTrue(visible(aggregate(row(start = now + 18 * hour)).single()))
        assertTrue(visible(aggregate(row(start = now + 24 * hour)).single()))
        assertFalse(visible(aggregate(row(start = now + 24 * hour + 1)).single()))
        assertFalse(visible(aggregate(row(start = 0)).single()))
    }

    @Test fun estimatedLeagueAndTournamentExpiryHaveExactBoundariesUnlessVerifiedLive() {
        val league = aggregate(row(league = "Premier League", start = now - 2 * hour)).single()
        val cup = aggregate(row(start = now - 3 * hour)).single()
        assertTrue(visible(league, now - 1))
        assertFalse(visible(league))
        assertTrue(visible(cup, now - 1))
        assertFalse(visible(cup))
        assertTrue(visible(league.copy(fixture = fixture(league.league, league.starts, MatchState.LIVE))),
            "Fresh scoreboard status takes precedence over estimated expiry")
    }

    @Test fun cupMetadataIsExplicitIncludingEnglishCupsAndMlsIsNotACup() {
        for (name in listOf("FA Cup", "Carabao Cup", "Turkish Cup", "World Cup", "Champions League")) {
            val league = Leagues.find(name)
            assertTrue(Leagues.allowed(league), name)
            assertTrue(league.tournament, name)
            assertTrue(visible(aggregate(row(league = name, start = now - 2 * hour)).single()), name)
        }
        for (name in listOf("MLS", "Premier League", "LaLiga", "Süper Lig", "TFF 1. Lig", "Primeira Liga", "Eredivisie")) {
            assertTrue(Leagues.allowed(Leagues.find(name)), name)
            assertFalse(Leagues.find(name).tournament, name)
        }
    }

    @Test fun unapprovedAndUnknownLeaguesAreHiddenEvenForTurkishTeamsAndSearch() {
        for (league in listOf("Chile Primera Division", "Liga MX", "Football", "Brasileirão Série A")) {
            val entry = aggregate(row(league = league)).single()
            for (category in listOf("football", "turkish", "live", "upcoming")) {
                assertTrue(CatalogSelection.select(listOf(entry), CatalogQuery(category, "Galatasaray"), now).isEmpty())
            }
        }
    }

    @Test fun channelsStaySeparateAndSearchStillUsesCuration() {
        val channel = aggregate(row(start = 0, league = "Football").copy(kind = ListingKind.CHANNEL)).single()
        assertEquals(1, CatalogSelection.select(listOf(channel), CatalogQuery("channels"), now).size)
        val hidden = aggregate(row(start = 0, league = "Premier League")).single()
        assertTrue(CatalogSelection.select(listOf(hidden), CatalogQuery("search", "Galatasaray"), now).isEmpty())
    }

    @Test fun statusUsesScheduleButNeverResurrectsConfirmedFinalResults() {
        val entry = aggregate(row(league = "Premier League")).single()
        assertEquals(MatchState.SCHEDULED, entry.state(now - 1))
        assertEquals(MatchState.LIVE, entry.state(now))
        assertEquals(MatchState.FINISHED, entry.state(now + 2 * hour))
        for (state in listOf(MatchState.FINISHED, MatchState.CANCELLED, MatchState.POSTPONED)) {
            val verified = entry.copy(fixture = fixture(entry.league, now, state))
            assertFalse(visible(verified), state.name)
        }
        val final = entry.copy(fixture = fixture(entry.league, now, MatchState.FINISHED).copy(observedAt = now - hour))
        assertEquals(MatchState.FINISHED, final.state(now))
        assertFalse(visible(final))
        assertTrue(visible(entry.copy(fixture = fixture(entry.league, now, MatchState.HALFTIME))))
    }

    @Test fun liveMatchesSortAheadOfUpcomingThenByKickoff() {
        val rows = listOf(row("future", start = now + hour), row("live", title = "Arsenal vs Chelsea", start = now - hour))
        val entries = MatchNormalizer.aggregate(rows, emptyList(), options, now)
        assertEquals("Arsenal vs Chelsea", CatalogSelection.select(entries, CatalogQuery("football"), now).first().title)
    }

    @Test fun galatasarayStuttgartConflictingTimesProduceOneEntryWithEveryStream() {
        val early = Instant.parse("2026-11-03T11:00:00Z").toEpochMilli() // 14:00 Istanbul
        val late = Instant.parse("2026-11-03T17:45:00Z").toEpochMilli() // 20:45 Istanbul
        val entries = aggregate(row(start = early), row("b", "Galatasaray v VfB Stuttgart", start = late))
        assertEquals(1, entries.size)
        assertEquals(2, entries.single().listings.size)
        assertTrue(entries.single().needsFixtureLookup)
        assertFalse(entries.single().ambiguous)
        assertEquals(late, entries.single().starts)
        val corrected = MatchNormalizer.aggregate(entries.single().listings,
            listOf(fixture(Leagues.find("UEFA Europa League"), late, MatchState.SCHEDULED)), options, now).single()
        assertEquals(late, corrected.starts)
        assertEquals(2, corrected.listings.size)
        assertFalse(corrected.needsFixtureLookup)
    }

    @Test fun harmlessClubDecorationsMergeWithoutFixtureCalls() {
        val entry = aggregate(row(title = "Galatasaray SK vs Stuttgart"), row("b", "Galatasaray vs VfB Stuttgart", start = now + 30 * 60_000)).single()
        assertEquals(2, entry.listings.size)
        assertFalse(entry.needsFixtureLookup)
    }

    @Test fun distinctSquadsAndDifferentDatesRemainSeparate() {
        for (other in listOf("Galatasaray U19 vs Stuttgart U19",
            "Galatasaray Women vs Stuttgart Women", "Galatasaray B vs Stuttgart B")) {
            assertEquals(2, aggregate(row(), row("b", other)).size, other)
        }
        assertEquals(2, aggregate(row(), row("b", start = now + 24 * hour)).size)
        assertEquals(1, aggregate(row(), row("b", "Galatasaray vs Fenerbahçe")).size)
        assertEquals(1, aggregate(row(), row("b", "Stuttgart vs Galatasaray")).size)
    }

    @Test fun conflictingKnownLeaguesStayHiddenUntilFixtureResolvesThem() {
        val rows = listOf(row(league = "Champions League"), row("b", league = "Europa League"))
        val provisional = MatchNormalizer.aggregate(rows, emptyList(), options, now).single()
        assertTrue(provisional.ambiguous)
        assertTrue(provisional.needsFixtureLookup)
        assertFalse(visible(provisional))
        val corrected = MatchNormalizer.aggregate(rows, listOf(fixture(Leagues.find("Europa League"), now, MatchState.LIVE)), options, now).single()
        assertTrue(visible(corrected))
        assertEquals("uefa.europa", corrected.league.id)
    }

    @Test fun unknownLeagueListingCanJoinAKnownMatchButCannotChangeItsCompetition() {
        val entry = aggregate(row(), row("b", league = "Football", start = now + 30 * 60_000)).single()
        assertEquals("uefa.europa", entry.league.id)
        assertEquals(2, entry.listings.size)
        assertTrue(visible(entry))
    }

    @Test fun clockOnlyScheduleUsesAnchoredLocalDateWithoutRollingForwardAfterMidnight() {
        val at = Instant.parse("2026-11-03T20:00:00Z").toEpochMilli() // 23:00 Istanbul
        val listing = row(start = 0).copy(timeLabel = "20:00", observedAt = at)
        val kickoff = Instant.parse("2026-11-03T17:00:00Z").toEpochMilli()
        assertEquals(kickoff, MatchNormalizer.kickoff(listing, at))
        assertEquals(kickoff, MatchNormalizer.kickoff(listing, at + 4 * hour))
    }

    @Test fun aliasesAndSeasonPrefixesResolveWithoutSubstringLeagueGuessing() {
        assertEquals("eng.1", Leagues.find("2026/27 England Premier League").id)
        assertEquals("tur.cup", Leagues.find("Ziraat Türkiye Kupası").id)
        assertFalse(Leagues.allowed(Leagues.find("Chile Premier League")))
        assertEquals("uefa.champions", Leagues.find("UEFA Şampiyonlar Ligi").id)
        assertEquals("uefa.champions", Leagues.find("UEFA Şampiyonlar Ligi Lig Aşaması").id)
        assertEquals("uefa.europa", Leagues.find("UEFA Avrupa Ligi").id)
        assertFalse(Leagues.allowed(Leagues.find("AFC Şampiyonlar Ligi")))
    }

    @Test fun genreFilteringCombinesWithSearchAndPaginationAcrossCatalogs() {
        val entries = MatchNormalizer.aggregate(listOf(
            row("pl", "Arsenal vs Chelsea", "Premier League", now + hour),
            row("laliga", "Real Madrid vs Barcelona", "LaLiga", now + 2 * hour),
            row("tur", "Galatasaray vs Fenerbahçe", "Süper Lig", now + 3 * hour),
        ), emptyList(), options, now)
        assertEquals(listOf("Arsenal vs Chelsea"), CatalogSelection.select(entries,
            CatalogQuery("all-matches", search = "arsenal", genre = "Premier League"), now).map { it.title })
        assertTrue(CatalogSelection.select(entries, CatalogQuery("all-matches", search = "arsenal", genre = "LaLiga"), now).isEmpty())
        assertEquals("Galatasaray vs Fenerbahçe", CatalogSelection.select(entries,
            CatalogQuery("turkish", genre = "Süper Lig"), now).single().title)
        assertEquals(1, CatalogSelection.select(entries, CatalogQuery("search", search = "real madrid"), now).size)
        assertEquals("Real Madrid vs Barcelona", CatalogSelection.select(entries,
            CatalogQuery("all-matches", genre = "LaLiga", skip = 0), now).single().title)
    }

    private fun fixture(league: League, starts: Long, state: MatchState) =
        ScoreFixture("fixture", league, Team("Galatasaray"), Team("VfB Stuttgart"), starts, state, "", "", "", now)
}
