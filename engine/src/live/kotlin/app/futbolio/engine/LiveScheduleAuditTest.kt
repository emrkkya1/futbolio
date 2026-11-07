package app.futbolio.engine

import app.futbolio.application.catalog.DomainRecovery
import app.futbolio.domain.catalog.*
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.adapters.providers.ProviderRegistry
import app.futbolio.engine.api.Configurations
import kotlinx.coroutines.*
import org.junit.Test
import java.time.Instant

/** Metadata-only manual audit; never resolves or prints player URLs. */
class LiveScheduleAuditTest {
    @Test fun compareProviderKickoffsAndDuplicateCandidates() = runBlocking<Unit> {
        HttpUpstream().use { net ->
            val providers = ProviderRegistry()
            val now = System.currentTimeMillis()
            val config = PresentationConfig(hideFinished = false)
            val rows = Configurations.sources().map { source -> async {
                try {
                    withTimeout(40_000) { DomainRecovery(net, providers).discover(source).listings }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    println("SCHEDULE ${source.name}: ${failure.javaClass.simpleName}")
                    emptyList()
                }
            } }.awaitAll().flatten().map { if (it.observedAt == 0L) it.copy(observedAt = now) else it }
            val entries = MatchNormalizer.aggregate(rows, emptyList(), config, now)
            val byTeam = entries.filter { !it.channel && it.home != null && it.away != null }.flatMap { entry ->
                listOf(entry.home!!.name, entry.away!!.name).map { MatchNormalizer.teamKey(it, config) to entry }
            }.groupBy({ it.first }, { it.second })
            val duplicates = byTeam.filterValues { it.size > 1 }.values.flatten().map { it.id }.toSet()
            for (entry in entries.filter { it.id in duplicates || it.title.contains("Galatasaray", true) }) {
                println("GROUP ${entry.title}; league=${entry.league.id}; start=${Instant.ofEpochMilli(entry.starts)}; providers=${entry.sources}; ambiguous=${entry.ambiguous}")
                entry.listings.forEach { println("ROW ${it.source.name}: ${it.title}; league=${it.league}; start=${Instant.ofEpochMilli(MatchNormalizer.kickoff(it, now))}; zone=${it.source.timeZone}") }
            }
            println("SCHEDULE ${rows.size} rows; ${entries.size} groups; ${duplicates.size} groups sharing a team")
        }
    }
}
