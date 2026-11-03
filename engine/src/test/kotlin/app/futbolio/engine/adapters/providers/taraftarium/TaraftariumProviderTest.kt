package app.futbolio.engine.adapters.providers.taraftarium

import app.futbolio.application.ports.*
import app.futbolio.engine.api.Configurations
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

    @Test fun playerMappingDoesNotSelectPreroll() {
        val html = "const CONFIG={baseUrl:'https://cdn.example/',prerollVideo:'https://ads.example/ad.m3u8'};return CONFIG.baseUrl+b+'/mono.m3u8'"
        assertEquals(listOf("https://cdn.example/fixture/mono.m3u8"), TaraftariumPlayerExtractor.extract(html, "https://fixture.example/channel.html?id=fixture"))
    }
}
