package app.futbolio.application.api

enum class ProbePhase(val wire: String) {
    DISCOVERING("discovering"), RESOLVING("resolving"), FETCHING_SEGMENTS("fetching-segments"),
    COMPLETE("complete"), FAILED("failed"), CANCELLED("cancelled"), BUSY("busy");
    override fun toString() = wire
}
enum class ProbeStage(val wire: String) {
    RESOLVED_DIRECT("resolved-direct"), SEGMENTS_FETCHED("segments-fetched"), FAILED("failed");
    override fun toString() = wire
}
enum class PlaybackStatus(val wire: String) {
    NOT_CONFIRMED("not-confirmed"), CONFIRMED_BY_USER("confirmed-by-user"), FAILED_IN_PLAYER("failed-in-player");
    override fun toString() = wire
}
data class SegmentResult(val bytes: Int, val format: String, val sha256: String)
data class StreamTestResult(
    val id: String, val title: String, val stage: ProbeStage, val segments: List<SegmentResult> = emptyList(),
    val error: String = "", val playback: PlaybackStatus = PlaybackStatus.NOT_CONFIRMED, val playbackCheckedAt: Long = 0,
    val mediaUrl: String = "",
)
data class ListingTestResult(val id: String, val title: String, val choices: List<StreamTestResult>, val error: String = "")
data class ProbeCandidate(val id: String, val title: String, val league: String, val channel: Boolean)
data class ProviderTestReport(
    val providerId: String, val providerName: String, val checkedAt: Long, val phase: ProbePhase,
    val catalogCount: Int = 0, val activeDomain: String = "", val warning: String = "",
    val listings: List<ListingTestResult> = emptyList(), val error: String = "",
    val upcomingOnly: Boolean = false,
)

data class ProviderTestBatch(
    val running: Boolean = false, val activeProviderId: String = "",
    val completed: Int = 0, val total: Int = 0,
)
