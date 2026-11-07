package app.futbolio.application.api

enum class RuntimePhase(val label: String) {
    STOPPED("Stopped"), STARTING("Starting"), RUNNING("Running"), STOPPING("Stopping"), DISPOSED("Disposed");
    override fun toString() = label
}

data class DiagnosticSnapshot(val activeMedia: Int, val mediaBytes: Long, val mediaRequests: Long, val failures: Long, val events: List<String>)

/** UI readiness only; never gates the listener, stream responses, or scheduler. */
data class RelayPreparation(val progress: Float = 0f, val ready: Boolean = false)

data class RuntimeSnapshot(
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val providers: List<ProviderStatus> = emptyList(),
    val reports: Map<String, ProviderTestReport> = emptyMap(),
    val candidates: Map<String, List<ProbeCandidate>> = emptyMap(),
    val diagnostics: DiagnosticSnapshot = DiagnosticSnapshot(0, 0, 0, 0, emptyList()),
    val error: String = "",
    val testBatch: ProviderTestBatch = ProviderTestBatch(),
    val preparation: RelayPreparation = RelayPreparation(),
) { val running get() = phase == RuntimePhase.RUNNING }

data class SaveResult(val saved: Boolean, val error: String = "")

interface MediaActivity {
    companion object { val None: MediaActivity = NoMediaActivity }
    fun started() {}
    fun progress() {}
    fun finished() {}
}
private object NoMediaActivity : MediaActivity

data class PosterModel(
    val title: String, val league: String, val home: String = "", val away: String = "",
    val homeLogo: ByteArray? = null, val awayLogo: ByteArray? = null, val status: String = "", val kickoff: String = "",
    val competitionLogo: ByteArray? = null, val background: ByteArray? = null,
    val kickoffTime: String = "", val kickoffDate: String = "", val live: Boolean = false,
    val statusVerified: Boolean = false,
    val competitionLightBackground: Boolean = false,
    val channelLogo: ByteArray? = null,
    val score: String = "", val minute: String = "",
)
fun interface PosterRenderer {
    fun render(title: String, source: String): ByteArray
    fun renderMatch(model: PosterModel): ByteArray = render(model.title, model.league)
}
