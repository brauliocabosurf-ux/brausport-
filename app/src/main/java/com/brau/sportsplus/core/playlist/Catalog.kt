package com.brau.sportsplus.core.playlist

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.net.UrlCheck
import com.brau.sportsplus.core.net.UrlPolicy
import java.text.Normalizer
import java.util.Locale

/**
 * Junta duplicados, clasifica por nombre y aplica la política de red a CADA fuente.
 * Una fuente rechazada (HTTP no autorizado, IP privada, credenciales en la URL…) se descarta y se
 * cuenta en [CatalogResult.blockedSources]; nunca llega al reproductor.
 * Para ajustar la clasificación solo hay que tocar las listas de palabras.
 */
object Catalog {
    const val LIVE = "🔴 En vivo hoy"
    const val OTHER = "Otros"

    val SECTION_ORDER = listOf("Deportes MX", "Deportes Latam", "USA en español", "Brasil", "TV abierta", "Cine y series", OTHER)
    val SPORT_ORDER = listOf("Liga MX", "Europa", "Sudamérica", "NFL", "NBA", "F1", "Más fútbol")

    // ---- Eventos ----
    private val NFL_TEAMS = listOf(
        "ravens", "bengals", "vikings", "colts", "cardinals", "broncos", "raiders", "rams", "lions", "packers",
        "seahawks", "chiefs", "patriots", "cowboys", "eagles", "giants", "jets", "steelers", "browns", "bills",
        "dolphins", "texans", "titans", "jaguars", "chargers", "49ers", "bears", "saints", "falcons", "panthers",
        "buccaneers", "commanders", "nfl",
    )
    private val SUDAMERICA = listOf(
        "zapping", "win sports", "l1 max", "tigo sports", "tnt sports chile", "espn premium", "movistar deportes",
        "boca", "river plate", "racing", "independiente", "velez", "estudiantes", "colo-colo", "alianza", "nacional",
        "cali", "medellin", "tolima", "pasto", "cerro porteno", "emelec", "aucas", "barracas", "central cordoba",
        "aldosivi", "belgrano", "palestino", "catolica", "concepcion", "limache", "cerro largo", "boston river",
        "sport boys", "cajamarca", "garcilaso", "chankas", "moquegua", "inter bogota", "guayaquil",
    )
    private val LIGA_MX = listOf(
        "america", "pachuca", "atlas", "puebla", "queretaro", "cruz azul", "tijuana", "santos laguna", "chivas",
        "guadalajara", "monterrey", "tigres", "toluca", "pumas", "leon", "necaxa", "mazatlan", "juarez",
        "san luis", "morelia", "tapatio", "tlaxcala", "correcaminos", "tudn mx", "canal 5 mx", "fox one",
    )
    private val EUROPA = listOf(
        "laliga", "dazn", "barcelona", "real madrid", "milan", "inter", "juventus", "napoli", "roma", "udinese",
        "real sociedad", "levante", "sevilla", "atletico madrid", "betis", "villarreal", "sporting cp", "benfica",
        "porto", "az", "twente", "ajax", "psv", "manchester", "liverpool", "chelsea", "arsenal", "tottenham",
        "bayern", "dortmund", "psg", "castellon", "sporting gijon",
    )

    // ---- Canales fijos ----
    private val CINE = listOf("warner", "space", "cinemax", "cinecanal", "star channel", "hbo")
    private val BRASIL = listOf("premiere", "sportv")
    private val USA = listOf("fox deportes", "espn deportes", "bein", "fox sports 1")
    private val MX = listOf("tudn", "azteca", "tvc deportes")
    private val ABIERTA = listOf("canal 5", "telefe", "el trece", "telemundo", "univision", "unimas", "universo", "america tv", "canal 9")
    private val LATAM = listOf("espn", "fox sports", "dsports", "directv", "tyc", "win sports", "liga 1", "liga1", "movistar", "ligapro", "sports")

    // Expresiones precompiladas una sola vez (antes se creaba una por palabra en cada canal).
    private val RX_F1 = words(listOf("f1", "formula 1"))
    private val RX_NBA = words(listOf("nba"))
    private val RX_NFL = words(NFL_TEAMS)
    private val RX_SUDAMERICA = words(SUDAMERICA)
    private val RX_LIGA_MX = words(LIGA_MX)
    private val RX_EUROPA = words(EUROPA)
    private val RX_TNT = words(listOf("tnt"))
    private val RX_CINE = words(CINE)
    private val RX_BRASIL = words(BRASIL)
    private val RX_USA = words(USA)
    private val RX_MX = words(MX)
    private val RX_ABIERTA = words(ABIERTA)
    private val RX_MN = Regex("\\p{Mn}+")
    private val RX_NOT_ALNUM = Regex("[^a-z0-9]")

    /** Coincidencia por palabra completa (para que "az" no pesque "azteca"). */
    private fun words(list: List<String>) =
        Regex("(^|[^a-z0-9])(" + list.joinToString("|") { Regex.escape(it) } + ")($|[^a-z0-9])")

    fun norm(s: String): String =
        RX_MN.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    fun keyOf(s: String) = RX_NOT_ALNUM.replace(norm(s), "")

    private fun isEvent(name: String): Boolean {
        val n = norm(name)
        return n.contains(" vs ") || n.startsWith("gp ")
    }

    private class Builder(val name: String, val isEvent: Boolean, val group: String?) {
        val sources = mutableListOf<Source>()
    }

    fun build(parsed: ParsedPlaylist, policy: UrlPolicy): CatalogResult {
        val builders = LinkedHashMap<String, Builder>()
        var blocked = 0
        var dropped = 0
        for (e in parsed.entries) {
            if (policy.check(e.url) !is UrlCheck.Allowed) {
                blocked++
                continue
            }
            val event = !e.generatedName && isEvent(e.name)
            val cut = e.name.lastIndexOf(" - ")
            val title = if (event && cut > 0) e.name.substring(0, cut).trim() else e.name.trim()
            if (title.isEmpty()) continue
            // Nombres generados: clave propia por posición, nunca se fusionan por el texto «Canal N».
            val key = when {
                e.generatedName -> "gen:" + keyOf(title)
                event -> "ev:" + keyOf(title)
                else -> "ch:" + keyOf(title)
            }
            val b = builders.getOrPut(key) { Builder(title, event, e.group) }
            if (b.sources.size >= Limits.SOURCES_PER_CHANNEL) {
                dropped++
                continue
            }
            val label = if (event && cut > 0) cleanText(e.name.substring(cut + 3)) else "Fuente ${b.sources.size + 1}"
            b.sources += Source(e.url, label)
        }

        val channels = builders.map { (key, b) ->
            if (b.isEvent) {
                val text = norm(b.name + " " + b.sources.joinToString(" ") { it.label })
                Channel(key, b.name, LIVE, sportOf(text), true, null, b.sources)
            } else {
                var section = sectionOf(norm(b.name))
                if (section == OTHER && !b.group.isNullOrBlank()) section = b.group
                val logo = if (key.startsWith("gen:")) null else LogoNames.forKey(keyOf(b.name))
                Channel(key, b.name, section, null, false, logo, b.sources)
            }
        }
        // Eventos primero; luego canales fijos por sección, respetando el orden de la lista.
        val sectionRank = { c: Channel ->
            if (c.isEvent) -1 else SECTION_ORDER.indexOf(c.section).let { if (it < 0) SECTION_ORDER.size else it }
        }
        return CatalogResult(channels.sortedBy(sectionRank), blocked, dropped, parsed.truncated)
    }

    private fun sportOf(t: String): String = when {
        t.startsWith("gp ") || RX_F1.containsMatchIn(t) -> "F1"
        RX_NBA.containsMatchIn(t) -> "NBA"
        RX_NFL.containsMatchIn(t) -> "NFL"
        t.contains("academy") || t.contains("apple tv") -> "Más fútbol"
        RX_SUDAMERICA.containsMatchIn(t) -> "Sudamérica"
        RX_LIGA_MX.containsMatchIn(t) -> "Liga MX"
        RX_EUROPA.containsMatchIn(t) -> "Europa"
        else -> "Más fútbol"
    }

    private fun sectionOf(n: String): String = when {
        (RX_TNT.containsMatchIn(n) && !n.contains("sports")) || RX_CINE.containsMatchIn(n) -> "Cine y series"
        RX_BRASIL.containsMatchIn(n) -> "Brasil"
        RX_USA.containsMatchIn(n) -> "USA en español"
        RX_MX.containsMatchIn(n) || n.endsWith(" mx") -> "Deportes MX"
        RX_ABIERTA.containsMatchIn(n) -> "TV abierta"
        LATAM.any { n.contains(it) } -> "Deportes Latam"
        else -> OTHER
    }
}
