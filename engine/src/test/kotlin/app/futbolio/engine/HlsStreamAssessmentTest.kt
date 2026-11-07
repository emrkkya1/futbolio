package app.futbolio.engine

import app.futbolio.application.api.HostConfig
import app.futbolio.application.ports.*
import app.futbolio.engine.adapters.probes.HlsStreamAssessor
import app.futbolio.engine.adapters.stremio.wire.StremioWire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class HlsStreamAssessmentTest {
    private val base = "https://media.example/"
    private val media = ByteArray(1024).also { it[0] = 0x47; it[188] = 0x47; it[376] = 0x47 }
    private fun playlist(duration: Double = 4.0, count: Int = 2) = "#EXTM3U\n" +
        (1..count).joinToString("\n") { "#EXTINF:$duration,\n$it.ts" }

    private class Fixture(val assets: Map<String, ByteArray>) : Upstream {
        val requests = mutableListOf<Pair<String, SourceHeaders>>()
        var closed = 0
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
            requests += url to headers
            val bytes = assets[url] ?: error("Missing fixture asset")
            val input = bytes.inputStream()
            return UpstreamResponse(200, url, emptyMap(), input) { closed++; input.close() }
        }
    }

    private fun assessor(net: Upstream): HlsStreamAssessor {
        var tick = 0L
        return HlsStreamAssessor(net) {
            val current = tick++
            (current / 3 + if (current % 3 == 2L) 1 else 0) * 1_000_000_000
        }
    }

    @Test fun measuresTwoRecentSegmentsAndUsesWorstSample() = runBlocking {
        val net = Fixture(mapOf(
            "${base}live.m3u8" to "#EXTM3U\n#EXTINF:4,\nold.ts\n#EXTINF:4,\n1.ts\n#EXTINF:0.5,\n2.ts".toByteArray(),
            "${base}1.ts" to media, "${base}2.ts" to media,
        ))
        val result = assessor(net).assess(StreamChoice("Live", "${base}live.m3u8"))
        assertEquals(ConnectionStatus.SLOW, result.status)
        assertEquals(.5, result.headroom)
        assertEquals(2, result.samples)
        assertEquals(3, net.requests.size)
        assertEquals(net.requests.size, net.closed)
    }

    @Test fun highestRenditionMetadataAndPlaybackHeadersArePreserved() = runBlocking {
        val net = Fixture(mapOf(
            "${base}master.m3u8" to ("#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360\nlow.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=59.94,CODECS=\"avc1.640028,mp4a.40.2\"\nhigh.m3u8").toByteArray(),
            "${base}high.m3u8" to playlist().toByteArray(),
            "${base}1.ts" to media, "${base}2.ts" to media,
        ))
        val headers = SourceHeaders(referer = "https://player.example/")
        val result = assessor(net).assess(StreamChoice("Live", "${base}master.m3u8", headers))
        assertEquals(ConnectionStatus.GOOD, result.status)
        assertEquals("1920x1080", result.resolution)
        assertEquals(59.94, result.frameRate)
        assertEquals(6_000_000L, result.bitrate)
        assertTrue(result.adaptive)
        assertTrue(net.requests.all { it.second == headers })
        assertFalse(net.requests.any { it.first.endsWith("low.m3u8") })
    }

    @Test fun limitedHeadroomAndSingleSampleAreNotOverpromised() = runBlocking {
        for ((count, duration, expected) in listOf(
            Triple(2, 1.5, ConnectionStatus.MEDIUM),
            Triple(1, 4.0, ConnectionStatus.UNKNOWN),
            Triple(1, .5, ConnectionStatus.UNKNOWN),
        )) {
            val net = Fixture(mapOf("${base}live.m3u8" to playlist(duration, count).toByteArray(),
                "${base}1.ts" to media, "${base}2.ts" to media))
            assertEquals(expected, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
        }
    }

    @Test fun cappedSamplesWithoutDeclaredBitrateStayUnknown() = runBlocking {
        val large = media.copyOf(2 * 1024 * 1024)
        val net = Fixture(mapOf("${base}live.m3u8" to playlist().toByteArray(),
            "${base}1.ts" to large, "${base}2.ts" to large))
        val result = assessor(net).assess(StreamChoice("Live", "${base}live.m3u8"))
        assertEquals(ConnectionStatus.UNKNOWN, result.status)
        assertNull(result.headroom)
        assertEquals(3, net.closed)
    }

    @Test fun cappedSamplesUseAdvertisedBitrateRatherThanPretendingToBeComplete() = runBlocking {
        val net = Fixture(mapOf(
            "${base}master.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\nlive.m3u8".toByteArray(),
            "${base}live.m3u8" to playlist(60.0).toByteArray(),
            "${base}1.ts" to media.copyOf(2 * 1024 * 1024), "${base}2.ts" to media.copyOf(2 * 1024 * 1024),
        ))
        val result = assessor(net).assess(StreamChoice("Live", "${base}master.m3u8"))
        assertEquals(ConnectionStatus.MEDIUM, result.status)
        assertEquals(512 * 1024 * 8.0 / 4_000_000, result.headroom)
    }

    @Test fun realProviderStyleRangesMeasureDirectPlaylistsWithoutInventingResolution() = runBlocking {
        var closed = 0
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                if (url.endsWith("m3u8")) return UpstreamResponse(200, url, mapOf("content-type" to "text/html"), playlist().byteInputStream())
                assertEquals("bytes=0-524287", range)
                // Actual Papaz/Taraftarium responses use image/jpeg even for MPEG-TS.
                return UpstreamResponse(206, url, mapOf("content-type" to "image/jpeg",
                    "content-length" to "524288", "content-range" to "bytes 0-524287/1048576"), media.copyOf(512 * 1024).inputStream()) { closed++ }
            }
        }
        val result = assessor(net).assess(StreamChoice("Live", "${base}live.m3u8"))
        assertEquals(ConnectionStatus.GOOD, result.status)
        assertEquals(2.0, result.headroom)
        assertEquals(2_097_152L, result.bitrate)
        assertTrue(result.bitrateEstimated)
        assertTrue(result.headroomEstimated)
        assertEquals("", result.resolution)
        assertEquals(2, closed)
    }

    @Test fun invalidRangeOrTruncatedResponsesCannotReceiveGoodLabels() = runBlocking {
        for (rangeHeader in listOf("bytes 1-524288/1048576", "bytes 0-524287/1048576")) {
            val net = object : Upstream {
                override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse =
                    if (url.endsWith("m3u8")) UpstreamResponse(200, url, emptyMap(), playlist().byteInputStream())
                    else UpstreamResponse(206, url, mapOf("content-range" to rangeHeader), media.inputStream())
            }
            assertEquals(ConnectionStatus.UNAVAILABLE, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
        }
    }

    @Test fun highRequestLatencyIsNotMultipliedAcrossTheWholeSegment() = runBlocking {
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse =
                if (url.endsWith("m3u8")) UpstreamResponse(200, url, emptyMap(), playlist(3.0).byteInputStream())
                else UpstreamResponse(206, url, mapOf("content-range" to "bytes 0-524287/3145728"), media.copyOf(512 * 1024).inputStream())
        }
        // 800 ms startup, then 25 ms for 512 KiB: a 3 MiB object should take ~950 ms,
        // not 825 ms * 6 = 4950 ms. Old extrapolation incorrectly reported SLOW (0.61x).
        val ticks = ArrayDeque(listOf(0L, 800_000_000L, 825_000_000L, 1_000_000_000L, 1_800_000_000L, 1_825_000_000L))
        val result = HlsStreamAssessor(net) { ticks.removeFirst() }.assess(StreamChoice("Live", "${base}live.m3u8"))
        assertEquals(ConnectionStatus.GOOD, result.status)
        assertEquals(3.0 / .95, requireNotNull(result.headroom), .0001)
    }

    @Test fun masterMetadataIsPublishedBeforeSlowSegmentsFinish() = runBlocking {
        val net = Fixture(mapOf("${base}master.m3u8" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720\nmissing.m3u8".toByteArray()))
        val updates = mutableListOf<StreamAssessment>()
        val result = assessor(net).assess(StreamChoice("Live", "${base}master.m3u8"), updates::add)
        assertEquals("1280x720", updates.first().resolution)
        assertEquals(ConnectionStatus.UNKNOWN, updates.first().status)
        assertEquals(ConnectionStatus.UNAVAILABLE, result.status)
        assertEquals("1280x720", result.resolution)
    }

    @Test fun real720StyleAesSegmentsValidateDecryptedMediaAndSequenceBindings() = runBlocking {
        val key = ByteArray(16) { it.toByte() }
        fun encrypt(bytes: ByteArray, sequence: Long): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = java.nio.ByteBuffer.allocate(16).putLong(0).putLong(sequence).array()
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
            return cipher.doFinal(bytes)
        }
        for ((bytes, status) in listOf(media to ConnectionStatus.GOOD, ByteArray(1024) to ConnectionStatus.UNAVAILABLE)) {
            val net = Fixture(mapOf(
                "${base}live.m3u8" to ("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" +
                    "#EXTINF:4,\n1.ts\n#EXTINF:4,\n2.ts").toByteArray(),
                "${base}key" to key, "${base}1.ts" to encrypt(bytes, 42), "${base}2.ts" to encrypt(bytes, 43),
            ))
            assertEquals(status, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
            assertEquals(1, net.requests.count { it.first.endsWith("/key") })
            assertEquals(net.requests.size, net.closed)
        }
    }

    @Test fun encryptionKeyRotationAndReturnToClearMediaAreBoundToTheirSegments() = runBlocking {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val net = Fixture(mapOf(
            "${base}live.m3u8" to ("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"expired-key\"\n#EXTINF:4,\nold.ts\n" +
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x0\n#EXTINF:4,\n1.ts\n" +
                "#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\n2.ts").toByteArray(),
            "${base}key" to key, "${base}1.ts" to cipher.doFinal(media), "${base}2.ts" to media,
        ))
        assertEquals(ConnectionStatus.GOOD, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
        assertFalse(net.requests.any { it.first.endsWith("expired-key") })
    }

    @Test fun htmlAndEmptyPlaylistsNeverGetGoodConnectionLabels() = runBlocking {
        for (text in listOf("<html>Denied</html>", "#EXTM3U")) {
            val net = Fixture(mapOf("${base}live.m3u8" to text.toByteArray()))
            assertNotEquals(ConnectionStatus.GOOD, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
        }
        val net = Fixture(mapOf("${base}live.m3u8" to playlist().toByteArray(), "${base}1.ts" to "<html>Denied</html>".toByteArray()))
        assertEquals(ConnectionStatus.UNAVAILABLE, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
        assertEquals(net.requests.size, net.closed)
    }

    @Test fun byteRangePlaylistsRemainUntestedWithoutFetchingSharedObjects() = runBlocking {
        val net = Fixture(mapOf("${base}live.m3u8" to "#EXTM3U\n#EXTINF:4,\n#EXT-X-BYTERANGE:100@0\nshared.ts".toByteArray()))
        assertEquals(ConnectionStatus.UNKNOWN, assessor(net).assess(StreamChoice("Live", "${base}live.m3u8")).status)
        assertEquals(1, net.requests.size)
    }

    @Test fun usesRenewablePlaybackSessionAndPropagatesCancellation() = runBlocking {
        val net = Fixture(emptyMap())
        var calls = 0
        val session = object : PlaybackSession {
            override val id = "fixture"
            override suspend fun open(url: String, range: String?): UpstreamResponse {
                calls++
                throw CancellationException("Stop")
            }
        }
        assertFailsWith<CancellationException> {
            assessor(net).assess(StreamChoice("Live", "${base}live.m3u8", session = session))
        }
        assertEquals(1, calls)
        assertTrue(net.requests.isEmpty())
    }

    @Test fun wireShowsUsefulMetadataWithoutCredentialsOrPlaybackPromises() {
        val choice = StreamChoice("English commentary", "${base}master.m3u8?token=secret", providers = listOf("Fixture"),
            assessment = StreamAssessment(ConnectionStatus.GOOD, "1920x1080", 50.0, 6_000_000, true, 3.0, 2))
        val wire = StremioWire(HostConfig(desktop = true))
        val json = wire.stream(choice) { "https://relay.example/local.m3u8" }
        val title = json.getValue("title").jsonPrimitive.content
        assertEquals("Fixture · GOOD", json.getValue("name").jsonPrimitive.content)
        assertEquals("1920x1080 · 50fps · 6.0 Mb", title)
        assertFalse(json.toString().contains("secret"))
    }

    @Test fun wireUsesExactlyTheRequestedMeasuredLabelsAndReportsCachedSampleAge() {
        val wire = StremioWire(HostConfig())
        for (status in listOf(ConnectionStatus.GOOD, ConnectionStatus.MEDIUM, ConnectionStatus.SLOW)) {
            val choice = StreamChoice("Live", "${base}live.m3u8", assessment = StreamAssessment(status,
                headroom = 1.5, samples = 2, checkedAtMillis = 1000))
            val json = wire.stream(choice, 24_000) { "https://relay.example/local.m3u8" }
            assertTrue(json.getValue("name").jsonPrimitive.content.endsWith(" · ${status.name}"))
            assertEquals("23s ago", json.getValue("title").jsonPrimitive.content)
        }
        for (status in listOf(ConnectionStatus.UNKNOWN, ConnectionStatus.UNAVAILABLE)) {
            val title = wire.stream(StreamChoice("Live", "${base}live.m3u8", assessment = StreamAssessment(status))) { "https://relay.example/local.m3u8" }
                .getValue("title").jsonPrimitive.content
            assertEquals("", title)
        }
    }

    @Test fun wireUsesTwoCompactLinesWithMeasuredMetadata() {
        val choice = StreamChoice("English HD", "${base}live.m3u8", providers = listOf("PapazSports"),
            assessment = StreamAssessment(ConnectionStatus.GOOD, resolution = "1920x1080", frameRate = 50.0,
                bitrate = 5_200_000, samples = 2, checkedAtMillis = 1000, resolutionMeasured = true))
        val json = StremioWire(HostConfig()).stream(choice, 5000) { "https://relay.example/local.m3u8" }
        assertEquals("PapazSports · GOOD", json.getValue("name").jsonPrimitive.content)
        assertEquals("1920x1080 · 50fps · 5.2 Mb\n4s ago", json.getValue("title").jsonPrimitive.content)
    }
}
