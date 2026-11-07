package app.futbolio.engine.adapters.providers.futbolvivo

import app.futbolio.engine.api.Configurations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import app.futbolio.application.ports.*
import kotlinx.coroutines.runBlocking

class FutbolVivoProviderTest {
    private val source = Configurations.sources().first { it.kind == "futbolvivo" }

    @Test fun utcFixturePreservesPlayerLanguage() {
        val json = requireNotNull(javaClass.getResource("/providers/futbolvivo/agenda.json")).readText()
        val row = FutbolVivoProvider.parse(source.copy(timeZone = "UTC"), Json.parseToJsonElement(json).jsonObject).single()
        assertEquals(1893456000000, row.starts)
        assertEquals("English", row.players.single().language)
    }

    @Test fun malformedCatalogIsNotSilentlySuccessfulDiscovery() {
        assertFailsWith<IllegalArgumentException> { FutbolVivoProvider.parse(source, Json.parseToJsonElement("{}").jsonObject) }
    }

    @Test fun easternScheduleUsesDaylightSavingRulesAndPreservesCustomTimezoneOverrides() {
        for ((date, utc) in listOf("2026-07-07" to "2026-07-07T15:00:00Z", "2026-11-07" to "2026-11-07T16:00:00Z")) {
            val payload = Json.parseToJsonElement("""{"events":[{"sport":"futbol","title":"Süper Lig: Galatasaray vs Amed","date":"$date","time":"11:00:00"}]}""").jsonObject
            assertEquals(java.time.Instant.parse(utc).toEpochMilli(), FutbolVivoProvider.parse(source, payload).single().starts)
            assertEquals(java.time.Instant.parse("${date}T11:00:00Z").toEpochMilli(),
                FutbolVivoProvider.parse(source.copy(timeZone = "UTC"), payload).single().starts)
        }
    }

    @Test fun redirectedHomeIsUsedForBrowserLinksAndPlayerReferers() = runBlocking {
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                return if (url == source.url) UpstreamResponse(200, "https://current.example/", emptyMap(), "<html></html>".byteInputStream())
                else {
                    assertEquals("https://current.example/api/agenda", url)
                    UpstreamResponse(200, url, emptyMap(), """{"events":[{"date":"2030-01-01","time":"00:00:00","title":"Premier League: Home vs Away","sport":"futbol","embeds":[{"iframe":"https://player.example/live"}]}]}""".byteInputStream())
                }
            }
        }
        val result = FutbolVivoProvider.discover(source, net)
        assertEquals("https://current.example/", result.activeUrl)
        assertTrue(result.listings.single().page.startsWith("https://current.example/"))
    }

    @Test fun mislabeledHockeyAndAmericanFootballAreNotSoccerFixtures() {
        val payload = """{"events":[
          {"sport":"futbol","title":"NHL: Home vs Away","date":"2030-01-01","time":"00:00:00"},
          {"sport":"futbol","title":"NFL: Home vs Away","date":"2030-01-01","time":"00:00:00"},
          {"sport":"futbol","title":"Premier League: Arsenal vs Chelsea","date":"2030-01-01","time":"00:00:00"}] }"""
        assertEquals(listOf("Arsenal vs Chelsea"), FutbolVivoProvider.parse(source, Json.parseToJsonElement(payload).jsonObject).map { it.title })
    }
}
