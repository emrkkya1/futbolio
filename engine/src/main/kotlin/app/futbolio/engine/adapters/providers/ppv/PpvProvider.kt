package app.futbolio.engine.adapters.providers.ppv

import app.futbolio.application.ports.*
import app.futbolio.domain.catalog.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.*
import app.futbolio.engine.adapters.providers.shared.StaticPlayerResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal object PpvProvider : Provider {
    override suspend fun resolve(item: Listing, net: Upstream) = StaticPlayerResolver(net).resolve(item.copy(players = players(item, net)))

    override suspend fun discover(source: SourceConfig, net: Upstream) = ProviderResult(
        parse(source, codec.parseToJsonElement(net.text(source.apiUrl.ifBlank { Web.resolve(source.url, "/api/streams") }).text).obj())
    )

    suspend fun players(item: Listing, net: Upstream): List<Player> {
        val path = Web.url(item.page).path
        if (!path.startsWith("/live/")) return item.players
        val api = item.source.apiUrl.ifBlank { Web.resolve(item.source.url, "/api/streams") }
        val endpoint = api.trimEnd('/') + "/" + path.removePrefix("/live/")
        return try {
            parsePlayers(codec.parseToJsonElement(net.text(endpoint, SourceHeaders(item.page)).text).obj(), endpoint).ifEmpty { item.players }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { item.players }
    }

    fun parsePlayers(payload: JsonObject, base: String): List<Player> {
        val data = payload["data"] as? JsonObject ?: payload
        return data.a("sources").take(12).mapNotNull { value ->
            val row = value.obj()
            if (row.s("type") !in listOf("iframe", "hls", "m3u8")) return@mapNotNull null
            runCatching {
                Player(Web.resolve(base, row.s("data").also { require(it.isNotBlank()) }), row.s("name"), row.s("locale"),
                    if (row.s("type") == "iframe") "" else "m3u8")
            }.getOrNull()
        }.distinctBy { it.url }.take(4)
    }

    fun parse(source: SourceConfig, payload: JsonObject, now: Long = System.currentTimeMillis()): List<Listing> {
        require(payload["streams"] is JsonArray) { "PPV catalog format changed" }
        val out = mutableListOf<Listing>()
        for (value in payload.a("streams")) {
            val group = value.obj()
            if (group.s("category").lowercase() !in listOf("football", "soccer")) continue
            for (item in group.a("streams")) {
                val row = item.obj()
                val channel = row.b("always_live")
                val end = row.n("ends_at") * 1000
                if (!channel && end > 0 && end < now) continue
                runCatching {
                    val players = (listOf(item) + row.a("substreams")).mapNotNull { variant ->
                        val player = variant.obj()
                        runCatching { Player(Web.url(player.s("iframe")).toString(), player.s("source_tag"), player.s("locale")) }.getOrNull()
                    }
                    out += Listing(source, Text.plain(row.s("name")), Text.plain(row.s("tag")).ifBlank { "Football" }, Web.resolve(source.url, "/live/${row.s("uri_name")}"),
                        if (channel) ListingKind.CHANNEL else ListingKind.MATCH, if (channel) 0 else row.n("starts_at").coerceAtLeast(0) * 1000,
                        if (channel) 0 else end, poster = row.s("poster"), players = players)
                }
                if (out.size >= 600) return out
            }
        }
        return out
    }
}
