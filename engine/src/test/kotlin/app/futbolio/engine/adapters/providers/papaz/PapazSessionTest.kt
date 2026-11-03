package app.futbolio.engine.adapters.providers.papaz

import app.futbolio.application.ports.*
import kotlinx.coroutines.*
import kotlin.test.*

class PapazSessionTest {
    private val headers = SourceHeaders("https://portal.example/", "https://portal.example")

    @Test fun renewsBeforePublishedPlayerDeadlineAndPreservesHeadersAndRanges() = runBlocking {
        var now = 1000L
        var authorizations = 0
        val requests = mutableListOf<Triple<String, SourceHeaders, String?>>()
        val net = object : Upstream {
            override suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders): UpstreamResponse {
                assertEquals("https://portal.example/auth.php", url)
                assertEquals(mapOf("channel" to "100001"), fields)
                delay(5)
                val version = ++authorizations
                return UpstreamResponse(200, url, emptyMap(), """{"URL":"https://cdn.example/live-$version.js","TOKEN":"token-$version"}""".byteInputStream())
            }
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                requests += Triple(url, headers, range)
                return UpstreamResponse(200, url, emptyMap(), byteArrayOf(1).inputStream())
            }
        }
        val session = PapazPlayerProtocol.Session(net, "https://portal.example/auth.php", "channel", "100001", headers, "Fixture") { now }
        session.prepare()
        val root = session.mediaUrl()
        now += 35 * 60_000 + 1
        (1..8).map { async { session.open(root, "bytes=0-99").close() } }.awaitAll()
        assertEquals(2, authorizations)
        assertTrue(requests.all { it.first == "https://cdn.example/live-2.js" })
        assertTrue(requests.all { it.second.extra == mapOf("usertoken" to "token-2", "pl" to "Fixture") })
        assertTrue(requests.all { it.third == "bytes=0-99" })
        assertFalse(session.mediaHeaders().toString().contains("token-2"))
    }

    @Test fun forbiddenMediaIsClosedAndReauthorizedOnlyOnce() = runBlocking {
        var authorizations = 0
        var deniedClosed = false
        var mediaCalls = 0
        val net = object : Upstream {
            override suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders): UpstreamResponse {
                authorizations++
                return UpstreamResponse(200, url, emptyMap(), """{"URL":"https://cdn.example/live.js","TOKEN":"token-$authorizations"}""".byteInputStream())
            }
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                mediaCalls++
                if (mediaCalls == 1) return UpstreamResponse(403, url, emptyMap(), byteArrayOf().inputStream()) { deniedClosed = true }
                assertEquals("token-2", headers.extra["usertoken"])
                return UpstreamResponse(200, url, emptyMap(), byteArrayOf(1).inputStream())
            }
        }
        val session = PapazPlayerProtocol.Session(net, "https://portal.example/auth.php", "id", "123", headers, "Fixture")
        session.prepare(); session.open(session.mediaUrl()).close()
        assertTrue(deniedClosed)
        assertEquals(2, authorizations); assertEquals(2, mediaCalls)
    }

    @Test fun missingAndPlaceholderMediaAreNeverAdvertisedAsPlayable() = runBlocking {
        for (payload in listOf("{}", """{"URL":"/lib/mp4/video_generating.mp4"}""")) {
            val net = object : Upstream {
                override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("Unexpected media request")
                override suspend fun postForm(url: String, fields: Map<String, String>, headers: SourceHeaders) =
                    UpstreamResponse(200, url, emptyMap(), payload.byteInputStream())
            }
            val session = PapazPlayerProtocol.Session(net, "https://portal.example/auth.php", "id", "123", headers, "Fixture")
            assertFails { session.prepare() }
        }
    }
}
