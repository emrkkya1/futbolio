package app.futbolio.engine

import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.domain.settings.DisplayTime
import app.futbolio.engine.adapters.providers.futbolvivo.FutbolVivoProvider
import app.futbolio.engine.api.Configurations
import java.time.Instant
import kotlin.test.*

/** Real 7 November agenda fields; player endpoints replaced with inert fixture URLs. */
class CapturedScheduleCorrectionTest {
    private val now = Instant.parse("2026-11-07T16:30:00Z").toEpochMilli()
    private val options = PresentationConfig(hideFinished = false)
    private fun captured() = FutbolVivoProvider.parse(Configurations.sources().first { it.kind == "futbolvivo" },
        codec.parseToJsonElement(requireNotNull(javaClass.getResource("/providers/states/2026-11-07/futbolvivo-agenda.json")).readText()).obj())
    private fun row(provider: String, title: String, league: String, start: String) = Listing(
        SourceConfig(provider, provider, provider, "https://$provider.example/"), title, league,
        "https://$provider.example/game", starts = Instant.parse(start).toEpochMilli())

    @Test fun galatasarayAmedShows1900TurkeyInsteadOf1400AndKeepsEveryProvider() {
        val fv = captured().single { it.title.startsWith("Galatasaray") }
        assertEquals(Instant.parse("2026-11-07T16:00:00Z").toEpochMilli(), fv.starts)
        assertEquals("07 Nov 19:00 (Europe/Istanbul)", DisplayTime.kickoff(fv.starts, options))
        val rows = listOf(fv,
            row("papaz", "Galatasaray - Amed SK", "Türkiye Süper Lig", "2026-11-07T16:00:00Z").copy(starts = 0, timeLabel = "19:00", observedAt = now),
            row("taraftarium", "Galatasaray - Amedspor", "Trendyol Süper Lig", "2026-11-07T16:00:00Z").copy(starts = 0, timeLabel = "19:00", observedAt = now))
        val entry = MatchNormalizer.aggregate(rows, emptyList(), options, now).single()
        assertEquals(3, entry.listings.size)
        assertEquals("Galatasaray vs Amed SFK", entry.title)
        assertEquals(fv.starts, entry.starts)
        assertFalse(entry.needsFixtureLookup)
    }

    @Test fun independentlyVerifiedEnglishMatchesAndUtcDateRolloverUseEasternTime() {
        val rows = captured()
        for (title in listOf("Arsenal vs Hull City", "Fulham vs Newcastle United")) {
            assertEquals(Instant.parse("2026-11-07T15:00:00Z").toEpochMilli(), rows.single { it.title == title }.starts)
        }
        val toluca = rows.single { it.title == "Toluca vs Monterrey" }
        assertEquals(Instant.parse("2026-11-08T01:00:00Z").toEpochMilli(), toluca.starts)
        val merged = MatchNormalizer.aggregate(listOf(toluca,
            row("ppv", toluca.title, "Liga MX", "2026-11-08T01:00:00Z")), emptyList(), options, now).single()
        assertEquals(2, merged.listings.size)
    }

    @Test fun observedPortugueseAndFrenchAliasesDoNotCreateDuplicateMatches() {
        val rows = captured().filter { it.league in listOf("Ligue 1", "Primeira Liga") } + listOf(
            row("ppv", "Paris Saint-Germain vs. Troyes", "Ligue 1", "2026-11-07T19:45:00Z"),
            row("taraftarium", "PSG - Troyes", "Fransa Ligue 1", "2026-11-07T20:05:00Z"),
            row("720pstream", "Paris Saint-Germain vs Troyes", "French Ligue 1", "2026-11-07T20:05:00Z"),
            row("taraftarium", "Sporting Lisbon - Moreirense", "Portekiz Liga NOS", "2026-11-07T20:30:00Z"),
            row("720pstream", "Sporting CP vs Moreirense", "Portuguese Primeira Liga", "2026-11-07T20:30:00Z"))
        val entries = MatchNormalizer.aggregate(rows, emptyList(), options, now)
        assertEquals(2, entries.size)
        assertEquals(setOf(4, 3), entries.map { it.listings.size }.toSet())
        assertTrue(entries.none { it.ambiguous })
    }

    @Test fun peterboroughAliasPreventsTimeConflictFromLeavingABridgedDuplicateGroup() {
        val rows = listOf(
            row("papaz", "Peterborough United - Reading", "İngiltere FA Cup 1.Tur", "2026-11-07T15:00:00Z"),
            row("futbolvivo", "Peterborough vs Reading", "FA Cup", "2026-11-07T20:00:00Z"),
            row("720pstream", "Peterborough United vs Reading", "English FA Cup", "2026-11-07T20:00:00Z"))
        val entry = MatchNormalizer.aggregate(rows, emptyList(), options, now).single()
        assertEquals(3, entry.listings.size)
        assertEquals(Instant.parse("2026-11-07T20:00:00Z").toEpochMilli(), entry.starts)
        assertEquals("eng.fa", entry.league.id)
        assertFalse(entry.ambiguous)
        assertTrue(entry.needsFixtureLookup)
    }
}
