package app.futbolio.engine.testing

import app.futbolio.application.api.*
import app.futbolio.application.ports.Upstream
import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig
import app.futbolio.engine.api.EngineControl
import app.futbolio.engine.runtime.FutbolioEngine
import app.futbolio.engine.runtime.TransportOwnership

/** Test-only composition seam; not a configurable production network bypass. */
object EngineFixtures {
    fun create(host: HostConfig, sources: List<SourceConfig>, poster: PosterRenderer, upstream: Upstream,
        presentation: PresentationConfig = PresentationConfig()): EngineControl =
        FutbolioEngine(host, sources, poster, upstream, presentation = presentation, ownership = TransportOwnership.BORROWED)
}
