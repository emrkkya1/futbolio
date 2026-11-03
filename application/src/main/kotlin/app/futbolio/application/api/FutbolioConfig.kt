package app.futbolio.application.api

import app.futbolio.domain.provider.SourceConfig
import app.futbolio.domain.settings.PresentationConfig

/** Desired settings, independent of storage encoding and the bundled adapter set. */
data class FutbolioConfig(
    val schema: Int = 1,
    val sources: List<SourceConfig> = emptyList(),
    val presentation: PresentationConfig = PresentationConfig(),
    val defaultsRevision: Int = 4,
) {
    companion object
}
