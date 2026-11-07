package app.futbolio.engine

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.runtime.FutbolioEngine
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StreamProviderBudgetTest {
    private fun verify(unavailable: String? = null) = runBlocking {
        val starts = System.currentTimeMillis() + 3_600_000 // Not a background Live Now candidate.
        val sources = (1..5).map { SourceConfig("provider-$it", "provider-$it", "Provider $it", "https://fixture.example/") }
        val resolved = AtomicInteger()
        val sampled = ConcurrentHashMap.newKeySet<String>()
        val sampleCalls = AtomicInteger()
        val directory = object : ProviderDirectory {
            override val descriptors = sources.map(::ProviderDescriptor)
            override fun get(kind: String) = object : Provider {
                override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(
                    Listing(source, "Arsenal vs Chelsea", "Premier League", "https://fixture.example/${source.id}", starts = starts)))
                override suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice> {
                    resolved.incrementAndGet()
                    return if (item.source.id == unavailable) emptyList() else
                        listOf(StreamChoice(item.source.name, "https://fixture.example/${item.source.id}/live.m3u8"))
                }
            }
        }
        val media = ByteArray(188 * 12).also { for (offset in it.indices step 188) it[offset] = 0x47 }
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val bytes = if (url.endsWith("m3u8")) "#EXTM3U\n#EXTINF:4,\n1.ts\n#EXTINF:4,\n2.ts".toByteArray()
                else {
                    sampled += URI(url).path.split('/')[1]
                    sampleCalls.incrementAndGet()
                    media
                }
                return UpstreamResponse(200, url, mapOf("content-length" to bytes.size.toString()), bytes.inputStream())
            }
        }
        val port = ServerSocket(0).use { it.localPort }
        val engine = FutbolioEngine(HostConfig(port), sources, PosterRenderer { _, _ -> byteArrayOf() }, net,
            providers = directory, scoreFeed = ScoreFeed { _, _, _ -> emptyList() })
        fun request(path: String): String {
            val connection = URI("${engine.host.base}$path").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 15_000
            return try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
        }
        try {
            engine.start()
            engine.catalog.refresh().await()
            val listing = engine.catalog.items().first()
            val path = "/stream/tv/${Web.enc(listing.id)}.json"
            val json = withContext(Dispatchers.IO) { codec.parseToJsonElement(request(path)).obj() }
            val streams = json.a("streams").map { it.obj() }
            assertEquals(if (unavailable == null) 5 else 4, streams.size, "The measurement cap must not hide unmeasured playable providers")
            assertEquals(3, sampled.size)
            assertEquals(6, sampleCalls.get())
            assertTrue(unavailable == null || unavailable !in sampled)
            assertEquals(3, streams.count { it.s("name").endsWith(" · GOOD") })
            assertEquals(if (unavailable == null) 2 else 1, streams.count { it.s("name").endsWith(" · Not rated") })
            assertEquals(5, resolved.get())
            val first = sampled.toSet()
            withContext(Dispatchers.IO) { request(path) }
            assertEquals(5, resolved.get(), "Repeat selection must reuse prepared provider links")
            assertEquals(first, sampled.toSet())
            assertEquals(6, sampleCalls.get(), "Returning fresh grades must not download the same samples again")
        } finally { engine.shutdown() }
    }

    @Test fun foregroundMeasuresAtMostThreeProvidersAndReturnsEveryPlayableOption() = verify()
    @Test fun unavailableProviderDoesNotConsumeOneOfThreeMeasurementSlots() = verify("provider-1")
}
