package app.futbolio.engine

import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.api.Configurations

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.assertEquals

/** Explicit task only. One provider's pass can never mask another's failure. */
@RunWith(Parameterized::class)
class LiveProviderVerificationTest(private val label: String, private val source: SourceConfig) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun providers(): Collection<Array<Any>> = Configurations.sources().filter { it.enabled }.map { arrayOf<Any>(it.name, it) }
    }

    @Test fun discoversMatchesAndFetchesVerifiedSegments() = runBlocking {
        val directory = File("build/reports/live-providers").apply { mkdirs() }
        val file = File(directory, "${source.id}.json")
        file.writeText(codec.encodeToString(LiveProviderReport(source.id, source.name, outcome = "RUNNING")))
        val report = HttpUpstream().use { LiveProviderVerifier(it).verify(source) }
        file.writeText(codec.encodeToString(report))
        println("$label: ${report.outcome}; ${report.matches} matches; ${report.mediaBytes} verified bytes; native playback NOT VERIFIED")
        assertEquals("PASS", report.outcome, "$label did not verify media; see the redacted provider report")
    }
}
