package app.futbolio.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.futbolio.engine.*
import app.futbolio.application.api.*
import app.futbolio.engine.testing.EngineFixtures
import app.futbolio.application.ports.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.MessageDigest

/** Installed-app smoke + shared fixture relay checks. No third-party network access. */
class SmokeInstrumentation : Instrumentation() {
    private var passed = 0
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun check(name: String, condition: Boolean) {
        if (!condition) throw AssertionError(name)
        passed++; sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }
    private fun connection(url: String, method: String = "GET") = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 3000; readTimeout = 10000 }
    private fun get(url: String) = connection(url).let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }
    private fun ready(): Boolean = runCatching { get(AppSettings.MANIFEST).contains("community.futbolio.local") }.getOrDefault(false)
    private suspend fun waitReady(value: Boolean) { withTimeout(30_000) { while (ready() != value) delay(200) } }
    override fun onStart() = runBlocking<Unit> {
        val settings = AppSettings(targetContext)
        val originalConfig = settings.config(); val originalDesired = settings.desired()
        val result = Bundle(); var resultCode = Activity.RESULT_OK
        var activity: Activity? = null
        try {
            settings.save(FutbolioConfig(sources = emptyList())); settings.desired(true)
            activity = startActivitySync(Intent().setClassName("app.futbolio.android", "app.futbolio.android.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            targetContext.startForegroundService(Intent().setClassName("app.futbolio.android", "app.futbolio.android.RelayService"))
            waitReady(true)
            check("Foreground service starts shared API", ready())
            val base = "http://127.0.0.1:7000"
            val manifest = get("$base/manifest.json")
            check("All three catalogs", manifest.contains("Turkish Football") && manifest.contains("All Matches") && manifest.contains("Sports Channels"))
            check("Empty provider configuration", get("$base/health.json").contains("\"sources\": []"))
            check("Unknown catalog handled", get("$base/catalog/tv/unknown.json").contains("[]"))
            check("Unknown metadata", get("$base/meta/tv/unknown.json").contains("null"))
            check("Unknown streams", get("$base/stream/tv/unknown.json").contains("[]"))
            connection(AppSettings.MANIFEST, "OPTIONS").let { c -> try { check("CORS preflight", c.responseCode == 204 && c.getHeaderField("Access-Control-Allow-Origin") == "*") } finally { c.disconnect() } }
            connection(AppSettings.MANIFEST, "HEAD").let { c -> try { check("HEAD has no body", c.responseCode == 200 && c.inputStream.read() == -1) } finally { c.disconnect() } }
            connection("$base/poster/unknown.jpg").let { c -> try { val bytes = c.inputStream.readBytes(); check("Native JPEG poster", bytes.size > 8 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) } finally { c.disconnect() } }
            connection("$base/proxy?url=http://localhost").let { c -> try { check("No arbitrary proxy", c.responseCode == 404) } finally { c.disconnect() } }
            Socket("127.0.0.1", 7000).use { socket -> socket.getOutputStream().write("GET /manifest.json HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".toByteArray()); check("Host rebinding rejected", socket.getInputStream().bufferedReader().readLine().contains("400")) }
            var checkedAddress = false
            for (network in NetworkInterface.getNetworkInterfaces().toList()) for (address in network.inetAddresses.toList()) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                checkedAddress = true
                val reachable = runCatching { Socket().use { it.connect(java.net.InetSocketAddress(address, 7000), 300) }; true }.getOrDefault(false)
                check("No listener on ${address.hostAddress}", !reachable)
            }
            check("Non-loopback interface tested", checkedAddress)
            // Remote navigation and visual quality are manual acceptance checks, not accessibility-tree assumptions.
            waitForIdleSync()
            runOnMainSync { check("Compose activity is attached and visible", activity?.window?.decorView?.isShown == true) }
            runOnMainSync { activity?.finish() }; delay(500)
            check("Service survives closing the UI", ready())
            fixtureChecks()
            populatedCatalogChecks()
            settings.desired(false); targetContext.stopService(Intent().setClassName("app.futbolio.android", "app.futbolio.android.RelayService")); waitReady(false)
            check("Stop closes listener", !ready())
            result.putString("stream", "ALL $passed FUTBOLIO ANDROID CHECKS PASSED\n")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            result.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n")
            android.util.Log.e("FutbolioSmoke", "Check failed", error)
        } finally {
            settings.desired(false); targetContext.stopService(Intent().setClassName("app.futbolio.android", "app.futbolio.android.RelayService"))
            settings.save(originalConfig); settings.desired(originalDesired)
        }
        finish(resultCode, result)
    }

    private suspend fun populatedCatalogChecks() {
        val now = System.currentTimeMillis()
        val source = SourceConfig("catalog-fixture", "ppv", "Catalog fixture", "https://fixture.example/", "https://fixture.example/api")
        val matches = (1..400).joinToString(",") { i ->
            """{"name":"Home $i vs Away $i","uri_name":"match-$i","starts_at":${now / 1000},"ends_at":${now / 1000 + 7200},"iframe":"https://embed.example/$i"}"""
        }
        val payload = """{"streams":[{"category":"Football","streams":[$matches]}]}"""
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?) =
                UpstreamResponse(200, url, emptyMap(), payload.byteInputStream())
        }
        val engine = EngineFixtures.create(HostConfig(port = 7002), listOf(source), AndroidPosters(), net,
            presentation = PresentationConfig(scoresEnabled = false))
        try {
            engine.start(); engine.providerStatuses.first { statuses -> statuses.none { it.loading } }
            val started = android.os.SystemClock.elapsedRealtime()
            val catalog = Json.parseToJsonElement(get("http://127.0.0.1:7002/catalog/tv/football.json")).obj()
            check("400-match Android catalog responds with 100 entries", catalog.a("metas").size == 100)
            sendStatus(0, Bundle().apply { putString("stream", "Android populated catalog: ${android.os.SystemClock.elapsedRealtime() - started} ms\n") })
            coroutineScope {
                val posters = catalog.a("metas").take(16).map { value -> async(Dispatchers.IO) {
                    connection(value.obj().s("poster")).let { c -> try {
                        val ok = c.responseCode == 200; if (ok) c.inputStream.use { it.readBytes() }; ok
                    } finally { c.disconnect() } }
                } }
                val responsive = async(Dispatchers.IO) { get("http://127.0.0.1:7002/manifest.json").contains("Futbolio") }
                check("Android API stays responsive during poster burst", responsive.await())
                check("16 concurrent Android posters return images instead of API busy", posters.awaitAll().all { it })
            }
        } finally { engine.close() }
    }

    private suspend fun fixtureChecks() {
        val fixture = FixtureUpstream()
        val engine = EngineFixtures.create(HostConfig(port = 7001), listOf(fixture.source), AndroidPosters(), fixture)
        try {
            engine.start(); engine.providerStatuses.first { statuses -> statuses.none { it.loading } }
            val base = engine.host.base
            val catalog = Json.parseToJsonElement(get("$base/catalog/tv/channels.json")).obj()
            val item = catalog.a("metas").first().obj()
            check("Shared provider fixture catalog on Android", item.s("name") == "BeIN Sports 1")
            val choices = Json.parseToJsonElement(get("$base/stream/tv/${Web.enc(item.s("id"))}.json")).obj().a("streams")
            check("Resolution and website fallback on Android", choices.size == 2 && choices.last().obj().s("externalUrl").isNotBlank())
            val playlistUrl = choices.first().obj().s("url")
            val playlist = get(playlistUrl)
            check("Nested HLS URLs rewritten on Android", playlist.contains("$base/hls/") && playlist.contains("URI=\"$base"))
            val segmentUrl = playlist.lines().first { it.startsWith("http:") }
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(fixture.segment)
            suspend fun download() = withContext(Dispatchers.IO) {
                connection(segmentUrl).let { c -> try {
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768); var bytes = 0
                    c.inputStream.use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); bytes += n } }
                    if (bytes != fixture.segment.size || !digest.digest().contentEquals(expectedHash) || c.contentType != "video/mp2t") error("Media bytes/MIME mismatch")
                } finally { c.disconnect() } }
            }
            download(); check("Real Android relay preserves segment SHA-256 and corrects MIME", true)
            check("Source headers survive relay", fixture.lastHeaders == SourceHeaders("https://fixture.example/", "https://fixture.example"))
            connection(segmentUrl).let { c -> try { c.setRequestProperty("Range", "bytes=0-563"); check("Android byte ranges", c.responseCode == 206 && c.inputStream.readBytes().size == 564 && c.getHeaderField("Content-Range") == "bytes 0-563/1048576") } finally { c.disconnect() } }
            coroutineScope { listOf(async { repeat(20) { download() } }, async { repeat(20) { download() } }).awaitAll() }
            check("Two concurrent consumers forward 40 MiB without corruption", engine.diagnosticSnapshot().mediaBytes >= 40L * 1024 * 1024)
            check("Media requests return to idle", engine.diagnosticSnapshot().activeMedia == 0)
            fixture.offline = true; engine.refreshProviders()
            val statuses = engine.providerStatuses.first { values -> values.none { it.loading } }
            check("Provider failure retains stale listings", !statuses.first().ok && statuses.first().count == 1)
            check("Diagnostics omit upstream URLs", !get("$base/diagnostics.json").contains("cdn.example"))
        } finally { engine.close() }
    }
}
