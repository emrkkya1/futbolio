package app.futbolio.engine.adapters.providers

import app.futbolio.application.ports.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.adapters.providers.shared.discoverLanding
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ProviderLandingTest {
    @Test fun bitlyLiteralEntryResolvesToCurrentProviderWithoutRunningScripts() = runBlocking {
        val source = SourceConfig("entry", "fixture", "Fixture", "https://bit.ly/m/fixture")
        val calls = mutableListOf<String>()
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                calls += url
                return when (url) {
                    source.url -> UpstreamResponse(200, url, emptyMap(), """<script>
                        window.launchpadData={"buttons":[
                          {"title":"Uygulama İndir","bitlink":"bit.ly/download","is_active":true},
                          {"title":"Siteye Giriş","bitlink":"bit.ly/inactive","is_active":false},
                          {"title":"Siteye Giriş","content":{"target":"bit.ly/current"},"is_active":true}]};
                        throw new Error('must never run');</script>""".byteInputStream())
                    "https://bit.ly/current" -> UpstreamResponse(200, "https://current.example/", emptyMap(), "<main>Fixture site</main>".byteInputStream())
                    else -> error("Unexpected entry link")
                }
            }
        }
        assertEquals("https://current.example/", discoverLanding(source, net).url)
        assertEquals(listOf(source.url, "https://bit.ly/current"), calls)
    }

    @Test fun labeledHtmlEntryStillWorksAndLoopsAreBounded() = runBlocking {
        val source = SourceConfig("entry", "fixture", "Fixture", "https://entry.example/")
        var calls = 0
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                calls++
                val next = if (url == source.url) "https://current.example/" else source.url
                return UpstreamResponse(200, url, emptyMap(), "<a href='$next'>Canlı Maç Girişi</a>".byteInputStream())
            }
        }
        assertFails { discoverLanding(source, net) }
        assertEquals(2, calls)
    }

    @Test fun bitlyPreviewFollowsOnlyItsExplicitDestinationLink() = runBlocking {
        val source = SourceConfig("entry", "fixture", "Fixture", "https://bit.ly/fixture")
        val calls = mutableListOf<String>()
        val net = object : Upstream {
            override suspend fun open(url: String, headers: SourceHeaders, range: String?): UpstreamResponse {
                calls += url
                return if (url == source.url) UpstreamResponse(200, url, emptyMap(),
                    "<a href='https://ads.example/'>Learn how</a><a href='https://published.example/'>Continue to destination</a>".byteInputStream())
                else UpstreamResponse(200, "https://current.example/", emptyMap(), "<main>Provider</main>".byteInputStream())
            }
        }
        assertEquals("https://current.example/", discoverLanding(source, net).url)
        assertEquals(listOf(source.url, "https://published.example/"), calls)
    }
}
