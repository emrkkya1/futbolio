package app.futbolio.domain.match

import app.futbolio.domain.text.TextPolicy
import app.futbolio.domain.identity.StableIdentity

data class League(val id: String, val name: String, val aliases: List<String>, val tournament: Boolean = false)

object Leagues {
    val known = listOf(
        League("tur.1", "Süper Lig", listOf("super lig", "turkish super lig", "trendyol super lig", "superlig", "turkiye super lig", "turkey super lig")),
        League("tur.2", "TFF 1. Lig", listOf("tff 1 lig", "trendyol 1 lig", "1 lig", "turkey 1 lig", "turkish 1 lig", "turkiye 1 lig", "turkish first league")),
        League("tur.cup", "Turkish Cup", listOf("turkiye kupasi", "ziraat turkiye kupasi", "turkish cup"), true),
        League("eng.fa", "FA Cup", listOf("english fa cup", "england fa cup", "fa cup"), true),
        League("eng.league_cup", "League Cup", listOf("efl cup", "carabao cup", "english league cup", "england league cup", "league cup"), true),
        League("eng.1", "Premier League", listOf("premier league", "english premier league", "england premier league", "epl", "ingiltere premier lig")),
        League("esp.1", "LaLiga", listOf("laliga", "la liga", "spanish la liga", "liga de espana", "ispanya la liga")),
        League("ita.1", "Serie A", listOf("serie a", "italian serie a", "italy serie a", "italya serie a", "italya seri a")),
        League("ger.1", "Bundesliga", listOf("bundesliga", "german bundesliga", "germany bundesliga", "almanya bundesliga", "almanya bundesliga 1")),
        League("fra.1", "Ligue 1", listOf("ligue 1", "french ligue 1", "france ligue 1", "fransa lig 1", "fransa ligue 1")),
        League("uefa.champions", "UEFA Champions League", listOf("champions league", "uefa champions league", "sampiyonlar ligi", "uefa sampiyonlar ligi", "uefa sampiyonlar ligi lig asamasi"), true),
        League("uefa.europa", "UEFA Europa League", listOf("europa league", "uefa europa league", "avrupa ligi", "uefa avrupa ligi", "uefa avrupa ligi lig asamasi"), true),
        League("uefa.europa.conf", "UEFA Conference League", listOf("conference league", "konferans ligi", "uefa konferans ligi", "uefa conference league", "uefa europa conference league"), true),
        League("fifa.world", "FIFA World Cup", listOf("world cup", "dunya kupasi"), true),
        League("uefa.euro", "UEFA European Championship", listOf("euro", "european championship", "uefa euro", "avrupa sampiyonasi"), true),
        League("uefa.euroq", "UEFA European Championship Qualifying", listOf("euro qualifying", "european championship qualifying"), true),
        League("uefa.nations", "UEFA Nations League", listOf("nations league", "uluslar ligi"), true),
        League("conmebol.america", "Copa América", listOf("copa america"), true),
        League("caf.nations", "Africa Cup of Nations", listOf("afcon", "africa cup of nations"), true),
        League("afc.asian.cup", "AFC Asian Cup", listOf("asian cup"), true),
        League("concacaf.gold", "CONCACAF Gold Cup", listOf("gold cup"), true),
        League("mex.1", "Liga MX", listOf("liga mx", "mexican liga bbva mx")),
        League("usa.1", "MLS", listOf("mls", "major league soccer")),
        League("arg.1", "Argentine Primera División", listOf("liga profesional argentina", "liga profesional", "argentine primera division", "argentina primera division")),
        League("bra.1", "Brasileirão Série A", listOf("brasileirao", "brazilian serie a", "brasileirao serie a")),
        League("bra.copa_do_brazil", "Copa do Brasil", listOf("copa do brasil")),
        League("conmebol.libertadores", "Copa Libertadores", listOf("copa libertadores", "conmebol libertadores")),
        League("conmebol.sudamericana", "Copa Sudamericana", listOf("copa sudamericana", "conmebol sudamericana")),
        League("por.1", "Primeira Liga", listOf("primeira liga", "liga portugal", "portuguese primeira liga")),
        League("ned.1", "Eredivisie", listOf("eredivisie", "dutch eredivisie")),
        League("sco.1", "Scottish Premiership", listOf("scottish premiership", "scottish premier league")),
        League("col.1", "Colombian Primera A", listOf("liga betplay", "colombian primera a")),
        League("per.1", "Peruvian Liga 1", listOf("liga 1", "peruvian liga 1")),
        League("uru.1", "Uruguayan Primera División", listOf("primera division de uruguay", "uruguayan primera division")),
    )

    private val curatedIds = setOf("tur.1", "tur.2", "tur.cup", "eng.1", "eng.fa", "eng.league_cup",
        "esp.1", "ita.1", "ger.1", "fra.1", "por.1", "ned.1", "usa.1", "uefa.champions", "uefa.europa",
        "uefa.europa.conf", "fifa.world", "uefa.euro", "uefa.euroq", "uefa.nations", "conmebol.america",
        "caf.nations", "afc.asian.cup", "concacaf.gold")
    val curated = known.filter { it.id in curatedIds }
    fun allowed(league: League) = league.id in curatedIds

    private val punctuation = Regex("[^a-z0-9]+")
    private val seasonPrefix = Regex("^20\\d{2}(?:[-/]?(?:20)?\\d{2})?\\s*")
    fun key(value: String) = TextPolicy.normalize(value).replace(punctuation, " ").trim()
    private val byName = buildMap {
        known.forEach { league -> (listOf(league.name, league.id) + league.aliases).forEach { putIfAbsent(key(it), league) } }
    }

    fun find(name: String, aliases: Map<String, String> = emptyMap()): League {
        val corrected = aliases.entries.firstOrNull { key(it.key) == key(name) }?.value ?: name
        val normalized = key(corrected.replace(seasonPrefix, ""))
        return byName[normalized]
            ?: League("other-${StableIdentity.hash(key(corrected))}", corrected.ifBlank { "Football" }, emptyList())
    }
}
