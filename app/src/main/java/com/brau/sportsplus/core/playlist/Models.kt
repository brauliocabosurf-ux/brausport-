package com.brau.sportsplus.core.playlist

import com.brau.sportsplus.core.Limits
import java.io.IOException

/**
 * Una línea de la lista tal como viene (ya saneada). Los logos de la lista se ignoran a propósito.
 * [generatedName]: la lista no traía título y se usó un nombre neutral («Canal N»). Esos canales
 * nunca se fusionan entre sí ni con otro canal llamado igual. La URL jamás se usa como nombre.
 */
data class Entry(val name: String, val url: String, val group: String?, val generatedName: Boolean = false)

/** Nombre neutral para entradas sin título (nunca derivado de la URL, que puede llevar tokens). */
fun neutralName(n: Int) = "Canal $n"

/** Un enlace para ver un canal o partido. */
data class Source(val url: String, val label: String)

/** Lo que ve el usuario: un canal o partido con todas sus fuentes juntas. */
data class Channel(
    val key: String,
    val name: String,
    val section: String,
    val sport: String?,
    val isEvent: Boolean,
    /** Nombre del recurso local del logo (sin descargas), o null para usar iniciales. */
    val logoName: String?,
    val sources: List<Source>,
) {
    val subtitle: String
        get() = when {
            isEvent -> sources.joinToString(" · ") { it.label }
            sources.size > 1 -> "${sources.size} fuentes"
            else -> ""
        }
}

data class ParsedPlaylist(
    val entries: List<Entry>,
    val skippedLines: Int,
    val invalidUrls: Int,
    val truncated: Boolean,
)

data class CatalogResult(
    val channels: List<Channel>,
    /** Fuentes descartadas por la política de red (HTTP no autorizado, IP privada, etc.). */
    val blockedSources: Int,
    /** Fuentes descartadas por pasar del máximo por canal. */
    val droppedSources: Int,
    val truncated: Boolean,
)

/** Errores controlados. La interfaz los traduce a textos fijos; nunca se muestra exception.message. */
enum class LoadError {
    NETWORK,
    TIMEOUT,
    BLOCKED_URL,
    CLEARTEXT_NOT_ALLOWED,
    HTTP_STATUS,
    TOO_LARGE,
    BAD_FORMAT,
    DTD_NOT_ALLOWED,
    EMPTY,
    NO_ALLOWED_SOURCES,
    /** No se pudo guardar en disco (lleno, sin permisos…): se conserva la versión anterior. */
    STORAGE,
}

class PlaylistException(val error: LoadError) : IOException("Error de lista: $error")

/** Quita caracteres de control, compacta espacios y limita la longitud sin partir pares sustitutos. */
fun cleanText(raw: String, max: Int = Limits.NAME_MAX_CHARS): String {
    val compact = raw.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
    if (compact.length <= max) return compact
    var cut = compact.take(max)
    if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut
}
