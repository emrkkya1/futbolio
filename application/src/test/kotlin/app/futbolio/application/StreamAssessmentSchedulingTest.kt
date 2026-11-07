package app.futbolio.application

import app.futbolio.application.api.*
import app.futbolio.application.ports.*
import app.futbolio.application.resolution.StreamResolver
import app.futbolio.application.resolution.rankStreams
import app.futbolio.application.resolution.deduplicateStreams
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.provider.SourceConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StreamAssessmentSchedulingTest {
    private val source = SourceConfig("fixture", "fixture", "Fixture", "https://fixture.example/")
    private val row = Listing(source, "Match", "Football", "https://fixture.example/game")
    private val net = object : Upstream {
        override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = error("No network expected")
    }
    private fun providers(count: Int) = object : ProviderDirectory {
        override val descriptors = listOf(ProviderDescriptor(source))
        override fun get(kind: String) = object : Provider {
            override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(listOf(row))
            override suspend fun resolve(item: Listing, net: Upstream) = (1..count).map { StreamChoice("Choice $it", "https://media.example/$it.m3u8") }
        }
    }

    @Test fun ranksMeasuredHeadroomAndMergesProviderEvidenceWithoutDroppingChoices() {
        fun choice(name: String, status: ConnectionStatus, headroom: Double? = null) = StreamChoice(name,
            "https://media.example/$name.m3u8", providers = listOf(name), assessment = StreamAssessment(status, headroom = headroom))
        val good = choice("good", ConnectionStatus.GOOD, 3.0)
        val best = choice("best", ConnectionStatus.GOOD, 6.0)
        val unknown = choice("unknown", ConnectionStatus.UNKNOWN)
        val slow = choice("slow", ConnectionStatus.SLOW, .8)
        val failed = choice("failed", ConnectionStatus.UNAVAILABLE)
        val ordered = rankStreams(listOf(failed, unknown, slow, good, best))
        assertEquals(listOf(best, good, unknown, slow, failed), ordered)
        val merged = deduplicateStreams(listOf(good.copy(assessment = StreamAssessment(), providers = listOf("Other")), good)).single()
        assertEquals(ConnectionStatus.GOOD, merged.assessment.status)
        assertEquals(listOf("Other", "good"), merged.providers)
    }

    @Test fun timeoutPreservesResolvedChoicesAndPublishedMetadata() = runTest {
        val assessor = object : StreamAssessor {
            override suspend fun assess(choice: StreamChoice): StreamAssessment = error("Use progressive assessment")
            override suspend fun assess(choice: StreamChoice, update: (StreamAssessment) -> Unit): StreamAssessment {
                update(StreamAssessment(resolution = "1280x720"))
                awaitCancellation()
            }
        }
        val resolver = StreamResolver(this, net, providers(1), assessor = assessor)
        val choices = resolver.resolve(row)
        assertEquals(2, choices.size)
        assertEquals("1280x720", choices.first().assessment.resolution)
        assertEquals(ConnectionStatus.UNKNOWN, choices.first().assessment.status)
        assertEquals(6000L, testScheduler.currentTime)
        assertEquals(row.page, choices.last().externalUrl)
    }

    @Test fun globalConcurrencyAndBatchDeadlineAreBoundedAndSuccessfulResultsSurvive() = runTest {
        var active = 0
        var peak = 0
        var calls = 0
        val assessor = StreamAssessor { choice ->
            calls++
            active++
            peak = maxOf(peak, active)
            try {
                if (choice.title == "Choice 1") { delay(100); StreamAssessment(ConnectionStatus.GOOD) }
                else { awaitCancellation() }
            } finally { active-- }
        }
        val resolver = StreamResolver(this, net, providers(6), limits = ResourceLimits(activeStreamAssessments = 2), assessor = assessor)
        val choices = resolver.resolve(row)
        assertEquals(7, choices.size)
        assertEquals(ConnectionStatus.GOOD, choices.first().assessment.status)
        assertEquals(ConnectionStatus.UNKNOWN, choices[4].assessment.status)
        assertEquals(2, peak)
        assertEquals(4, calls)
        assertEquals(0, active)
        assertEquals(7000L, testScheduler.currentTime)
        resolver.resolve(row)
        assertEquals(4, calls, "Cached resolution must not re-probe")
    }

    @Test fun invalidationCancelsChecksAndDoesNotPublishObsoleteResults() = runTest {
        var cancelled = false
        val resolver = StreamResolver(this, net, providers(1), assessor = StreamAssessor {
            try { awaitCancellation() } finally { cancelled = true }
        })
        val task = async { resolver.resolve(row) }
        runCurrent()
        resolver.trim()
        assertFailsWith<CancellationException> { task.await() }
        assertTrue(cancelled)
    }
}
