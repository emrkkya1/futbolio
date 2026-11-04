package app.futbolio.desktop

import app.futbolio.application.api.PosterModel
import java.awt.Color
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class PosterDesignTest {
    @Test fun liveScoreReplacesVersusAndMinuteAppearsOnlyAtBottomRight() {
        val renderer = DesktopPosters()
        fun image(score: String = "", minute: String = "") = ImageIO.read(renderer.renderMatch(
            model().copy(score = score, minute = minute, live = true, statusVerified = true)).inputStream())
        val plain = image()
        val score = image("2 - 1")
        val minute = image("2 - 1", "47'")
        fun differences(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage, x: Int, y: Int, w: Int, h: Int) =
            (x until x + w).sumOf { column -> (y until y + h).count { row -> a.getRGB(column, row) != b.getRGB(column, row) } }
        assertTrue(differences(plain, score, 265, 280, 110, 48) > 100, "Center score must replace VS")
        assertTrue(differences(score, minute, 498, 546, 112, 48) > 100, "Minute must be visible at bottom right")
        assertEquals(0, differences(score, minute, 0, 0, 640, 500), "Minute must not alter team names or crests")
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "opencode")
        Files.createDirectories(directory)
        Files.write(directory.resolve("futbolio-live-score-poster.jpg"), renderer.renderMatch(
            model().copy(score = "2 - 1", minute = "47'", live = true, statusVerified = true)))
    }

    @Test fun sportsChannelPostersRenderBundledLogoAndRetainUnknownChannelFallback() {
        val renderer = DesktopPosters()
        val channel = PosterModel("beIN Sports 1", "Sports Channels", channelLogo = asset("channels/bein-sport-1.png"))
        val bytes = renderer.renderMatch(channel)
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(640, image.width)
        assertTrue(Color(image.getRGB(140, 280)).red < 100, "Channel logos sit directly on the dark poster, without a white plate")
        assertTrue(bytes.size < 100_000)
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "opencode")
        Files.createDirectories(directory)
        Files.write(directory.resolve("futbolio-channel-poster.jpg"), bytes)
        val unknown = ImageIO.read(renderer.renderMatch(channel.copy(title = "Unknown Channel", channelLogo = null)).inputStream())
        assertEquals(640, unknown.height)
    }
    private fun asset(name: String) = javaClass.getResourceAsStream("/artwork/$name")!!.use { it.readBytes() }
    private fun model() = PosterModel("Arsenal vs Chelsea", "UEFA Europa League", "Arsenal", "Chelsea",
        asset("teams/359.png"), asset("teams/363.png"), "Not Started", "04 Nov 20:45 (Europe/Istanbul)",
        competitionLogo = asset("competitions/uefa.europa.png"), kickoffTime = "20:45", kickoffDate = "04 NOV · UTC+03:00")

    @Test fun matchPosterHasBundledCrestsSquareSizeAndTrueNegativeSpaceBrand() {
        val bytes = DesktopPosters().renderMatch(model())
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(640, image.width); assertEquals(640, image.height)
        assertTrue(Color(image.getRGB(50, 570)).red > 230)
        assertTrue(Color(image.getRGB(60, 565)).red < 100, "The F must be cut out, not painted on the white mark")
        assertTrue(bytes.size < 100_000, "Posters should stay below 100 kB for fast browsing")
        println("Square match poster JPEG: ${bytes.size} bytes")
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "opencode")
        Files.createDirectories(directory)
        Files.write(directory.resolve("futbolio-square-poster.jpg"), bytes)
    }

    @Test fun missingAndMalformedLogosUseTheSameBrandedLayout() {
        val image = ImageIO.read(DesktopPosters().renderMatch(model().copy(homeLogo = byteArrayOf(1, 2, 3), awayLogo = null,
            home = "An Extremely Long Club Name With More Words Than A Poster Can Display", competitionLogo = null)).inputStream())
        assertEquals(640, image.height)
        assertTrue(Color(image.getRGB(50, 570)).red > 230)
        assertTrue(Color(image.getRGB(60, 565)).red < 100)
    }

    @Test fun tffFirstLeaguePostersRenderOfficialBundledCrestsAndCompetitionMark() {
        val bytes = DesktopPosters().renderMatch(model().copy(title = "Boluspor vs Bandırmaspor", league = "TFF 1. Lig",
            home = "Boluspor", away = "Bandırmaspor", homeLogo = asset("teams/tff-3593.png"),
            awayLogo = asset("teams/tff-130.png"), competitionLogo = asset("competitions/tur.2.png"), competitionLightBackground = true))
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(640, image.width)
        assertTrue(bytes.size < 100_000)
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "opencode")
        Files.createDirectories(directory)
        Files.write(directory.resolve("futbolio-tff-first-league-poster.jpg"), bytes)
    }
}
