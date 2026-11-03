package app.futbolio.desktop

import app.futbolio.engine.*
import app.futbolio.engine.testing.EngineFixtures
import app.futbolio.application.api.*
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.engine.api.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopTest {
    @Test fun settingsPersistAndTokenStable() = runBlocking {
        val dir = Files.createTempDirectory("futbolio-desktop-test")
        val host = DesktopHost(dir); val token = host.settings.value.token
        try {
            host.saveConfig(FutbolioConfig(sources = emptyList()))
            host.saveSettings(host.settings.value.copy(port = 17001))
            DesktopHost(dir).use { restored -> assertEquals(token, restored.settings.value.token); assertEquals(17001, restored.settings.value.port); assertTrue(restored.configuration.value.config.sources.isEmpty()) }
        } finally { host.close() }
    }
    @Test fun localhostUsesSharedTokenFreeManifestWhileLanKeepsToken() {
        val local = DesktopSettings(token = HostTokens.create())
        assertEquals("http://127.0.0.1:7000/manifest.json", local.host().manifest)
        val lan = DesktopSettings(lanAddress = "192.168.1.20", token = HostTokens.create())
        assertTrue(lan.host().manifest.contains("/a/"))
    }
    @Test fun corruptSettingsAreNotOverwritten() {
        val dir = Files.createTempDirectory("futbolio-corrupt-test"); val file = dir.resolve("providers.json"); Files.writeString(file, "{broken")
        DesktopHost(dir).use { host -> assertTrue(host.error.value.isNotBlank()); assertEquals("{broken", Files.readString(file)) }
    }
    @Test fun failedSaveDoesNotPublishConfiguration() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("futbolio-save-failure")
        DesktopHost(dir).use { host ->
            val before = host.configuration.value.config
            Files.createDirectory(dir.resolve("providers.json"))
            assertFails { host.saveConfig(before.copy(sources = emptyList())) }
            assertEquals(before, host.configuration.value.config)
        }
    }
    @Test fun realHostStartsAndStops() = runBlocking {
        val host = DesktopHost(Files.createTempDirectory("futbolio-host-test"))
        try {
            val port = ServerSocket(0).use { it.localPort }
            host.saveConfig(FutbolioConfig(sources = emptyList())); host.saveSettings(host.settings.value.copy(port = port))
            host.start(); assertTrue(host.runtime.value.running)
            val connection = URI(host.settings.value.host().manifest).toURL().openConnection() as HttpURLConnection
            try { assertEquals(200, connection.responseCode); assertTrue(connection.inputStream.bufferedReader().readText().contains("community.futbolio.local")) } finally { connection.disconnect() }
            assertFails { host.saveSettings(host.settings.value.copy(port = port + 1)) }
            host.stop(); assertEquals(RuntimePhase.STOPPED, host.runtime.value.phase)
        } finally { host.close() }
    }
    @Test fun providerChangesReachRunningEngine() = runBlocking {
        val host = DesktopHost(Files.createTempDirectory("futbolio-config-test"))
        try {
            host.saveConfig(FutbolioConfig(sources = emptyList())); host.saveSettings(host.settings.value.copy(port = ServerSocket(0).use { it.localPort })); host.start()
            val config = FutbolioConfig(sources = listOf(Configurations.sources().first().copy(enabled = false)))
            host.saveConfig(config)
            val state = withTimeout(2000) { host.runtime.first { it.providers.size == 1 } }
            assertFalse(state.providers.single().enabled)
        } finally { host.close() }
    }
    @Test fun posterIsValidSquareImage() { val bytes = DesktopPosters().render("Fenerbahçe - Göztepe", "Futbolio"); val image = ImageIO.read(bytes.inputStream()); assertEquals(640, image.width); assertEquals(640, image.height) }
    @Test fun disconnectedInterfaceRejected() { assertFails { DesktopSettings(lanAddress = "203.0.113.10").validate() } }
    @Test fun lanApiAndRelayUseAdvertisedAddress() = runBlocking {
        val ip = lanAddresses().firstOrNull()
        org.junit.Assume.assumeTrue("No LAN interface available", ip != null)
        val f = FixtureUpstream(); val port = ServerSocket(0).use { it.localPort }
        val e = EngineFixtures.create(HostConfig(port, ip!!, ip, HostTokens.create(), desktop = true), listOf(f.source), DesktopPosters(), f)
        fun get(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).let { c -> try { c.connectTimeout = 3000; c.readTimeout = 5000; c.inputStream.readBytes() } finally { c.disconnect() } }
        try {
            e.start()
            assertTrue(get(e.host.manifest).toString(Charsets.UTF_8).contains("Futbolio"))
            val catalog = Json.parseToJsonElement(get("${e.host.base}/catalog/tv/channels.json").toString(Charsets.UTF_8)).obj()
            assertTrue(catalog.a("metas").first().obj().s("poster").startsWith("http://$ip:$port/a/"))
            val id = catalog.a("metas").first().obj().s("id")
            val streams = Json.parseToJsonElement(get("${e.host.base}/stream/tv/${Web.enc(id)}.json").toString(Charsets.UTF_8)).obj().a("streams")
            val playlist = get(streams.first().obj().s("url")).toString(Charsets.UTF_8); val segment = playlist.lines().first { it.startsWith("http:") }
            assertTrue(segment.startsWith("http://$ip:$port/a/")); assertContentEquals(f.segment, get(segment))
        } finally { e.close() }
    }
}
