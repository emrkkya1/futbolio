package app.futbolio.engine

import app.futbolio.engine.runtime.FutbolioEngine
import app.futbolio.engine.adapters.relay.Hls
import app.futbolio.engine.adapters.relay.RelayTokens
import app.futbolio.engine.adapters.diagnostics.Diagnostics

import app.futbolio.engine.adapters.providers.ppv.PpvProvider
import app.futbolio.engine.adapters.providers.papaz.PapazProvider
import app.futbolio.engine.adapters.providers.papaz.PapazPlayerExtractor
import app.futbolio.engine.adapters.providers.taraftarium.TaraftariumPlayerExtractor
import app.futbolio.engine.adapters.providers.shared.StaticStreamExtractor

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.catalog.MatchNormalizer
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.*

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.test.*

class EngineTest {
    private val source = SourceConfig("fixture", "papaz", "Fixture", "https://fixture.example/")
    private fun row(title: String = "Fenerbahçe - Göztepe", channel: Boolean = false) = Listing(source, title, "", "https://fixture.example/event", if (channel) ListingKind.CHANNEL else ListingKind.MATCH)
    @Test fun configRoundTrip() { val c = Configurations.defaults(); assertEquals(c, FutbolioConfig.import(c.export())) }
    @Test fun legacyNodeImport() { assertEquals(Configurations.sources(), FutbolioConfig.import(Configurations.defaults().export().replace("\"schema\": 1", "\"port\":7000,\"host\":\"0.0.0.0\"")).sources) }
    @Test fun emptyProvidersAllowed() { assertTrue(FutbolioConfig.import("""{"schema":1,"sources":[]}""").sources.isEmpty()) }
    @Test fun invalidSchemaRejected() { assertFails { FutbolioConfig.import("""{"schema":2,"sources":[]}""") } }
    @Test fun missingSourcesRejected() { assertFails { FutbolioConfig.import("{}") } }
    @Test fun duplicateSourceIdsRejected() { assertFails { Configurations.validateSources(listOf(source, source)) } }
    @Test fun sourceLimit() { assertFails { Configurations.validateSources((1..9).map { source.copy(id = "s$it") }) } }
    @Test fun unknownAdapterRejected() { assertFails { source.copy(kind = "other").validate() } }
    @Test fun sourceUrlRestrictions() { for (s in listOf("file:///private", "https://user:password@example.com", "https://example.com/#secret", "https://example.com:99999")) assertFails { Web.url(s) } }
    @Test fun strictJsonRejectsExecutable() { for (s in listOf("[1];alert(1)", "[1,]", "{'x':1}")) assertFails { codec.parseToJsonElement(s) } }
    @Test fun literalSupportsData() { val a = LiteralReader("[{title:'Fenerbah\\u00e7e',live:true,},]").read() as JsonArray; assertEquals("Fenerbahçe", a[0].obj().s("title")); assertTrue(a[0].obj().b("live")) }
    @Test fun literalRejectsExpressions() { for (s in listOf("[process.exit()]", "[1+2]", "[{x:()=>1}]", "[undefined]")) assertFails { LiteralReader(s).read() } }
    @Test fun literalDepthBounded() { assertFails { LiteralReader("[".repeat(50) + "0" + "]".repeat(50)).read() } }
    @Test fun variableExtraction() { assertEquals(1, Text.variable("const channels=[{title:'BeIN',url:'/player'}];", "channels").size) }
    @Test fun turkishNormalization() { assertTrue(Text.turkish("Beşiktaş")); assertTrue(Text.turkish("Fenerbahçe")); assertFalse(Text.turkish("Arsenal Chelsea")); assertTrue(Text.otherSport("Basketbol Süper Ligi")) }
    @Test fun htmlParsing() { val a = Text.anchors("<!-- <a href='/bad'>bad</a> --><a href='javascript:x'>no</a><a href='/channel.html?id=1&amp;x=2'><b>BeIN</b></a>", source.url); assertEquals(listOf("https://fixture.example/channel.html?id=1&x=2" to "BeIN"), a) }
    @Test fun publicAddressPolicy() { for (ip in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "::1", "fc00::1", "2001:db8::1", "::ffff:127.0.0.1")) assertFalse(AddressPolicy.isPublic(InetAddress.getByName(ip)), ip); assertTrue(AddressPolicy.isPublic(InetAddress.getByName("8.8.8.8"))); assertTrue(AddressPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111"))) }
    @Test fun productionTransportCannotAccidentallyContactInternetInUnitTests() = runBlocking<Unit> {
        HttpUpstream().use { assertFailsWith<AssertionError> { it.open("https://public.example/") } }
    }
    @Test fun ppvFiltersAndPreservesVariants() {
        val payload = codec.parseToJsonElement("""{"streams":[{"category":"American Football","streams":[{"name":"NFL"}]},{"category":"Football","streams":[{"name":"Past","ends_at":1},{"name":"Match","uri_name":"match","ends_at":9999999999,"iframe":"https://player.example/1","substreams":[{"iframe":"https://player.example/2","locale":"tr"}]}]}]}""").obj()
        val rows = PpvProvider.parse(source.copy(kind = "ppv"), payload); assertEquals(1, rows.size); assertEquals(2, rows[0].players.size); assertEquals("tr", rows[0].players[1].language)
    }
    @Test fun papazMatchesAndChannels() { val rows = PapazProvider.parse(source, "const karsilasmalar=[{title:'Fenerbahçe - Göztepe',league:'Süper Lig',tarih:'2030-10-31',time:'19:00',url:'/event?id=1'},{title:'Basket',league:'Basketbol',url:'/event?id=2'}]; const channels=[{title:'BeIN',url:'/event?id=3'}];", source.url); assertEquals(2, rows.size); assertTrue(rows[0].starts > 0); assertTrue(rows[1].channel) }
    private fun select(rows: List<Listing>, category: String, search: String = "", skip: Int = 0) =
        CatalogSelection.select(MatchNormalizer.aggregate(rows.map { if (it.channel) it else it.copy(league = "Premier League", starts = 60_000) }, emptyList(), PresentationConfig(), 0), CatalogQuery(category, search, skip), 0)
    @Test fun catalogSelection() { val rows = listOf(row(), row("Arsenal - Chelsea"), row("BeIN", true)); assertEquals(1, select(rows, "turkish").size); assertEquals(1, select(rows, "channels").size); assertEquals(1, select(rows, "football", "Chelsea").size); assertEquals(1, select(rows, "football", skip = 1).size); assertTrue(select(rows, "unknown").isEmpty()) }
    @Test fun paginationBounded() { assertEquals(100, select((1..200).map { row("Match $it") }, "football").size) }
    @Test fun datedMatchesExpire() { assertTrue(row().copy(starts = 1).expired(System.currentTimeMillis())); assertFalse(row(channel = true).copy(starts = 1).expired(System.currentTimeMillis())) }
    @Test fun stableListingIdentity() { assertEquals(row().id, row().id); assertNotEquals(row().id, row().copy(source = source.copy(id = "other")).id) }
    @Test fun taraftariumExcludesPreroll() { assertEquals(listOf("https://cdn.example/patron/mono.m3u8"), TaraftariumPlayerExtractor.extract("const CONFIG={baseUrl:'https://cdn.example/',prerollVideo:'https://ad.example/ad.m3u8'};return CONFIG.baseUrl+b+'/mono.m3u8'", "https://fixture.example/channel.html?id=patron")) }
    @Test fun papazTestMappingPreserved() { val html = "this.baseurls=['https://cdn.example/'];if(id==='androstreamlivebs1'&&!useRealStream)return 'test.m3u8';"; assertEquals("https://cdn.example/test.m3u8", PapazPlayerExtractor.extract(html, "https://fixture.example/event?id=androstreamlivebs1").first()); assertEquals("https://cdn.example/androstreamlivebs1.m3u8", PapazPlayerExtractor.extract(html, "https://fixture.example/event?id=androstreamlivebs1&real=1").first()) }
    @Test fun base64AndDashExtraction() { assertEquals(listOf("https://cdn.example/live.m3u8", "https://cdn.example/live.mpd"), StaticStreamExtractor.extract("source:'https://cdn.example/live.m3u8';file:'https://cdn.example/live.mpd'", source.url)); assertEquals("https://cdn.example/live.m3u8", StaticStreamExtractor.extract("source:atob('aHR0cHM6Ly9jZG4uZXhhbXBsZS9saXZlLm0zdTg=')", source.url).first()) }
    @Test fun hlsNestedUris() { val urls = mutableListOf<String>(); val text = Hls.rewrite("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8\"\nvariant.m3u8\nsegment.jpg\n", "https://cdn.example/live/index.m3u8") { urls += it; "local/${urls.size}" }; assertEquals(5, urls.size); assertEquals("https://cdn.example/key.bin", urls[0]); assertTrue(text.contains("URI=\"local/1\"")) }
    @Test fun hlsRejectsUnsafeSchemes() { assertFails { Hls.rewrite("#EXTM3U\nfile:///private.ts", source.url) { it } } }
    @Test fun hlsPreservesDataKeys() { assertTrue(Hls.rewrite("#EXTM3U\n#EXT-X-KEY:URI=\"data:abc\"", source.url) { error("Should not relay inline data") }.contains("data:abc")) }
    @Test fun tokenReuseAndUnknown() { val t = RelayTokens("http://localhost"); val a = t.register("https://cdn.example/live.m3u8", SourceHeaders()); assertEquals(a, t.register("https://cdn.example/live.m3u8", SourceHeaders())); assertTrue(a.matches(Regex(".*/hls/[a-f0-9]{48}/media.m3u8"))); assertNull(t.get("fake")) }
    @Test fun tokenBoundAndExpiry() { var now = 0L; val t = RelayTokens("http://localhost") { now }; repeat(4100) { t.register("https://cdn.example/$it.ts", SourceHeaders()) }; assertEquals(4096, t.size()); now = 1_800_001; t.trim(); assertEquals(0, t.size()) }
    @Test fun diagnosticsBounded() { val d = Diagnostics(); repeat(40) { d.event("Event $it") }; assertEquals(30, d.snapshot().events.size) }
    @Test fun androidCannotExposeLan() { assertFails { HostConfig(bindHost = "0.0.0.0") }; assertFails { HostConfig(advertisedHost = "192.168.1.1") } }
    @Test fun desktopLanRequiresToken() { assertFails { HostConfig(bindHost = "0.0.0.0", desktop = true) }; assertTrue(HostConfig(bindHost = "0.0.0.0", accessToken = opaqueToken(), desktop = true).manifest.contains("/a/")) }

    private fun connection(url: String, method: String = "GET") = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 5000; readTimeout = 10000 }
    private fun get(url: String) = connection(url).let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }
    private fun withEngine(desktop: Boolean = false, test: suspend (FutbolioEngine, FixtureUpstream) -> Unit) = runBlocking {
        val port = ServerSocket(0).use { it.localPort }; val fixture = FixtureUpstream()
        val engine = FutbolioEngine(HostConfig(port = port, desktop = desktop, accessToken = if (desktop) opaqueToken() else ""), listOf(fixture.source), PosterRenderer { _, _ -> byteArrayOf(137.toByte(), 80, 78, 71) }, fixture)
        try { engine.start(); engine.catalog.refresh().await(); test(engine, fixture) } finally { engine.close() }
    }
    @Test fun manifestAndCatalogApi() = withEngine { e, _ ->
        val base = e.host.base; val manifest = get("$base/manifest.json"); assertTrue(manifest.contains("community.futbolio.local")); assertTrue(manifest.contains("Sports Channels")); assertTrue(manifest.contains("Search Futbolio")); assertFalse(manifest.contains("Provider Tests"))
        assertTrue(get("$base/catalog/tv/channels/search=BeIN.json").contains("BeIN Sports 1"))
        assertTrue(get("$base/catalog/tv/unknown.json").contains("[]"))
        assertTrue(get("$base/meta/tv/unknown.json").contains("null")); assertTrue(get("$base/stream/tv/unknown.json").contains("[]"))
    }
    @Test fun metadataAndWebsiteFallback() = withEngine { e, _ ->
        val row = e.catalog.items().first(); assertTrue(get("${e.host.base}/meta/tv/${Web.enc(row.id)}.json").contains(row.title))
        assertTrue(get("${e.host.base}/stream/tv/${Web.enc(row.id)}.json").contains("externalUrl"))
    }
    @Test fun relayBytesMimeHeadersAndRanges() = withEngine { e, f ->
        val row = e.catalog.items().first(); val response = codec.parseToJsonElement(get("${e.host.base}/stream/tv/${Web.enc(row.id)}.json")).obj()
        val url = response.a("streams")[0].obj().s("url")
        val playlist = get(url); assertTrue(playlist.contains(e.host.base + "/hls/")); assertTrue(playlist.contains("URI=\"${e.host.base}"))
        val segment = playlist.lines().first { it.startsWith("http:") }
        connection(segment).let { c -> try { assertEquals(200, c.responseCode); assertEquals("video/mp2t", c.contentType); assertContentEquals(f.segment, c.inputStream.readBytes()) } finally { c.disconnect() } }
        assertEquals("https://fixture.example/", f.lastHeaders.referer); assertEquals("https://fixture.example", f.lastHeaders.origin)
        connection(segment).let { c -> try { c.setRequestProperty("Range", "bytes=0-563"); assertEquals(206, c.responseCode); assertEquals("bytes 0-563/1048576", c.getHeaderField("Content-Range")); assertEquals(564, c.inputStream.readBytes().size) } finally { c.disconnect() } }
        connection(segment).let { c -> try { c.setRequestProperty("Range", "bytes=0-1,3-4"); assertEquals(416, c.responseCode) } finally { c.disconnect() } }
        assertEquals(0, e.diagnostics.activeMedia.get()); assertTrue(e.diagnostics.mediaBytes.get() >= f.segment.size)
    }
    @Test fun headCorsAndMethods() = withEngine { e, _ ->
        for (method in listOf("HEAD", "OPTIONS")) connection("${e.host.base}/manifest.json", method).let { c -> try { assertEquals(if (method == "HEAD") 200 else 204, c.responseCode); assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin")); assertEquals(-1, c.inputStream.read()) } finally { c.disconnect() } }
        connection("${e.host.base}/manifest.json", "POST").let { c -> try { assertEquals(405, c.responseCode) } finally { c.disconnect() } }
    }
    @Test fun proxyAndRebindingRejected() = withEngine { e, _ ->
        for (path in listOf("/proxy?url=http://127.0.0.1/", "/hls/${"a".repeat(48)}/media.m3u8")) connection(e.host.base + path).let { c -> try { assertEquals(404, c.responseCode) } finally { c.disconnect() } }
        Socket("127.0.0.1", e.host.port).use { s -> s.getOutputStream().write("GET /manifest.json HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n".toByteArray()); assertTrue(s.getInputStream().bufferedReader().readLine().contains("400")) }
    }
    @Test fun desktopTokenRequiredAndPropagated() = withEngine(true) { e, _ ->
        connection("http://127.0.0.1:${e.host.port}/manifest.json").let { c -> try { assertEquals(404, c.responseCode) } finally { c.disconnect() } }
        assertTrue(get(e.host.manifest).contains("community.futbolio.local"))
        val catalog = get("${e.host.base}/catalog/tv/channels.json"); assertTrue(catalog.contains(e.host.prefix))
    }
    @Test fun providerFailureIsolatedAndStaleRetained() = withEngine { e, f ->
        f.offline = true; e.catalog.refresh(true).await(); assertFalse(e.catalog.status.value.first().ok); assertEquals(1, e.catalog.items().size)
        assertFalse(get("${e.host.base}/diagnostics.json").contains("cdn.example"))
    }
    @Test fun settingsChangeClearsOldCatalog() = withEngine { e, _ -> e.configure(emptyList()); assertTrue(e.catalog.items().isEmpty()); assertTrue(e.catalog.status.value.isEmpty()) }
    @Test fun resolverFallbackWhenOffline() = withEngine { e, f -> f.offline = true; val streams = e.resolver.resolve(e.catalog.items().first()); assertEquals(1, streams.size); assertTrue(streams.first().externalUrl.isNotEmpty()) }
    @Test fun serverShutdownClosesListener() = withEngine { e, _ -> e.close(); assertFails { get(e.host.manifest) } }
}
