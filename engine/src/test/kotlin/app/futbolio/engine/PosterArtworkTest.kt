package app.futbolio.engine

import app.futbolio.engine.adapters.posters.BundledArtwork
import app.futbolio.engine.adapters.stremio.wire.StremioWire
import app.futbolio.application.api.HostConfig
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlin.test.*

class PosterArtworkTest {
    @Test fun channelLogosAreOfflineAndNumberedChannelsDoNotBorrowEachOthersBranding() {
        listOf("beIN 1", "beIN Sports 2 HD", "S-Sport 1", "Tivibuspor 4", "SMARTSPOR 2", "TRT Spor Yıldız", "A Spor", "TV 8.5", "Eurosport 2", "NBA TV").forEach {
            assertNotNull(BundledArtwork.channel(it), it)
        }
        assertContentEquals(BundledArtwork.channel("beIN 1"), BundledArtwork.channel("beIN Sports 1 HD"))
        assertNull(BundledArtwork.channel("beIN Sports 12"))
        assertNull(BundledArtwork.channel("Unknown sports channel"))
    }
    @Test fun bundledAssetsAreAvailableOfflineAndAliasesDoNotGuessUnknownClubs() {
        assertNotNull(BundledArtwork.background)
        assertNotNull(BundledArtwork.team("Arsenal"))
        assertNotNull(BundledArtwork.team("Chelsea"))
        assertNotNull(BundledArtwork.team("Galatasaray"))
        assertContentEquals(BundledArtwork.team("Stuttgart"), BundledArtwork.team("VfB Stuttgart"))
        assertNotNull(BundledArtwork.competition("uefa.europa"))
        assertNull(BundledArtwork.team("Unknown team fixture"))
        assertNull(BundledArtwork.team("Arsenal U19"))
        assertNull(BundledArtwork.team("Arsenal Women"))
    }

    @Test fun matchCopyIsSquareCleanAndHonestAboutStatus() {
        val now = 1_793_721_600_000L
        val row = Listing(SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/"),
            "Arsenal [LIVE] vs Chelsea", "Premier League", "https://fixture.example/match", starts = now)
        val entry = MatchNormalizer.aggregate(listOf(row), emptyList(), PresentationConfig(), now).single()
        val meta = StremioWire(HostConfig()).meta(entry, PresentationConfig(), now)
        assertEquals("Arsenal vs Chelsea", meta.s("name"))
        assertEquals("square", meta.s("posterShape"))
        assertTrue(meta.s("poster").contains("?v=7"))
        assertTrue(meta.s("description").contains("Kickoff:"))
        assertTrue(meta.s("description").contains("kickoff-based estimate"))
        assertTrue(meta.s("description").contains("1 provider"))
        assertFalse(meta.s("description").contains("ESPN public scoreboard"))
    }

    @Test fun tffFirstLeagueHasEveryCurrentClubAndItsCompetitionLogoOffline() {
        val clubs = listOf("Antalyaspor", "Bursaspor", "Sarıyer", "Iğdır FK", "Ankara Keçiörengücü",
            "Manisa FK", "Vanspor FK", "Sivasspor", "Muğlaspor", "Esenler Erokspor", "Pendikspor",
            "İstanbulspor", "Bandırmaspor", "Fatih Karagümrük", "Ümraniyespor", "Kayserispor", "Boluspor",
            "Bodrum FK", "Mardin 1969 Spor", "Batman Petrolspor")
        assertNotNull(BundledArtwork.competition("tur.2"))
        assertTrue(BundledArtwork.competitionLightBackground("tur.2"))
        clubs.forEach { name ->
            assertNotNull(BundledArtwork.team(name), "Missing TFF 1. Lig crest: $name")
            assertNull(BundledArtwork.team("$name U19"), "Never borrow senior crests for youth fixtures")
        }
        for ((alias, club) in listOf("Bandirma Spor" to "Bandırmaspor", "Van Spor FK" to "Vanspor FK",
            "Keciorengucu" to "Ankara Keçiörengücü", "BB Bodrumspor" to "Bodrum FK",
            "Alagoz Holding Igdir Futbol Kulubu" to "Iğdır FK", "EMİNEVİM ÜMRANİYESPOR" to "Ümraniyespor")) {
            assertNotNull(BundledArtwork.team(alias), alias)
            assertContentEquals(BundledArtwork.team(club), BundledArtwork.team(alias), alias)
        }
        assertEquals("tur.2", app.futbolio.domain.match.Leagues.find("Turkey 1. Lig").id)
        assertEquals("tur.2", app.futbolio.domain.match.Leagues.find("Trendyol 1.Lig").id)
    }
}
