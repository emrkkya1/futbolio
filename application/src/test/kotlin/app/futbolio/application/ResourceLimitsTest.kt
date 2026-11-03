package app.futbolio.application

import app.futbolio.application.api.ResourceLimits
import kotlin.test.*

class ResourceLimitsTest {
    @Test fun existingSafetyEnvelopeIsExplicit() {
        val limits = ResourceLimits()
        assertEquals(4096, limits.relayTokens)
        assertEquals(32 * 1024, limits.mediaBufferBytes)
        assertEquals(64L * 1024 * 1024, limits.mediaObjectBytes)
        assertEquals(4, limits.androidMediaSlots)
    }

    @Test fun invalidBudgetsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ResourceLimits(activeResolutions = 0) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(pendingResolutions = 1) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(staleCatalogMillis = 1) }
    }
}
