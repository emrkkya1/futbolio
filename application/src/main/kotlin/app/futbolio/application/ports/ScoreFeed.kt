package app.futbolio.application.ports

import app.futbolio.domain.match.League
import app.futbolio.domain.match.ScoreFixture
import java.time.LocalDate

fun interface ScoreFeed {
    suspend fun fixtures(league: League, date: LocalDate, observedAt: Long): List<ScoreFixture>
}
