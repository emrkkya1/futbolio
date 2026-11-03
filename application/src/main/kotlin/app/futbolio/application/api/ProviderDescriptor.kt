package app.futbolio.application.api

import app.futbolio.domain.provider.SourceConfig

enum class ProviderSetting { API_URL, DATA_URL }
data class ProviderDescriptor(val source: SourceConfig, val settings: Set<ProviderSetting> = emptySet()) {
    val kind get() = source.kind
    val defaultTimeZone get() = source.timeZone
}
