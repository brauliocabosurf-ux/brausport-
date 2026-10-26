package com.brau.sportsplus.core

/**
 * Límites centralizados de la app. Todo el código lee estos valores; no hay números sueltos.
 *
 * Los límites de LISTAS (M3U/XSPF) son independientes de los de REPRODUCCIÓN: una transmisión en
 * vivo no tiene tamaño total, así que al video solo se le limita el buffer, los manifiestos HLS,
 * las claves de cifrado, los tiempos y los reintentos.
 */
object Limits {
    // ---- Listas ----
    /** Contenido de la lista YA descomprimido (se cuenta al leer, no se confía en Content-Length). */
    const val PLAYLIST_MAX_BYTES: Long = 2L * 1024 * 1024
    const val PLAYLIST_MAX_ENTRIES = 2_000
    const val NAME_MAX_CHARS = 256
    /** Una URL más larga se RECHAZA; nunca se trunca. */
    const val URL_MAX_CHARS = 4_096
    const val SOURCES_PER_CHANNEL = 8
    /** Una línea M3U más larga se descarta completa (y la entrada que estaba armando). */
    const val M3U_LINE_MAX_CHARS = 8_192

    // ---- XSPF (XML) ----
    const val XML_MAX_DEPTH = 16
    const val XML_MAX_ELEMENTS = 40_000
    const val XML_MAX_TEXT_CHARS = 8_192

    // ---- Red: descarga de listas ----
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val READ_TIMEOUT_MS = 15_000L
    const val WRITE_TIMEOUT_MS = 10_000L
    /** Tiempo máximo TOTAL de una descarga de lista (conexión + redirecciones + lectura). */
    const val PLAYLIST_TOTAL_TIMEOUT_MS = 45_000L
    const val MAX_REDIRECTS = 5

    // ---- Reproducción ----
    const val HLS_MANIFEST_MAX_BYTES: Long = 4L * 1024 * 1024
    const val HLS_KEY_MAX_BYTES: Long = 64L * 1024
    const val PLAYER_MIN_BUFFER_MS = 15_000
    const val PLAYER_MAX_BUFFER_MS = 50_000
    const val PLAYER_START_BUFFER_MS = 2_500
    const val PLAYER_REBUFFER_MS = 5_000
    const val PLAYER_TARGET_BUFFER_BYTES = 48 * 1024 * 1024
    /** Reintentos de Media3 por cada carga (segmento, manifiesto, clave). */
    const val PLAYER_LOAD_RETRIES = 3
    /** Si una fuente se queda cargando más de esto, se pasa a la siguiente. */
    const val STALL_TIMEOUT_MS = 20_000L
    const val BEHIND_LIVE_RETRIES = 3
    /** Intentos automáticos máximos por episodio de fallo (errores + congelamientos). */
    const val EPISODE_MAX_ATTEMPTS = 12
    /** Duración máxima de un episodio de fallo antes de rendirse y ofrecer reintento manual. */
    const val EPISODE_MAX_MS = 120_000L
    /** Reproducción continua, con avance real, necesaria para restablecer el presupuesto. */
    const val STABLE_PLAYBACK_MS = 30_000L
    /** Cada cuánto se comprueba el avance de la reproducción. */
    const val PROGRESS_CHECK_MS = 5_000L
}
