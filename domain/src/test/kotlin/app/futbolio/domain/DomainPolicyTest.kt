package app.futbolio.domain

import app.futbolio.domain.text.TextPolicy
import app.futbolio.domain.identity.StableIdentity
import app.futbolio.domain.provider.SourceUrl
import kotlin.test.*

class DomainPolicyTest {
    @Test fun identityIsByteCompatible() {
        assertEquals("254e293007bae4e03175e2ff", StableIdentity.hash("fixture|https://fixture.example/game|Home vs Away|1893456000000"))
    }

    @Test fun classificationWorksWithoutHtmlOrNetworkLibraries() {
        assertEquals("besiktas", TextPolicy.normalize("Beşiktaş"))
        assertTrue(TextPolicy.isTurkish("Fenerbahçe"))
        assertFalse(TextPolicy.isTurkish("Arsenal Chelsea"))
        assertTrue(TextPolicy.isOtherSport("Basketbol Süper Ligi"))
    }

    @Test fun sourceSyntaxDoesNotDependOnDns() {
        assertEquals("fixture.example", SourceUrl.parse("https://fixture.example/path").host)
        for (value in listOf("file:///private", "https://user:pass@example.com", "https://example.com/#secret")) {
            assertFailsWith<IllegalArgumentException> { SourceUrl.parse(value) }
        }
    }
}
