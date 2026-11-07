package app.futbolio.engine

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.adapters.hls.HlsDeclaredMetadata
import app.futbolio.engine.adapters.providers.shared.StaticPlayerResolver
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MinimumMetadataTest {
    private val master = """#EXTM3U
        |#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,FRAME-RATE=25
        |low.m3u8
        |#EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,FRAME-RATE=50,CODECS="avc1.640028,mp4a.40.2"
        |high.m3u8
    """.trimMargin()

    @Test fun alreadyFetchedPlaylistContributesMinimumMetadataWithoutAnyAdditionalRequest() = runBlocking {
        var requests = 0
        val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                requests++
                assertEquals("https://fixture.example/player", url)
                assertNull(range)
                return UpstreamResponse(200, url, emptyMap(), master.byteInputStream())
            }
        }
        val choice = StaticPlayerResolver(net).resolve(Listing(source, "Match", "Premier League", "${source.url}player")).single()
        assertEquals(1, requests, "Do not request a variant or a segment just for minimum-set metadata")
        assertEquals("1920x1080", choice.assessment.resolution)
        assertEquals(50.0, choice.assessment.frameRate)
        assertEquals(5_200_000L, choice.assessment.bitrate)
        assertTrue(choice.assessment.adaptive)
        assertEquals(ConnectionStatus.UNKNOWN, choice.assessment.status)
        assertEquals(0, choice.assessment.samples)
    }

    @Test fun missingMalformedAndOutOfBoundsMetadataIsNotInvented() {
        assertEquals(StreamAssessment(), HlsDeclaredMetadata.read("<html>Not a playlist</html>"))
        assertEquals(StreamAssessment(), HlsDeclaredMetadata.read("#EXTM3U\n#EXTINF:4,\nsegment.ts"))
        assertEquals(StreamAssessment(), HlsDeclaredMetadata.read("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1920x1080"))
        val malformed = HlsDeclaredMetadata.read("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=-1,RESOLUTION=HD,FRAME-RATE=Infinity\na.m3u8")
        assertEquals("", malformed.resolution)
        assertNull(malformed.bitrate)
        assertNull(malformed.frameRate)
        assertEquals(StreamAssessment(), HlsDeclaredMetadata.read("#EXTM3U\n" + "#pad\n".repeat(15_000) + master.removePrefix("#EXTM3U")))
    }
}
