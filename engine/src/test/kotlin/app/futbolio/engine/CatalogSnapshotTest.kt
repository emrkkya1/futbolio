package app.futbolio.engine

import app.futbolio.engine.adapters.providers.ProviderRegistry

import app.futbolio.application.ports.*
import app.futbolio.application.catalog.Catalog
import app.futbolio.domain.provider.SourceConfig

import kotlinx.coroutines.*
import kotlin.test.*

class CatalogSnapshotTest {
    @Test fun firstSuccessfulProviderDoesNotWaitForBlockedProvider() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fast = SourceConfig("fast", "ppv", "Fast", "https://fast.example/", "https://fast.example/api")
        val slow = fast.copy(id = "slow", name = "Slow", apiUrl = "https://slow.example/api")
        val blocked = CompletableDeferred<Unit>()
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                if (Web.url(url).host == "slow.example") { blocked.complete(Unit); awaitCancellation() }
                blocked.await()
                val payload = """{"streams":[{"category":"Football","streams":[{"name":"Fast match","always_live":true,"iframe":"https://embed.example/","uri_name":"fixture"}]}]}"""
                return UpstreamResponse(200, url, emptyMap(), payload.byteInputStream())
            }
        }
        try {
            val catalog = Catalog(scope, net, listOf(fast, slow), ProviderRegistry())
            val rows = withTimeout(1500) { catalog.snapshot() }
            assertEquals("Fast match", rows.single().title)
            assertTrue(catalog.status.value.first { it.id == "slow" }.loading)
        } finally { scope.cancel() }
    }
    @Test fun entirelyBlockedRefreshReturnsQuickEmptyResponseAndKeepsRefreshing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = SourceConfig("slow", "ppv", "Slow", "https://slow.example/", "https://slow.example/api")
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse = awaitCancellation()
        }
        try {
            val catalog = Catalog(scope, net, listOf(source), ProviderRegistry())
            assertTrue(withTimeout(3500) { catalog.snapshot() }.isEmpty())
            assertTrue(catalog.status.value.single().loading)
        } finally { scope.cancel() }
    }
}
