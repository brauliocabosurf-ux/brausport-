package com.brau.sportsplus

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias de la app (las mismas claves de la versión anterior: no se pierden favoritos).
 * SharedPreferences no cifra nada; aquí solo hay la URL de la lista, favoritos y el último canal.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("brau_sports", Context.MODE_PRIVATE)

    /**
     * URL que guardaba la versión 1.0 (antes de que URL y lista vivieran juntas en un solo archivo).
     * Solo se usa una vez para migrar: la URL vigente ahora es la de la lista confirmada.
     */
    val legacyPlaylistUrl: String?
        get() = prefs.getString(KEY_URL, null)

    fun clearLegacyPlaylistUrl() {
        prefs.edit().remove(KEY_URL).apply()
    }

    /** Se guarda la clave del canal (no la URL), porque las fuentes cambian. */
    var lastChannelKey: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    fun favorites(): Set<String> = prefs.getStringSet(KEY_FAVS, emptySet())?.toSet() ?: emptySet()

    /** Devuelve true si quedó agregado, false si se quitó. */
    fun toggleFavorite(key: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (key in set) {
            set.remove(key); false
        } else {
            set.add(key); true
        }
        prefs.edit().putStringSet(KEY_FAVS, set).apply()
        return added
    }

    private companion object {
        const val KEY_URL = "playlist_url"
        const val KEY_LAST = "last_channel_key"
        const val KEY_FAVS = "favorite_keys"
    }
}
