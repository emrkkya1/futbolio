package app.futbolio.domain.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DisplayTime {
    fun kickoff(starts: Long, preferences: PresentationConfig): String = if (starts > 0)
        DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.UK).withZone(ZoneId.of(preferences.displayTimeZone))
            .format(Instant.ofEpochMilli(starts)) + " (${preferences.displayTimeZone})"
    else "Kickoff unconfirmed"
}
