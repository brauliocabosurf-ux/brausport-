package com.brau.sportsplus.core.playlist

import com.brau.sportsplus.core.Limits

/**
 * Lector de M3U. Trata la lista como DATOS:
 * - Solo usa #EXTINF (nombre y group-title) y #EXTGRP.
 * - Ignora cualquier otra directiva (#EXTVLCOPT, #KODIPROP, cabeceras HTTP, user-agent, referer…):
 *   la lista no puede cambiar cómo se conecta la app.
 * - Aplica los límites de [Limits]. Las URLs largas se rechazan, nunca se truncan.
 * - La validación completa de cada URL (destino, HTTP permitido, etc.) la hace [Catalog].
 */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]{1,64})="([^"]{0,1024})"""")

    fun parse(text: String): ParsedPlaylist {
        val entries = ArrayList<Entry>()
        var skipped = 0
        var invalid = 0
        var truncated = false
        var unnamed = 0
        var name: String? = null
        var group: String? = null

        for (raw in text.lineSequence()) {
            if (raw.length > Limits.M3U_LINE_MAX_CHARS) {
                // Línea anómala: se descarta junto con la entrada que se estaba armando.
                skipped++
                name = null
                group = null
                continue
            }
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = titleComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    name = if (comma >= 0) cleanText(line.substring(comma + 1)) else null
                    val attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    group = attrs["group-title"]?.let { cleanText(it) }?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    cleanText(line.substringAfter(":")).takeIf { it.isNotEmpty() }?.let { group = it }
                }
                line.startsWith("#") -> Unit // directiva ignorada a propósito
                else -> {
                    if (line.length > Limits.URL_MAX_CHARS || !looksLikeHttpUrl(line)) {
                        invalid++
                    } else {
                        val title = name?.takeIf { it.isNotEmpty() }
                        entries += if (title != null) {
                            Entry(title, line, group)
                        } else {
                            Entry(neutralName(++unnamed), line, group, generatedName = true)
                        }
                        if (entries.size >= Limits.PLAYLIST_MAX_ENTRIES) {
                            truncated = true
                            break
                        }
                    }
                    name = null
                    group = null
                }
            }
        }
        return ParsedPlaylist(entries.distinctBy { it.url }, skipped, invalid, truncated)
    }

    private fun looksLikeHttpUrl(s: String) =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** La coma que separa atributos del nombre va después de la última comilla. */
    private fun titleComma(line: String): Int {
        val lastQuote = line.lastIndexOf('"')
        return line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
    }
}
