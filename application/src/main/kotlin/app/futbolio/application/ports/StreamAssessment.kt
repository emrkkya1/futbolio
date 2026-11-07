package app.futbolio.application.ports

/** Short transport snapshot, not a playback/decode guarantee. Never contains source URLs. */
data class StreamAssessment(
    val status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val resolution: String = "",
    val frameRate: Double? = null,
    val bitrate: Long? = null,
    val adaptive: Boolean = false,
    val headroom: Double? = null,
    val samples: Int = 0,
    val bitrateEstimated: Boolean = false,
    val headroomEstimated: Boolean = false,
    val codecs: String = "",
    val resolutionMeasured: Boolean = false,
    val checkedAtMillis: Long = 0,
)

/** Three measured quality grades; UNKNOWN/UNAVAILABLE are ungraded check states, never SLOW. */
enum class ConnectionStatus(val label: String, val rank: Int) {
    GOOD("GOOD", 0),
    MEDIUM("MEDIUM", 1),
    UNKNOWN("Not rated", 2),
    SLOW("SLOW", 3),
    UNAVAILABLE("Not rated · check failed", 4),
}

fun interface StreamAssessor {
    suspend fun assess(choice: StreamChoice): StreamAssessment

    suspend fun assess(choice: StreamChoice, update: (StreamAssessment) -> Unit): StreamAssessment =
        assess(choice).also(update)

    companion object {
        val None = StreamAssessor { StreamAssessment() }
    }
}
