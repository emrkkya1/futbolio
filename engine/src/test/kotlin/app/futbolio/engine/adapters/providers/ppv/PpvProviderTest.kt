package app.futbolio.engine.adapters.providers.ppv

import app.futbolio.engine.*

import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.providers.ppv.PpvProvider

import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.*
import app.futbolio.engine.api.Configurations

import kotlinx.coroutines.*
import kotlin.test.*

class PpvProviderTest {
    private val source = Configurations.sources().first()
    private val item = Listing(source, "Fixture match", "Football", "https://ppv.st/live/league/2030-01-01/home-away",
        players = listOf(Player("https://old.example/embed")))
    private class Fixture(private val responses: Map<String, String>) : Upstream {
        val requests = mutableListOf<Pair<String, SourceHeaders>>()
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
            requests += url to headers
            val text = responses[url] ?: error("Unexpected fixture request: $url")
            return UpstreamResponse(200, url, emptyMap(), text.byteInputStream())
        }
    }
    private val endpoint = "https://api.ppv.st/api/streams/league/2030-01-01/home-away"

    @Test fun refreshesVariantsAndUsesActualEmbedReferer() = runBlocking {
        val hls = "https://bharat.indianservers.st/secure/fresh/1893456000/1893466000/sa_juv/index.m3u8"
        val embed = "https://embed.example/embed/new"
        val net = Fixture(mapOf(endpoint to """{"success":true,"data":{"sources":[{"type":"iframe","name":"English","data":"$embed"}]}}""",
            embed to """<meta name="referrer" content="origin"><script>const player={src:"$hls"};</script>"""))
        val choice = StreamResolver(this, net, ProviderRegistry()).resolve(item).first { it.url.isNotEmpty() }
        assertEquals(hls, choice.url)
        assertEquals("https://embed.example/", choice.headers.referer)
        assertEquals("https://embed.example", choice.headers.origin)
        assertEquals(listOf(endpoint, embed), net.requests.map { it.first })
        assertEquals(item.page, net.requests.first().second.referer)
    }

    @Test fun acceptsDifferentSignedHostsWithoutHardcoding() {
        for (host in listOf("bharat", "netanyahu")) {
            val url = "https://$host.indianservers.st/secure/token/1/2/channel/index.m3u8"
            val players = PpvProvider.parsePlayers(codec.parseToJsonElement("""{"sources":[{"type":"hls","data":"$url"}]}""").obj(), endpoint)
            assertEquals(url, players.single().url)
            assertEquals("m3u8", players.single().target)
        }
    }

    @Test fun failedRefreshPreservesCatalogPlayer() = runBlocking {
        assertEquals(item.players, PpvProvider.players(item, Fixture(emptyMap())))
    }

    @Test fun ignoresUnsupportedUnsafeAndEmptySources() {
        val payload = codec.parseToJsonElement("""{"sources":[{"type":"script","data":"https://bad.example/a.js"},{"type":"iframe","data":"file:///secret"},{"type":"iframe","data":""}]}""").obj()
        assertTrue(PpvProvider.parsePlayers(payload, endpoint).isEmpty())
    }

    @Test fun obfuscatedOnlyEmbedReturnsWebsiteFallback() = runBlocking {
        val net = Fixture(mapOf(endpoint to """{"sources":[]}""", item.players.single().url to "<script src='/js/bundle-jw.js'></script>"))
        val choices = StreamResolver(this, net, ProviderRegistry()).resolve(item)
        assertTrue(choices.all { it.url.isEmpty() })
        assertEquals(item.page, choices.single().externalUrl)
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { PpvProvider.players(item, net) }
        Unit
    }

}
