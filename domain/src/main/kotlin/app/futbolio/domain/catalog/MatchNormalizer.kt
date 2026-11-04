package app.futbolio.domain.catalog

import app.futbolio.domain.catalog.CatalogEntry
import app.futbolio.domain.catalog.Listing
import app.futbolio.domain.match.*
import app.futbolio.domain.identity.StableIdentity
import app.futbolio.domain.settings.PresentationConfig
import java.time.*

/** Pure matching policy. Adapters supply plain text; all date-sensitive work receives now explicitly. */
object MatchNormalizer {
    private val builtInAliases = mapOf("man utd" to "Manchester United", "manchester utd" to "Manchester United", "man united" to "Manchester United", "man city" to "Manchester City", "psg" to "Paris Saint-Germain", "napoles" to "Napoli", "internazionale" to "Inter Milan", "inter" to "Inter Milan", "bayern munchen" to "Bayern Munich", "atletico de madrid" to "Atlético Madrid", "athletic bilbao" to "Athletic Club", "stuttgart" to "VfB Stuttgart", "galatasaray sk" to "Galatasaray", "fenerbahce sk" to "Fenerbahçe", "besiktas jk" to "Beşiktaş")
    private val liveSuffix = Regex("(?i)\\s*\\[(?:live|canli)\\]\\s*|\\s*\\((?:live|canli)\\)\\s*")
    private val versus = Regex("\\s+(?:vs\\.?|v\\.?|[-–—]|x)\\s+", RegexOption.IGNORE_CASE)
    private val atHome = Regex("\\s+at\\s+", RegexOption.IGNORE_CASE)
    private val clock = Regex("(?:^|\\s)([0-2]\\d:[0-5]\\d)(?:$|\\s)")
    private val clubDecorations = setOf("fc", "cf", "sk", "jk")
    private val regionalAliases = mapOf("psv" to "PSV Eindhoven", "brujas" to "Club Brugge", "club brujas" to "Club Brugge",
        "leipzig" to "RB Leipzig", "porto" to "FC Porto", "oporto" to "FC Porto", "slavia prag" to "Slavia Prague", "slavia praga" to "Slavia Prague")
    private val genericCompetitions = setOf("", "football", "soccer", "futbol", "futebol", "matches")

    fun teamName(name: String, config: PresentationConfig): String {
        val clean = name.replace(liveSuffix, " ").trim()
        return config.teamAliases.entries.firstOrNull { Leagues.key(it.key) == Leagues.key(clean) }?.value
            ?: builtInAliases[Leagues.key(clean)] ?: regionalAliases[Leagues.key(clean)] ?: clean
    }

    fun teamKey(name: String, config: PresentationConfig) = Leagues.key(teamName(name, config))
        .split(' ').filterNot { it in clubDecorations }.joinToString(" ")

    /** Only club-decoration differences; never fuzzy-match opponents or strip squad qualifiers. */
    fun compatibleTeam(a: String, b: String): Boolean = a == b ||
        a.removePrefix("vfb ") == b.removePrefix("vfb ")

    fun teams(title: String, config: PresentationConfig): Pair<Team, Team>? {
        val cleaned = title.substringAfterLast(": ")
        val at = atHome.split(cleaned, limit = 2)
        if (at.size == 2) return Team(teamName(at[1], config)) to Team(teamName(at[0], config))
        val parts = versus.split(cleaned, limit = 2)
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) Team(teamName(parts[0], config)) to Team(teamName(parts[1], config)) else null
    }

    fun kickoff(row: Listing, now: Long): Long {
        if (row.starts > 0) return row.starts
        val time = clock.find(row.timeLabel)?.groupValues?.get(1) ?: return 0
        return runCatching {
            val zone = ZoneId.of(row.source.timeZone)
            // Providers' time-only schedules describe their current local date, not the nearest
            // occurrence of a clock time (which could resurrect yesterday's finished match).
            Instant.ofEpochMilli(row.observedAt.takeIf { it > 0 } ?: now).atZone(zone).toLocalDate()
                .atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
        }.getOrDefault(0)
    }

    fun aggregate(rows: List<Listing>, fixtures: List<ScoreFixture>, config: PresentationConfig, now: Long): List<CatalogEntry> {
        data class FixtureKeys(val fixture: ScoreFixture, val home: Set<String>, val away: Set<String>)
        val fixtureKeys = fixtures.map { fixture ->
            FixtureKeys(fixture,
                (listOf(fixture.home.name) + fixture.home.aliases).map { teamKey(it, config) }.toSet(),
                (listOf(fixture.away.name) + fixture.away.aliases).map { teamKey(it, config) }.toSet())
        }
        data class Parsed(val row: Listing, val teams: Pair<Team, Team>?, val league: League, val start: Long, val fixture: ScoreFixture?,
            val homeKey: String, val awayKey: String, val titleKey: String, val rowId: String, val ambiguousFixture: Boolean)
        val teamKeys = mutableMapOf<String, String>()
        fun key(name: String) = teamKeys.getOrPut(name) { teamKey(name, config) }
        val initial = rows.map { row ->
            val teams = if (row.channel) null else teams(row.title, config)
            val league = Leagues.find(row.league, config.leagueAliases)
            val start = kickoff(row, now)
            val homeKey = teams?.first?.name?.let(::key).orEmpty()
            val awayKey = teams?.second?.name?.let(::key).orEmpty()
            val candidates = if (teams == null || start == 0L) emptyList() else fixtureKeys.filter { keys ->
                val fixture = keys.fixture
                homeKey in keys.home && awayKey in keys.away &&
                    kotlin.math.abs(fixture.starts - start) <= 12 * CatalogVisibility.HOUR
            }
            Parsed(row, teams, league, start, candidates.singleOrNull()?.fixture, homeKey, awayKey, Leagues.key(row.title), row.id, candidates.size > 1)
        }
        // Clock-only schedules can retain tonight's matches after local midnight. Re-anchor
        // only with independent, explicitly dated evidence for the same still-live fixture.
        // Without that evidence kickoff() keeps its conservative observation-date policy.
        val dated = initial.filter { !it.row.channel && it.row.starts > 0 && it.teams != null &&
            it.start <= now && now < it.start + (if (it.league.tournament) 3 else 2) * CatalogVisibility.HOUR }
            .flatMap { item -> listOf(item.homeKey to item, item.awayKey to item) }.groupBy({ it.first }, { it.second })
        val parsed = initial.map { item ->
            if (item.row.channel || item.row.starts > 0 || item.start <= now || item.teams == null) item else {
                val zone = ZoneId.of(item.row.source.timeZone)
                val previous = Instant.ofEpochMilli(item.start).atZone(zone).minusDays(1).toInstant().toEpochMilli()
                val anchors = (dated[item.homeKey].orEmpty() + dated[item.awayKey].orEmpty()).filter {
                    (it.league.id == item.league.id || Leagues.key(item.league.name) in genericCompetitions) &&
                        (it.start / 60_000 == previous / 60_000 || it.homeKey == item.homeKey && it.awayKey == item.awayKey &&
                            kotlin.math.abs(it.start - previous) <= 45 * 60_000)
                }.map { it.start }.distinct()
                anchors.singleOrNull()?.let { item.copy(start = previous) } ?: item
            }
        }
        fun sameTeamAndTime(a: Parsed, b: Parsed): Boolean = a.teams != null && b.teams != null &&
            a.start > 0 && b.start > 0 && a.start / 60_000 == b.start / 60_000 &&
            (a.homeKey == b.homeKey || a.homeKey == b.awayKey || a.awayKey == b.homeKey || a.awayKey == b.awayKey)
        val parents = IntArray(parsed.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parents[current] != current) { parents[current] = parents[parents[current]]; current = parents[current] }
            return current
        }
        val byTeam = mutableMapOf<String, MutableSet<Int>>()
        val byChannel = mutableMapOf<String, Int>()
        val byFixture = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
        val uncertainRows = mutableSetOf<String>()
        for ((index, item) in parsed.withIndex()) {
            val channelKey = "${item.titleKey}|${item.row.source.timeZone}"
            val candidates = if (item.row.channel) listOfNotNull(byChannel[channelKey]) else
                (byTeam[item.homeKey].orEmpty() + byTeam[item.awayKey].orEmpty() +
                    item.fixture?.let { byFixture[it.league.id to it.id].orEmpty() }.orEmpty()).toList()
            val matches = candidates.filter { candidate ->
                val first = parsed[candidate]
                when {
                    item.row.channel || first.row.channel -> item.row.channel && first.row.channel && item.titleKey == first.titleKey && item.row.source.timeZone == first.row.source.timeZone
                    sameTeamAndTime(item, first) -> true
                    item.fixture != null && first.fixture != null -> item.fixture.id == first.fixture.id && item.fixture.league.id == first.fixture.league.id
                    item.teams == null || first.teams == null || item.start == 0L || first.start == 0L -> item.rowId == first.rowId
                    else -> {
                        val close = kotlin.math.abs(item.start - first.start) <= 45 * 60_000
                        val sameDay = Instant.ofEpochMilli(item.start).atZone(ZoneOffset.UTC).toLocalDate() ==
                            Instant.ofEpochMilli(first.start).atZone(ZoneOffset.UTC).toLocalDate()
                        val exactPair = item.homeKey == first.homeKey && item.awayKey == first.awayKey
                        val compatiblePair = (item.homeKey == first.homeKey || item.awayKey == first.awayKey) &&
                            compatibleTeam(item.homeKey, first.homeKey) && compatibleTeam(item.awayKey, first.awayKey)
                        (exactPair && (close || sameDay && kotlin.math.abs(item.start - first.start) <= 12 * CatalogVisibility.HOUR)) ||
                            (close && compatiblePair)
                    }
                }
            }
            val strict = matches.filter { sameTeamAndTime(item, parsed[it]) }.map(::root).distinct()
            val roots = matches.map(::root).distinct()
            // The explicit team+time rule is transitive, including a listing bridging two groups.
            // Keep ambiguity handling for looser, conflicting schedule estimates only.
            val join = if (strict.isNotEmpty()) strict else roots.takeIf { it.size == 1 }.orEmpty()
            if (strict.isEmpty() && roots.size > 1) {
                uncertainRows += item.rowId
                parsed.indices.filter { root(it) in roots }.forEach { uncertainRows += parsed[it].rowId }
            }
            join.forEach { parents[root(it)] = index }
            item.fixture?.let { byFixture.getOrPut(it.league.id to it.id) { linkedSetOf() } += index }
            if (item.row.channel) byChannel[channelKey] = index else {
                if (item.homeKey.isNotEmpty()) byTeam.getOrPut(item.homeKey) { linkedSetOf() } += index
                if (item.awayKey.isNotEmpty()) byTeam.getOrPut(item.awayKey) { linkedSetOf() } += index
            }
        }
        return parsed.indices.groupBy(::root).values.map { indices ->
            val group = indices.map { parsed[it] }
            // Prefer an explicit timestamp over a clock-only listing; use the most-supported
            // timestamp, then the later one, as provisional metadata until a conflict is resolved.
            val first = group.sortedWith(compareByDescending<Parsed> { Leagues.allowed(it.league) }
                .thenByDescending { it.row.starts > 0 }
                .thenByDescending { candidate -> group.count { it.start == candidate.start } }.thenByDescending { it.start }
                .thenBy { it.row.source.id }).first()
            val matched = group.mapNotNull { it.fixture }.distinctBy { it.league.id to it.id }
            val fixture = matched.singleOrNull()
            val homeFallback = group.firstOrNull { it.row.homeLogo.isNotBlank() }?.row?.homeLogo.orEmpty()
            val awayFallback = group.firstOrNull { it.row.awayLogo.isNotBlank() }?.row?.awayLogo.orEmpty()
            val home = (fixture?.home ?: first.teams?.first)?.let { it.copy(logo = it.logo.ifBlank { homeFallback }) }
            val away = (fixture?.away ?: first.teams?.second)?.let { it.copy(logo = it.logo.ifBlank { awayFallback }) }
            val league = fixture?.league ?: first.league
            val start = fixture?.starts ?: first.start
            val title = if (home != null && away != null) "${home.name} vs ${away.name}" else first.row.title
            val identity = when {
                first.row.channel -> "channel|${Leagues.key(title)}|${first.row.source.timeZone}"
                home != null && away != null && start > 0 -> "match|${league.id}|${teamKey(home.name, config)}|${teamKey(away.name, config)}|${Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()}"
                else -> first.row.id
            }
            val knownLeagues = group.filter { Leagues.key(it.league.name) !in genericCompetitions }.map { it.league.id }.distinct()
            val conflictingTime = group.any { kotlin.math.abs(it.start - start) > 45 * 60_000 }
            val uncertain = group.any { it.rowId in uncertainRows || it.ambiguousFixture }
            val needsLookup = fixture == null && (knownLeagues.size > 1 || conflictingTime || matched.size > 1 || uncertain)
            CatalogEntry("fut:event:${StableIdentity.hash(identity)}", title, league, start, group.map { it.row }.distinctBy { it.id }, home, away, fixture,
                needsFixtureLookup = needsLookup, ambiguous = fixture == null && (knownLeagues.size > 1 || matched.size > 1 || uncertain))
        }.filter { entry ->
            !(config.hideFinished && entry.state(now) in listOf(MatchState.FINISHED, MatchState.CANCELLED)) &&
                (config.showHalftime || entry.state(now) != MatchState.HALFTIME)
        }
    }
}
