package com.brau.sportsplus.core.load

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.playlist.CatalogResult
import com.brau.sportsplus.core.playlist.LoadError
import com.brau.sportsplus.core.playlist.PlaylistException
import com.brau.sportsplus.core.storage.PlaylistStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Un resultado que ya no corresponde a la última operación. Se trata como cancelación, no como error. */
class StaleResultException : CancellationException("Resultado obsoleto")

/** La lista vigente: misma pareja URL/contenido que está en disco. */
class Committed(val url: String, val result: CatalogResult)

/**
 * Fuente de verdad de la lista vigente.
 *
 * - [committed] es lo que hay en disco; la interfaz solo muestra esto. Así lo mostrado y lo
 *   guardado nunca divergen, aunque una operación se cancele o quede obsoleta.
 * - Generación: cada operación (arranque, cambio de URL, recarga) toma un turno con [begin]; solo
 *   el turno vigente puede publicar o guardar.
 * - Confirmación atómica: bajo un mutex se comprueba que el turno sigue vigente y, SIN poder
 *   cancelarse a la mitad, se escribe URL+contenido y se publica. O pasa todo, o no pasa nada.
 * - Si algo falla, se lanza [PlaylistException] y la versión vigente no cambia.
 */
class PlaylistRepository(
    private val fetch: suspend (String) -> ByteArray,
    private val decode: (ByteArray) -> CatalogResult,
    private val storage: PlaylistStorage,
    private val io: CoroutineDispatcher,
    private val cpu: CoroutineDispatcher,
    private val totalTimeoutMs: Long = Limits.PLAYLIST_TOTAL_TIMEOUT_MS,
) {
    private val generation = AtomicLong()
    private val lock = Mutex()
    private val state = MutableStateFlow<Committed?>(null)

    val committed: StateFlow<Committed?> = state.asStateFlow()

    /** Inicia una operación nueva; cualquier anterior queda obsoleta. */
    fun begin(): Long = generation.incrementAndGet()

    fun isCurrent(token: Long): Boolean = generation.get() == token

    /**
     * Lee la lista guardada. Con [token] (arranque) solo publica si la operación sigue vigente;
     * sin token (reproductor tras muerte del proceso) solo publica si aún no hay nada publicado.
     * Nunca reemplaza una lista más reciente.
     */
    suspend fun loadStored(token: Long? = null): Committed? {
        state.value?.let { return it }
        val stored = withContext(io) { storage.read() } ?: return null
        val result = try {
            withContext(cpu) { decode(stored.bytes) }
        } catch (e: PlaylistException) {
            // Limpieza CONDICIONAL y bajo el mismo mutex que las escrituras: solo se borra si el
            // archivo sigue siendo exactamente la versión que se leyó. Si mientras tanto se confirmó
            // otra lista, esta limpieza no la toca.
            lock.withLock { withContext(NonCancellable + io) { storage.clearIfVersion(stored.version) } }
            return null
        }
        return lock.withLock {
            if (token != null && !isCurrent(token)) throw StaleResultException()
            state.value ?: Committed(stored.url, result).also { state.value = it }
        }
    }

    /** Descarga, valida y confirma [url]. Devuelve lo confirmado o lanza sin cambiar nada. */
    suspend fun refresh(url: String, token: Long): Committed {
        val bytes = withTimeoutOrNull(totalTimeoutMs) { fetch(url) }
            ?: throw PlaylistException(LoadError.TIMEOUT)
        if (!isCurrent(token)) throw StaleResultException()
        val result = withContext(cpu) { decode(bytes) }
        return lock.withLock {
            if (!isCurrent(token)) throw StaleResultException()
            withContext(NonCancellable) {
                try {
                    withContext(io) { storage.write(url, bytes) } // atómico; si falla, no se publica
                } catch (e: IOException) {
                    throw PlaylistException(LoadError.STORAGE)
                }
                Committed(url, result).also { state.value = it }
            }
        }
    }
}
