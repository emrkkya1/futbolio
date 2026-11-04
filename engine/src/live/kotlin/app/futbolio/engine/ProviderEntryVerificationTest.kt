package app.futbolio.engine

import app.futbolio.application.ports.SourceHeaders
import app.futbolio.engine.api.Configurations
import app.futbolio.engine.adapters.providers.papaz.PapazProvider
import app.futbolio.engine.adapters.providers.shared.discoverLanding
import app.futbolio.engine.adapters.providers.shared.publishedEntry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.*

/** Manual live task only: prove that public entry links don't become media/browser URLs. */
class ProviderEntryVerificationTest {
    @Test fun papazResolvesItsPublishedEntryToTheCurrentPortal() = runBlocking {
        withTimeout(35_000) {
            HttpUpstream().use { net ->
                val source = Configurations.sources().first { it.kind == "papaz" }
                val entry = net.text(source.url)
                println("Papaz entry: host=${Web.url(entry.url).host}; literal=${entry.text.contains("launchpadData")}; publishedLink=${publishedEntry(entry)?.let { Web.url(it).host }}")
                val home = discoverLanding(source, net)
                println("Papaz portal: host=${Web.url(home.url).host}; bytes=${home.text.length}; publishedPlayerConfig=${home.text.contains("phptojs")}")
                println("Papaz entry-page title=${Text.match(home.text, "<title[^>]*>(.*?)</title>")}; links=${Text.anchors(home.text, home.url).take(12).map { Web.url(it.first).host to it.second.take(80) }}")
                assertFalse(Web.url(home.url).host in setOf("bit.ly", "linktr.ee"))
                val rows = PapazProvider.parsePortal(source, home.text, home.url)
                println("Papaz portal listings=${rows.size}; channels=${rows.count { it.channel }}")
                assertTrue(rows.isNotEmpty())
                assertTrue(rows.all { Web.url(it.page).host == Web.url(home.url).host })
            }
        }
    }
}
