package app.futbolio.engine.adapters.stremio.server

import app.futbolio.application.api.*
import app.futbolio.application.catalog.*
import app.futbolio.application.ports.TimeSource
import app.futbolio.application.ports.isHls
import app.futbolio.application.resolution.*
import app.futbolio.application.fixtures.FixtureService
import app.futbolio.application.workbench.ProviderWorkbench
import app.futbolio.domain.catalog.*
import app.futbolio.engine.Text
import app.futbolio.engine.Web
import app.futbolio.engine.adapters.diagnostics.Diagnostics
import app.futbolio.engine.adapters.posters.PosterStore
import app.futbolio.engine.adapters.relay.*
import app.futbolio.engine.adapters.stremio.wire.*
import app.futbolio.engine.api.EngineControl
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Inbound protocol adapter. Business selection, resolution and caches are application services. */
internal class StremioServer(
    private val host: HostConfig,
    private val control: EngineControl,
    private val catalog: Catalog,
    private val entries: EntryCatalog,
    private val resolver: StreamResolver,
    private val workbench: ProviderWorkbench,
    private val fixtures: FixtureService,
    private val posters: PosterStore,
    private val relay: MediaRelay,
    private val tokens: RelayTokens,
    private val diagnostics: Diagnostics,
    private val limits: ResourceLimits,
    private val time: TimeSource,
    private val browsing: () -> Unit = {},
) {
    private val guard = RequestGuard(host)
    private val wire = StremioWire(host)
    private val mediaPermits = Semaphore(if (host.desktop) limits.desktopMediaSlots else limits.androidMediaSlots)
    private val apiPermits = Semaphore(limits.apiSlots)
    private val started = java.util.concurrent.atomic.AtomicBoolean()
    private val server = embeddedServer(CIO, configure = {
        connectors.add(EngineConnectorBuilder().apply { this.host = this@StremioServer.host.bindHost; port = this@StremioServer.host.port })
        connectionIdleTimeoutSeconds = 15
    }) { routing { route("/{path...}") { handle { handleRequest(call) } } } }

    suspend fun start() { started.set(true); server.startSuspend(wait = false) }
    fun stop() { if (started.getAndSet(false)) server.stop(0, 1500, java.util.concurrent.TimeUnit.MILLISECONDS) }

    private suspend fun handleRequest(call: ApplicationCall) {
        val path = guard.path(call) ?: return
        val media = Regex("/hls/([a-f0-9]{48})/media\\.(?:m3u8|bin)").matchEntire(path)
        if (media != null) {
            if (!mediaPermits.tryAcquire()) return text(call, 503, "Relay busy; retry shortly")
            try {
                withTimeout(limits.mediaDeadlineMillis) { relay.serve(media.groupValues[1], call.request.headers[HttpHeaders.Range]) { response ->
                    response.headers.forEach { (key, value) -> call.response.headers.append(key, value) }
                    val type = ContentType.parse(response.contentType)
                    if (call.request.httpMethod == HttpMethod.Head) respondHead(call, response.status, type, response.length)
                    else call.respondOutputStream(type, HttpStatusCode.fromValue(response.status), response.length) { response.write(this) }
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!call.response.isCommitted) text(call, 502, "Stream request failed. Try another source or refresh the channel.") }
            finally { mediaPermits.release() }
            return
        }
        Regex("/poster/([^/]+)\\.(?:png|jpg)").matchEntire(path)?.let { match ->
            val bytes = posters.image(Web.dec(match.groupValues[1])) ?: return text(call, 503, "Poster rendering busy")
            val type = if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) ContentType.Image.JPEG else ContentType.Image.PNG
            if (call.request.httpMethod == HttpMethod.Head) return respondHead(call, 200, type, bytes.size.toLong())
            return call.respondBytes(bytes, type)
        }
        if (!apiPermits.tryAcquire()) return text(call, 503, "Add-on busy; retry shortly")
        try { api(call, path) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (!call.response.isCommitted) json(call, buildJsonObject { put("error", "Invalid or unavailable add-on request") }, 400) }
        finally { apiPermits.release() }
    }

    private suspend fun api(call: ApplicationCall, path: String) {
        when (path) {
            "/manifest.json" -> return json(call, wire.manifest())
            "/", "/configure" -> return text(call, 200, StremioPages.install(host), ContentType.Text.Html)
            "/health.json" -> {
                catalog.refresh()
                return json(call, buildJsonObject {
                    put("status", "running"); put("scope", if (host.desktop) "desktop" else "same-device-only")
                    put("sources", providerStatusesJson(catalog.status.value)); put("relay", diagnosticJson(diagnostics.snapshot()))
                    put("relayTokens", tokens.size()); put("matchDataError", fixtures.lastError)
                })
            }
            "/diagnostics.json" -> return json(call, diagnosticJson(diagnostics.snapshot()))
            "/workbench" -> return text(call, 200, StremioPages.workbench(host, catalog.configuredSources()), ContentType.Text.Html)
            "/workbench/reports.json" -> return json(call, probeReportsJson(workbench.reports.value))
            "/workbench/candidates.json" -> return json(call, probeCandidatesJson(workbench.candidates.value))
        }
        Regex("/workbench/test/([a-z0-9-]+)\\.json").matchEntire(path)?.let { match ->
            if (call.request.headers["X-Futbolio-Action"] != "test") return text(call, 403, "Use the provider workbench to initiate a test")
            val id = match.groupValues[1]
            if (catalog.configuredSources().none { it.id == id }) return text(call, 404, "Unknown provider")
            control.testProvider(id, call.request.queryParameters["listing"])
            return json(call, buildJsonObject { put("started", true) })
        }
        val route = Regex("/(catalog|meta|stream)/tv/([^/]+)(?:/([^/]+))?\\.json").matchEntire(path) ?: return text(call, 404, "Not found")
        val resource = route.groupValues[1]
        val id = Web.dec(route.groupValues[2])
        val extra = route.groupValues[3]
        if (resource == "catalog") {
            if (id != "channels") browsing()
            val extras = if (extra.isNotEmpty()) Web.params(extra) else call.request.queryParameters.entries().associate { it.key to it.value.first() }
            val rows = CatalogSelection.select(if (id == "search") entries.entries(includeHidden = true) else entries.entries(),
                CatalogQuery(id, Text.plain(extras["search"].orEmpty()), extras["skip"]?.toIntOrNull() ?: 0, extras["genre"].orEmpty()), time.wallMillis(), entries.presentation())
            return json(call, buildJsonObject { put("metas", JsonArray(rows.map { wire.meta(it, entries.presentation(), time.wallMillis()) })) })
        }
        if (extra.isNotEmpty()) return text(call, 404, "Not found")
        val resolverRevision = resolver.revision()
        val row = workbench.entries().find { it.id == id } ?: entries.selected(id, if (resource == "meta") 350 else 0)
        if (resource == "meta") return json(call, buildJsonObject { put("meta", row?.let { wire.meta(it, entries.presentation(), time.wallMillis()) } ?: JsonNull) })
        row?.let { resolver.prioritize(it.listings, resolverRevision) }
        val choices = if (row == null) emptyList() else if (id.startsWith("fut:probe:")) workbench.streams(id) else coroutineScope {
            val preparationSlots = Semaphore(limits.activeResolutions)
            val prepared = row.listings.map { listing -> async {
                listing to preparationSlots.withPermit { resolver.prepare(listing, resolverRevision, background = false) }
            } }.awaitAll()
            // Any fresh measured evidence is immediately useful. Do not make a warm hit wait
            // for more benchmarking; Stremio cannot update the same response later anyway.
            val alreadyChecked = prepared.any { (listing, choices) -> choices.any { isHls(it) && it.url.isNotBlank() } && resolver.hasAssessment(listing) }
            val toMeasure = if (alreadyChecked) emptySet() else prepared.filter { (_, choices) -> choices.any { it.url.isNotBlank() && isHls(it) } }
                .distinctBy { it.first.source.kind }.take(limits.measuredProvidersPerMatch).map { it.first.id }.toSet()
            prepared.map { (listing, choices) -> async {
                val result = if (listing.id in toMeasure) resolver.measurePrepared(listing, resolverRevision, background = false).ifEmpty { choices } else choices
                result.map { it.copy(providers = listOf(listing.source.name)) }
            } }.awaitAll().flatten()
        }
        if (resolverRevision != resolver.revision()) return json(call, buildJsonObject { put("streams", JsonArray(emptyList())) })
        val streams = rankStreams(deduplicateStreams(choices.filter { it.url.isNotBlank() && it.externalUrl.isBlank() }))
            .map { wire.stream(it, time.wallMillis()) { choice -> tokens.register(choice.url, choice.headers, playlist = true, session = choice.session) } }
        json(call, buildJsonObject { put("streams", JsonArray(streams)) })
    }
}
