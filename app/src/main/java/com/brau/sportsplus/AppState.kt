package com.brau.sportsplus

import android.content.Context
import android.util.Xml
import com.brau.sportsplus.core.load.PlaylistRepository
import com.brau.sportsplus.core.playlist.LoadError
import com.brau.sportsplus.core.playlist.PlaylistDecoder
import com.brau.sportsplus.core.storage.FilePlaylistStore
import com.brau.sportsplus.net.NetInfo
import com.brau.sportsplus.net.PlaylistFetcher
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Acceso único al repositorio de la lista. La lista vigente vive en [PlaylistRepository.committed]
 * (la misma pareja URL/contenido que está en disco). Si Android mata el proceso, el reproductor
 * la recupera del disco con [PlaylistRepository.loadStored].
 */
object AppState {
    @Volatile
    private var repository: PlaylistRepository? = null

    fun repository(context: Context): PlaylistRepository = repository ?: synchronized(this) {
        repository ?: create(context.applicationContext).also { repository = it }
    }

    private fun create(app: Context): PlaylistRepository {
        // Archivos de versiones anteriores (sin URL ligada ni verificación): se descartan una vez.
        File(app.filesDir, "playlist.cache").delete()
        File(app.filesDir, "playlist").deleteRecursively()
        val decoder = PlaylistDecoder(NetInfo.policy) { Xml.newPullParser() }
        val fetcher = PlaylistFetcher(NetInfo.client, NetInfo.policy)
        return PlaylistRepository(
            fetch = fetcher::fetch,
            decode = decoder::decode,
            storage = FilePlaylistStore(File(app.filesDir, "store")),
            io = Dispatchers.IO,
            cpu = Dispatchers.Default,
        )
    }
}

/** Textos fijos para cada error: nunca se muestra exception.message, URLs ni tokens. */
fun LoadError.toMessage(context: Context): String = context.getString(
    when (this) {
        LoadError.NETWORK -> R.string.err_network
        LoadError.TIMEOUT -> R.string.err_timeout
        LoadError.BLOCKED_URL -> R.string.err_blocked
        LoadError.CLEARTEXT_NOT_ALLOWED -> R.string.err_cleartext
        LoadError.HTTP_STATUS -> R.string.err_http
        LoadError.TOO_LARGE -> R.string.err_too_large
        LoadError.BAD_FORMAT -> R.string.err_format
        LoadError.DTD_NOT_ALLOWED -> R.string.err_dtd
        LoadError.EMPTY -> R.string.err_empty
        LoadError.NO_ALLOWED_SOURCES -> R.string.err_no_sources
        LoadError.STORAGE -> R.string.err_storage
    },
)
