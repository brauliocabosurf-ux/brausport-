package com.brau.sportsplus.core.playlist

import com.brau.sportsplus.core.Limits
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

/**
 * Lector de XSPF con un parser XML real (XmlPullParser; en Android, `Xml.newPullParser()`).
 *
 * Seguridad:
 * - Cualquier DTD (`<!DOCTYPE`) o declaración de entidad (`<!ENTITY`) hace que el documento se
 *   RECHACE antes de parsear. Así no hay entidades externas ni expansión de entidades, sin depender
 *   de qué opciones soporte el parser de cada versión de Android.
 * - Solo se aceptan las entidades XML normales (&amp; &lt; &gt; &quot; &apos; y numéricas).
 * - Límites de profundidad, número de elementos y longitud de texto.
 * - Se procesan namespaces: el elemento raíz debe ser `playlist` en el namespace XSPF (o sin namespace).
 * - Las extensiones de VLC (`vlc:option`, etc.) se ignoran.
 */
class XspfParser(private val newParser: () -> XmlPullParser) {

    fun parse(text: String): ParsedPlaylist {
        if (DTD.containsMatchIn(text)) throw PlaylistException(LoadError.DTD_NOT_ALLOWED)
        return try {
            parseSafely(text)
        } catch (e: XmlPullParserException) {
            throw PlaylistException(LoadError.BAD_FORMAT)
        } catch (e: IOException) {
            if (e is PlaylistException) throw e
            throw PlaylistException(LoadError.BAD_FORMAT)
        } catch (e: RuntimeException) {
            // Algunos parsers lanzan excepciones no comprobadas con XML roto.
            throw PlaylistException(LoadError.BAD_FORMAT)
        }
    }

    private fun parseSafely(text: String): ParsedPlaylist {
        val p = newParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(text))

        val entries = ArrayList<Entry>()
        val path = ArrayList<String>()
        var elements = 0
        var invalid = 0
        var truncated = false
        var unnamed = 0
        var title: String? = null
        var location: String? = null
        var field: String? = null
        val sb = StringBuilder()

        loop@ while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    elements++
                    if (p.depth > Limits.XML_MAX_DEPTH || elements > Limits.XML_MAX_ELEMENTS) bad()
                    val ns = p.namespace ?: ""
                    val name = if (ns == XSPF_NS || ns.isEmpty()) p.name else "{other}"
                    if (p.depth == 1 && name != "playlist") bad()
                    path += name
                    when {
                        isAt(path, "playlist", "trackList", "track") -> {
                            title = null
                            location = null
                        }
                        path.size == 4 && path[2] == "track" && (name == "title" || name == "location") -> {
                            field = name
                            sb.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (field != null) {
                    val t = p.text ?: ""
                    if (sb.length + t.length > Limits.XML_MAX_TEXT_CHARS) bad()
                    sb.append(t)
                }
                XmlPullParser.END_TAG -> {
                    if (field != null && path.size == 4) {
                        if (field == "title") title = cleanText(sb.toString()) else location = sb.toString().trim()
                        field = null
                    }
                    if (isAt(path, "playlist", "trackList", "track")) {
                        val loc = location
                        if (loc == null || loc.length > Limits.URL_MAX_CHARS || !isHttp(loc)) {
                            invalid++
                        } else {
                            val t = title?.takeIf { it.isNotEmpty() }
                            entries += if (t != null) Entry(t, loc, null) else Entry(neutralName(++unnamed), loc, null, generatedName = true)
                            if (entries.size >= Limits.PLAYLIST_MAX_ENTRIES) {
                                truncated = true
                                break@loop
                            }
                        }
                    }
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                }
                XmlPullParser.END_DOCUMENT -> break@loop
            }
        }
        return ParsedPlaylist(entries.distinctBy { it.url }, 0, invalid, truncated)
    }

    private fun isAt(path: List<String>, vararg expected: String) =
        path.size == expected.size && path.indices.all { path[it] == expected[it] }

    private fun isHttp(s: String) =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    private fun bad(): Nothing = throw PlaylistException(LoadError.BAD_FORMAT)

    companion object {
        const val XSPF_NS = "http://xspf.org/ns/0/"
        private val DTD = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
    }
}
