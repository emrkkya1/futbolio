package app.futbolio.engine

import app.futbolio.application.ports.*
import app.futbolio.engine.adapters.probes.SegmentProbe
import app.futbolio.domain.provider.SourceConfig

import kotlinx.coroutines.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class LiveProviderVerifierTest {
    private val source = SourceConfig("fixture", "ppv", "Fixture", "https://fixture.example/", "https://fixture.example/api")
    private class Fixture(private val mode: String = "ok") : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
            if (mode == "cancel") throw CancellationException("cancelled")
            if (mode == "blocked") awaitCancellation()
            val bytes = when {
                url == "https://fixture.example/api" -> {
                    if (mode == "discovery") error("A secret URL must not appear in reports")
                    val start = System.currentTimeMillis() / 1000 + if (mode == "future") 3600 else -60
                    val rows = if (mode == "empty") "[]" else """[{"name":"Home vs Away","uri_name":"fixture","always_live":${mode == "channels"},"starts_at":$start,"ends_at":${start + 7200},"iframe":"https://embed.example/game"}]"""
                    """{"streams":[{"category":"Football","streams":$rows}]}""".toByteArray()
                }
                url == "https://embed.example/game" -> (if (mode == "resolve") "<script src='/opaque.js'></script>" else "<script>file:'https://cdn.example/live.m3u8'</script>").toByteArray()
                url.endsWith("live.m3u8") -> "#EXTM3U\n#EXTINF:4,\npart1.ts\n#EXTINF:4,\npart2.ts".toByteArray()
                url.contains("part") -> if (mode == "segment") ByteArray(1000) { 65 } else ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0 }
                else -> error("Unexpected fixture request")
            }
            return UpstreamResponse(200, url, emptyMap(), bytes.inputStream())
        }
    }
    @Test fun oneProvidersSuccessNeverMasksAnotherProvidersFailure() = runBlocking {
        val good = LiveProviderVerifier(Fixture()).verify(source)
        val bad = LiveProviderVerifier(Fixture("segment")).verify(source.copy(id = "broken"))
        assertEquals("PASS", good.outcome); assertEquals(1, good.matches); assertEquals(2, good.mediaObjects); assertTrue(good.mediaBytes > 0)
        assertEquals("SEGMENT_FAILED", bad.outcome); assertEquals(1, bad.matches); assertEquals(0, bad.mediaBytes)
    }
    @Test fun stageFailuresRemainDistinct() = runBlocking {
        for ((mode, expected) in mapOf("discovery" to "DISCOVERY_FAILED", "empty" to "DISCOVERY_FAILED", "channels" to "NO_MATCHES", "future" to "NO_ACTIVE_MATCHES", "resolve" to "RESOLUTION_FAILED")) {
            val report = LiveProviderVerifier(Fixture(mode)).verify(source)
            assertEquals(expected, report.outcome)
            assertEquals(0, report.mediaBytes)
            assertFalse(report.toString().contains("secret URL"))
        }
    }
    @Test fun providerBudgetAndCancellationAreBounded() = runBlocking {
        assertEquals("PROVIDER_TIMEOUT", LiveProviderVerifier(Fixture("blocked"), budgetMs = 25).verify(source).outcome)
        assertFailsWith<CancellationException> { LiveProviderVerifier(Fixture("cancel")).verify(source) }
        Unit
    }
    @Test fun strictProbeDecryptsImplicitIvAndRejectsEncryptedGarbage() = runBlocking {
        val key = ByteArray(16) { it.toByte() }
        val iv = java.nio.ByteBuffer.allocate(16).putLong(0).putLong(7).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(ByteArray(188 * 8) { if (it % 188 == 0) 0x47 else 0 })
        fun net(bad: Boolean) = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                val bytes = when {
                    url.endsWith(".m3u8") -> "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\nsegment".toByteArray()
                    url.endsWith("/key") -> key
                    else -> if (bad) ByteArray(encrypted.size) else encrypted
                }
                return UpstreamResponse(200, url, emptyMap(), bytes.inputStream())
            }
        }
        val choice = StreamChoice("Fixture", "https://cdn.example/live.m3u8")
        assertEquals("AES-128 → MPEG-TS", SegmentProbe(net(false)).probe(choice, verifyMedia = true).single().format)
        assertFails { SegmentProbe(net(true)).probe(choice, verifyMedia = true) }
        Unit
    }
}
