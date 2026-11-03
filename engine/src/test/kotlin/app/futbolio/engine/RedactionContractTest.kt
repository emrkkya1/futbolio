package app.futbolio.engine

import app.futbolio.application.api.HostConfig

import app.futbolio.application.ports.*
import app.futbolio.application.resolution.deduplicateStreams

import kotlin.test.*

class RedactionContractTest {
    private val secret = "canary-secret-never-log"

    @Test fun credentialBearingModelsHaveSafeStringRepresentations() {
        val headers = SourceHeaders(
            referer = "https://fixture.example/?token=$secret",
            extra = mapOf("usertoken" to secret), authOrigin = "https://auth.example", authToken = secret,
        )
        val choice = StreamChoice("Fixture", "https://media.example/live.m3u8?token=$secret", headers)
        assertFalse(headers.toString().contains(secret))
        assertFalse(choice.toString().contains(secret))
        val host = HostConfig(accessToken = "a".repeat(48), desktop = true)
        assertFalse(host.toString().contains(host.accessToken))
    }

    @Test fun redactionDoesNotMergeDifferentCredentialProfiles() {
        val a = StreamChoice("Fixture", "https://media.example/live.m3u8", SourceHeaders(extra = mapOf("usertoken" to "first-secret")))
        assertEquals(2, deduplicateStreams(listOf(a, a.copy(headers = SourceHeaders(extra = mapOf("usertoken" to "second-secret"))))).size)
        assertEquals(1, deduplicateStreams(listOf(a, a.copy(title = "Another label"))).size)
    }
}
