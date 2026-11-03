package app.futbolio.engine

import app.futbolio.domain.catalog.*
import app.futbolio.domain.match.*
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.Configurations

import kotlin.test.*

class CatalogAggregationTest {
    @Test fun realisticMultiProviderCatalogPreservesEveryFixture() {
        val source = Configurations.sources().first { it.kind == "720pstream" }
        val now = System.currentTimeMillis()
        val rows = (1..400).map { i -> Listing(source, "Home $i vs Away $i", "Football", "https://site.example/live/$i", starts = now) }
        val start = System.nanoTime()
        val entries = MatchNormalizer.aggregate(rows, emptyList(), PresentationConfig(), now)
        println("Aggregate 400 matches: ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(400, entries.size)
        assertEquals(400, entries.map { it.id }.distinct().size)
    }
}
