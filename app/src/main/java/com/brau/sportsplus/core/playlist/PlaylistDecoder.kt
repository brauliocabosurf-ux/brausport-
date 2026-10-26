package com.brau.sportsplus.core.playlist

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.io.decodeUtf8
import com.brau.sportsplus.core.net.UrlPolicy
import org.xmlpull.v1.XmlPullParser

/** Bytes de la lista → catálogo listo para mostrar. Función pura: se ejecuta en Dispatchers.Default. */
class PlaylistDecoder(
    private val policy: UrlPolicy,
    private val newXmlParser: () -> XmlPullParser,
) {
    fun decode(bytes: ByteArray): CatalogResult {
        if (bytes.size > Limits.PLAYLIST_MAX_BYTES) throw PlaylistException(LoadError.TOO_LARGE)
        val text = decodeUtf8(bytes)
        val parsed = if (text.trimStart().startsWith("<")) XspfParser(newXmlParser).parse(text) else M3uParser.parse(text)
        if (parsed.entries.isEmpty()) throw PlaylistException(LoadError.EMPTY)
        val result = Catalog.build(parsed, policy)
        if (result.channels.isEmpty()) {
            throw PlaylistException(if (result.blockedSources > 0) LoadError.NO_ALLOWED_SOURCES else LoadError.EMPTY)
        }
        return result
    }
}

/** Filtros de la pantalla principal. Compartidos con el reproductor para reconstruir su lista. */
object ChannelFilters {
    const val FAVORITES = "★ Favoritos"
    const val ALL = "Todos"
    /** Las categorías de deporte dentro de "En vivo hoy" llevan este prefijo. */
    const val SPORT_PREFIX = "   · "

    fun apply(all: List<Channel>, category: String, query: String, favorites: Set<String>): List<Channel> {
        val q = Catalog.norm(query.trim())
        return when {
            q.isNotEmpty() -> all.filter { Catalog.norm(it.name).contains(q) || Catalog.norm(it.subtitle).contains(q) }
            category == FAVORITES -> all.filter { it.key in favorites }
            category == ALL -> all
            category == Catalog.LIVE -> all.filter { it.isEvent }
            category.startsWith(SPORT_PREFIX) -> all.filter { it.isEvent && it.sport == category.removePrefix(SPORT_PREFIX) }
            else -> all.filter { !it.isEvent && it.section == category }
        }
    }
}
