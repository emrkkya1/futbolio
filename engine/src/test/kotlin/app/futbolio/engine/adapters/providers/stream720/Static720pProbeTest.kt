package app.futbolio.engine.adapters.providers.stream720

import app.futbolio.engine.*
import app.futbolio.engine.runtime.FutbolioEngine

import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.adapters.providers.stream720.Stream720Provider

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.domain.catalog.*
import app.futbolio.engine.api.*

import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive

/** Exercises the production adapter and local Stremio relay, not a separate research resolver. */
class Static720pProbeTest {
    private val source = Configurations.sources().first { it.kind == "720pstream" }
    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private fun playerHtml(media: String = "https://cdn.example/manifest.ts", auth: String = "https://auth.example/") = """
        F=d(String.fromCharCode(...[${b64(media).map { it.code }.joinToString(",")} ]));
        let R="fixture-device";k="match-2";v=d("${b64(auth)}");y=parseInt(123,10);
        const T={[m]:d("${b64("fixture-header")}")};l=_({scode:"fresh-code",ts:y,session_id:"session-2",device_id:R});
        const r=`host_id=${'$'}{encodeURIComponent("s-dynamic")}`;
    """.replace(" ]", "]")
    private val embedHtml = """<script data-pldm="player.example" data-pid="8" data-play="different-match" data-gc="football"></script>
        <script>const siteConfig = {"csrf":"a+b","csrf_ip":"new","sec_expires":"123","sec_hash":"hash"};</script>"""
    private val schedule = """<a class="match-card" href="/live/soccer/fixture-home"><time datetime="2030-01-01T21:30:00.000Z"></time>
        <span class="team"><img src="/home.png"><strong>Home SC</strong></span><span class="team"><strong>Away FC</strong></span></a>"""
    private fun payload(generation: Int) = """{"success":true,"device_id":"device-$generation","scode":"code-$generation","ts":2000000000,"session_id":"session-$generation","refresh_after":5}"""

    @Test fun discoveryAndDefaultsMigration() {
        val rows = Stream720Provider.parse(source, schedule + schedule.replace("/soccer/", "/basketball/"), source.url)
        assertEquals(1, rows.size); assertEquals("Home SC vs Away FC", rows.single().title)
        assertEquals(1893533400000L, rows.single().starts)
        assertTrue(rows.single().homeLogo.endsWith("/home.png"))
        assertTrue(ProviderRegistry().get("720pstream") === Stream720Provider)
        val old = FutbolioConfig(sources = Configurations.sources().filterNot { it.kind == "720pstream" }, defaultsRevision = 3)
        assertEquals(1, FutbolioConfig.import(old.export()).sources.count { it.kind == "720pstream" })
        assertTrue(FutbolioConfig.import(FutbolioConfig(sources = emptyList(), defaultsRevision = 3).export()).sources.isEmpty())
        val removed = FutbolioConfig(sources = old.sources, defaultsRevision = 4)
        assertEquals(old.sources, FutbolioConfig.import(removed.export()).sources)
    }
    @Test fun literalDataOnlyAndDynamicDomains() {
        val nested = Stream720Provider.nestedPlayer(embedHtml, "https://embed.example/game")
        assertEquals("a+b", Web.query(nested, "csrf")); assertEquals("different-match", Web.query(nested, "v"))
        val data = Stream720Provider.playerData(playerHtml("https://other-cdn.example/manifest.ts"))
        assertEquals("other-cdn.example", Web.url(data.media).host)
        assertEquals("match-2", Web.query(data.auth, "stream")); assertEquals("s-dynamic", Web.query(data.auth, "host_id"))
        assertFails { Stream720Provider.playerData(playerHtml().replace("String.fromCharCode(...[", "String.fromCharCode(...[fetch('bad'),")) }
        assertFails { Stream720Provider.playerData(playerHtml("http://cdn.example/live")) }
        assertFails { Stream720Provider.nestedPlayer(embedHtml.replace("player.example", "player.example/@other"), "https://embed.example/") }
    }

    @Test fun nextPlayerDataIsReadWithoutExecutingPageCodeAndPendingEventsStayUnplayable() {
        val json = """[1,"18:[\"${'$'}\",\"Player\",null,{\"iframeUrl\":\"https://embed.example/published\"}]"]"""
        val html = "<script>self.__next_f.push($json)</script>"
        assertEquals("https://embed.example/published", Stream720Provider.pageEmbed(html, source.url))
        assertEquals("https://embed.example/legacy", Stream720Provider.pageEmbed(
            "<iframe class='video' src='https://embed.example/legacy'></iframe>", source.url))
        assertNull(Stream720Provider.pageEmbed("<script>self.__next_f.push([1,\"{\\\"iframeUrl\\\":null,\\\"iframeuri\\\":\\\"\\\"}\"])</script>", source.url))
        assertNull(Stream720Provider.pageEmbed("<script>self.__next_f.push(fetch('https://untrusted.example'))</script>", source.url))
    }

    @Test fun nextScheduleCompetitionMetadataKeepsCurated720MatchesDiscoverable() {
        val data = """{"initialGames":[{"homeuri":"fixture-home","date":"2030-01-01T21:30:00.000Z","description":"UEFA Champions League, Playing at Fixture Stadium","iframeuri":""}]}"""
        val html = schedule + "<script>self.__next_f.push([1,${JsonPrimitive(data)}])</script>"
        val row = Stream720Provider.parse(source, html, source.url).single()
        assertEquals("UEFA Champions League", row.league)
        assertEquals("uefa.champions", app.futbolio.domain.match.Leagues.find(row.league).id)
    }

    @Test fun nextPublishedDirectPlaylistKeepsHlsTypeEvenWithANonstandardExtension() = runBlocking {
        val row = Listing(source, "Home vs Away", "Premier League", "https://site.example/live/soccer/home")
        val data = """{"iframeUrl":"https://embed.example/watch.ts"}"""
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val body = if (url == row.page) "<script>self.__next_f.push([1,${JsonPrimitive(data)}])</script>" else "#EXTM3U\n#EXTINF:4,\npart.ts"
                return UpstreamResponse(200, url, emptyMap(), body.byteInputStream())
            }
        }
        val choice = Stream720Provider.resolve(row, net).single()
        assertEquals("hls", choice.mediaType)
        assertTrue(isHls(choice))
    }
    @Test fun credentialsAreOriginScopedAndRedacted() {
        val headers = SourceHeaders(authOrigin = "https://auth.example", authToken = "secret-fixture")
        assertEquals("secret-fixture", headers.scopedAuthFor("https://auth.example/key"))
        for (target in listOf("https://cdn.example/key", "http://auth.example/key", "https://auth.example:8443/key", "https://auth.example.evil/key"))
            assertNull(headers.scopedAuthFor(target))
        assertFalse(headers.toString().contains("secret-fixture"))
        assertFails { SourceHeaders(authOrigin = "https://auth.example", authToken = "bad\r\nheader") }
    }
    @Test fun renewsOnceForConcurrentRequestsAndUsesFreshBindings() = runBlocking {
        var now = 1000L; var calls = 0
        val authQueries = mutableListOf<String>(); val mediaQueries = mutableListOf<String>()
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val text = if (Web.url(url).host == "auth.example") {
                    authQueries += url; delay(5); payload(++calls)
                } else { mediaQueries += url; "media" }
                return UpstreamResponse(200, url, emptyMap(), text.byteInputStream())
            }
        }
        val session = Stream720Provider.Session(net, Stream720Provider.playerData(playerHtml()), "https://player.example/embed") { now }
        session.prepare(); val root = session.mediaUrl()
        assertEquals(1, calls); now += 5001
        (1..5).map { async { session.open(root).close() } }.awaitAll()
        assertEquals(2, calls); assertEquals("1", Web.query(authQueries.last(), "r"))
        assertEquals("code-1", Web.query(authQueries.last(), "scode"))
        assertTrue(mediaQueries.all { Web.query(it, "u_id") == "device-2" })
    }
    @Test fun retriesUnauthorizedOnceAndPropagatesCancellation() = runBlocking {
        var authCalls = 0; var mediaCalls = 0
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val auth = Web.url(url).host == "auth.example"
                val text = if (auth) payload(++authCalls) else "denied"
                return UpstreamResponse(if (auth) 200 else { mediaCalls++; 403 }, url, emptyMap(), text.byteInputStream())
            }
        }
        val session = Stream720Provider.Session(net, Stream720Provider.playerData(playerHtml()), "https://player.example/embed")
        session.open("https://cdn.example/part.ts").use { assertEquals(403, it.status) }
        assertEquals(2, authCalls); assertEquals(2, mediaCalls)
        val cancelled = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = throw CancellationException("fixture cancellation")
        }
        assertFailsWith<CancellationException> { Stream720Provider.Session(cancelled, Stream720Provider.playerData(playerHtml()), "https://player.example/embed").prepare() }
        Unit
    }
    @Test fun layoutFailureReturnsWebsiteNotFakePlayback() = runBlocking {
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?) = UpstreamResponse(200, url, emptyMap(), "<html>Changed player</html>".byteInputStream())
        }
        val choices = StreamResolver(this, net, ProviderRegistry()).resolve(Listing(source, "Fixture", "Football", "https://site.example/game"))
        assertTrue(choices.all { it.url.isEmpty() }); assertEquals("https://site.example/game", choices.single().externalUrl)
    }

    private fun localGet(url: String): ByteArray {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 35_000
        try { check(c.responseCode == 200) { "Local relay HTTP ${c.responseCode}" }; return c.inputStream.use { it.readBytes() } }
        finally { c.disconnect() }
    }
    private suspend fun verifyRelay(engine: FutbolioEngine, listing: Listing): Long {
        val streams = codec.parseToJsonElement(localGet("${engine.host.base}/stream/tv/${Web.enc(listing.id)}.json").toString(Charsets.UTF_8)).obj().a("streams")
        var url = streams.map { it.obj().s("url") }.firstOrNull { it.isNotBlank() } ?: error("No inline stream")
        require(url.startsWith(engine.host.base + "/hls/"))
        var media = ""
        for (depth in 0..3) {
            val text = localGet(url).toString(Charsets.UTF_8)
            require(text.startsWith("#EXTM3U"))
            if (text.contains("#EXT-X-STREAM-INF:")) url = text.lines().first { it.startsWith(engine.host.base + "/hls/") }
            else { media = text; break }
        }
        require(media.isNotBlank())
        val lines = media.lines().map(String::trim)
        val keyLine = lines.first { it.startsWith("#EXT-X-KEY:") }
        val keyUrl = Text.match(keyLine, "URI=\"([^\"]+)\"")
        assertTrue(keyUrl.startsWith(engine.host.base + "/hls/"))
        val key = localGet(keyUrl); assertEquals(16, key.size)
        val iv = Text.match(keyLine, "IV=0x([a-fA-F0-9]{32})").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val paths = lines.filter { it.isNotBlank() && !it.startsWith('#') }.take(2)
        assertEquals(2, paths.size)
        return paths.sumOf { path ->
            assertTrue(path.startsWith(engine.host.base + "/hls/"))
            val bytes = localGet(path)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val clear = cipher.doFinal(bytes)
            assertTrue(clear.size >= 752 && clear.size % 188 == 0)
            assertTrue((0..3).all { clear[it * 188] == 0x47.toByte() })
            clear.size.toLong()
        }
    }
    @Test fun fixtureStremioApiRelaysPlaylistsKeysAndMedia() = runBlocking {
        val key = ByteArray(16) { it.toByte() }; val iv = ByteArray(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0 })
        val fixtureSource = source.copy(url = "https://site.example/soccer-online")
        val requests = mutableListOf<Pair<String, SourceHeaders>>()
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                requests += url to headers
                val uri = Web.url(url)
                val bytes = when {
                    uri.path == "/soccer-online" -> schedule.toByteArray()
                    uri.path.startsWith("/live/soccer/") -> "<iframe class='video' src='https://embed.example/game'></iframe>".toByteArray()
                    uri.host == "embed.example" -> embedHtml.toByteArray()
                    uri.host == "player.example" -> playerHtml().toByteArray()
                    uri.host == "auth.example" && uri.path == "/" -> payload(1).toByteArray()
                    uri.host == "auth.example" && uri.path == "/key" -> { assertEquals("fixture-header", headers.scopedAuthFor(url)); key }
                    uri.path == "/manifest.ts" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia.m3u8".toByteArray()
                    uri.path == "/media.m3u8" -> "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://auth.example/key\",IV=0x${"00".repeat(16)}\n#EXTINF:4,\npart1.ts\n#EXTINF:4,\npart2.ts".toByteArray()
                    uri.path.startsWith("/part") -> { assertNull(headers.scopedAuthFor(url)); encrypted }
                    else -> error("Unexpected fixture request")
                }
                return UpstreamResponse(200, url, mapOf("content-length" to bytes.size.toString()), bytes.inputStream())
            }
        }
        val engine = FutbolioEngine(HostConfig(port = ServerSocket(0).use { it.localPort }), listOf(fixtureSource), PosterRenderer { _, _ -> byteArrayOf() }, net)
        try {
            engine.start(); engine.catalog.refresh().await()
            assertEquals(3008, verifyRelay(engine, engine.catalog.items().single()))
            assertTrue(requests.any { it.first.contains("/key") })
        } finally { engine.close() }
    }
}
