package app.futbolio.desktop.cli

import app.futbolio.desktop.atomicWrite
import app.futbolio.engine.api.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun runCommandLine(args: Array<String>): Boolean {
    if ("--check-sources" in args) {
        runBlocking { for (result in ProviderTools.checkSources())
            println("${result.name}: ${if (result.error.isNotBlank()) result.error else "${result.count} listings"}${if (result.warning.isBlank()) "" else " · ${result.warning}"}") }
        return true
    }
    if ("--probe-provider" !in args) return false
    runBlocking {
        fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val id = option("--probe-provider") ?: error("Specify a built-in provider ID")
        val defaults = Configurations.sources()
        val selected = if (id == "all") defaults else listOf(defaults.find { it.id == id } ?: error("Choose ${Configurations.descriptors.joinToString { it.kind }}"))
        val sources = selected.map { base -> base.copy(url = option("--url") ?: base.url, apiUrl = option("--api-url") ?: base.apiUrl,
            dataUrl = option("--data-url") ?: base.dataUrl, fallbackUrls = option("--fallback")?.split(',') ?: base.fallbackUrls,
            numberedDomainWindow = option("--domain-window")?.toIntOrNull() ?: base.numberedDomainWindow) }
        for (report in ProviderTools.probe(sources, option("--listing"))) {
            option("--report-dir")?.let { atomicWrite(Path.of(it).toAbsolutePath().resolve("${report.providerId}.json"), report.export()) }
            if ("--summary" in args) {
                println("${report.providerName}: ${report.phase}, ${report.catalogCount} listings, domain=${report.activeDomain}, error=${report.error}")
                report.listings.forEach { listing ->
                    println("  ${listing.title}: ${listing.error}")
                    listing.choices.forEach { choice -> println("    ${choice.stage}: ${choice.segments.sumOf { it.bytes }} bytes; ${choice.error}; playback=${choice.playback}") }
                }
            } else println(report.export())
        }
    }
    return true
}
