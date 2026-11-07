package app.futbolio.domain.match

import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlin.test.*

class WeightedMatchPriorityTest {
    private val now = 1_800_000_000_000L
    private val config = PresentationConfig()
    private fun entry(id: String, home: String, away: String, league: String = "Premier League", starts: Long = now - 60_000) = CatalogEntry(
        id, "$home vs $away", Leagues.find(league), starts,
        listOf(Listing(SourceConfig(id, "fixture", id, "https://fixture.example/"), "$home vs $away", league,
            "https://fixture.example/$id", starts = starts)), Team(home), Team(away))
    private fun score(entry: CatalogEntry) = LiveMatchPriority.score(entry, config, now)
    private fun live(entry: CatalogEntry, minute: String, homeScore: String = "1", awayScore: String = "1", at: Long = now) = entry.copy(
        fixture = ScoreFixture(entry.id, entry.league, entry.home!!, entry.away!!, entry.starts, MatchState.LIVE,
            homeScore, awayScore, "Live", at, minute))

    @Test fun favouritesRemainDominantEvenAgainstAMarqueeLateEuropeanMatch() {
        val european = live(entry("elite", "Real Madrid", "Barcelona", "Champions League"), "90+4'")
        for (club in listOf("Galatasaray", "Fenerbahçe", "Beşiktaş"))
            assertTrue(score(entry(club, club, "Unknown club", "Turkish Cup")) > score(european))
    }

    @Test fun bothTeamsAndKnownDerbiesMatterNotJustTheLeague() {
        val ordinary = entry("ordinary", "Unknown Home", "Unknown Away")
        val oneClub = entry("one", "Liverpool", "Unknown Away")
        val marquee = entry("marquee", "Liverpool", "Bayern Munich")
        val derby = entry("derby", "Liverpool", "Manchester United")
        assertTrue(score(oneClub) > score(ordinary))
        assertTrue(score(marquee) > score(oneClub))
        assertTrue(score(derby) > score(marquee))
        assertEquals(score(derby), score(entry("reversed", "Manchester United", "Liverpool")))
        assertTrue(score(entry("istanbul", "Galatasaray", "Fenerbahçe", "Süper Lig")) >
            score(entry("one-favourite", "Galatasaray", "Unknown Away", "Süper Lig")))
    }

    @Test fun weightingCanPromoteAMajorMatchOverAnOrdinaryHigherTierFixture() {
        val marquee = entry("marquee", "Liverpool", "Bayern Munich", "Europa League")
        val ordinary = entry("ordinary", "Unknown Home", "Unknown Away", "Champions League")
        assertTrue(score(marquee) > score(ordinary), "This is weighted relevance, not a strict league ladder")
        assertTrue(score(ordinary) > score(entry("ordinary-pl", "Unknown Home", "Unknown Away")))
    }

    @Test fun verifiedLateCloseGamesGetOnlyAModestBoundedBonus() {
        val base = entry("game", "Arsenal", "Chelsea")
        val early = live(base, "20'")
        val closeLate = live(base, "90+4'")
        val blowout = live(base, "90+4'", "5", "0")
        assertTrue(score(closeLate) > score(early))
        assertEquals(90, LiveMatchPriority.breakdown(closeLate, config, now).urgency)
        assertEquals(0, LiveMatchPriority.breakdown(blowout, config, now).urgency)
        assertTrue(score(closeLate) - score(early) <= 100)
    }

    @Test fun missingStaleMalformedAndFutureEspnDataHaveNeutralUrgency() {
        val base = entry("base", "Arsenal", "Chelsea")
        for (row in listOf(base, live(base, "90'", at = now - 300_001), live(base, "90'", at = now + 60_000),
            live(base, "unknown"), live(base, "999+99'"), live(base, "90'", "?", "1"))) {
            assertEquals(0, LiveMatchPriority.breakdown(row, config, now).urgency)
        }
        assertEquals(180, LiveMatchPriority.breakdown(base, config, now).activity, "Estimated-live games are not penalized just for lacking ESPN data")
    }

    @Test fun aliasesAreRecognizedButSquadQualifiersCannotBorrowSeniorPopularity() {
        assertEquals(score(entry("canonical", "Manchester United", "Paris Saint-Germain")), score(entry("alias", "Man Utd", "PSG")))
        val youth = entry("youth", "Galatasaray U19", "Liverpool Women", "Champions League")
        val breakdown = LiveMatchPriority.breakdown(youth, config, now)
        assertEquals(0, breakdown.favourites)
        assertEquals(0, breakdown.teams)
        assertEquals(0, breakdown.matchup)
        val custom = config.copy(teamAliases = mapOf("GS" to "Galatasaray"))
        assertEquals(score(entry("canonical-gs", "Galatasaray", "Liverpool")),
            LiveMatchPriority.score(entry("custom-gs", "GS", "Liverpool"), custom, now))
    }

    @Test fun catalogueAndPrefetchUseExactlyTheSameScoresAndTieBreaking() {
        val rows = listOf(entry("low", "Unknown Home", "Unknown Away"), entry("marquee", "Liverpool", "Bayern Munich"),
            entry("gs", "Galatasaray", "Unknown Away", "Süper Lig"), live(entry("late", "Arsenal", "Chelsea"), "85'"),
            entry("cl", "Unknown Club", "Other Club", "Champions League"), entry("tie", "Another Home", "Another Away"))
        val catalogue = CatalogSelection.select(rows, CatalogQuery("live"), now, config)
        val selected = LiveMatchPriority.select(rows.reversed(), config, now, 4)
        assertEquals(catalogue.take(4), selected)
        assertEquals(catalogue, CatalogSelection.select(rows.reversed(), CatalogQuery("live"), now, config))
        assertEquals(catalogue.map { score(it) }.sortedDescending(), catalogue.map { score(it) })
    }

    @Test fun startingSoonGetsMoreActivityWeightThanAFixtureHoursAway() {
        val soon = entry("soon", "Arsenal", "Chelsea", starts = now + 5 * 60_000)
        val later = entry("later", "Arsenal", "Chelsea", starts = now + 12 * 60 * 60_000)
        assertTrue(score(soon) > score(later))
    }
}
