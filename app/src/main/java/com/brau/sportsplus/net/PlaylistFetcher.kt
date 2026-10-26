package com.brau.sportsplus.net

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.io.LimitExceededException
import com.brau.sportsplus.core.io.readAllLimited
import com.brau.sportsplus.core.net.BlockedConnectionException
import com.brau.sportsplus.core.net.BlockedHostException
import com.brau.sportsplus.core.net.PolicyViolationException
import com.brau.sportsplus.core.net.UrlCheck
import com.brau.sportsplus.core.net.UrlPolicy
import com.brau.sportsplus.core.net.UrlRejection
import com.brau.sportsplus.core.playlist.LoadError
import com.brau.sportsplus.core.playlist.PlaylistException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * Descarga una lista por el cliente seguro.
 * - Cancelar la coroutine cancela la petición de OkHttp (también durante la lectura del cuerpo).
 * - Tiempo total por llamada, además de los timeouts del cliente.
 * - Cuenta bytes descomprimidos (OkHttp descomprime gzip de forma transparente).
 * - Los errores se convierten en [LoadError] fijos: nunca se propaga un mensaje con la URL.
 */
class PlaylistFetcher(
    private val client: OkHttpClient,
    private val policy: UrlPolicy,
    private val totalTimeoutMs: Long = Limits.PLAYLIST_TOTAL_TIMEOUT_MS,
) {
    suspend fun fetch(url: String): ByteArray {
        when (val check = policy.check(url)) {
            is UrlCheck.Rejected -> throw PlaylistException(mapRejection(check.reason))
            is UrlCheck.Allowed -> Unit
        }
        val request = Request.Builder().url(url).header("Accept", "*/*").get().build()
        val call = client.newCall(request)
        call.timeout().timeout(totalTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            call.downloadLimited()
        } catch (e: PlaylistException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // si fue por cancelación, se propaga como tal
            throw PlaylistException(mapIo(e))
        }
    }

    /**
     * Conexión y lectura del cuerpo ocurren dentro del callback de OkHttp. `invokeOnCancellation`
     * se dispara en cuanto la coroutine empieza a cancelarse, y `call.cancel()` aborta tanto la
     * conexión como una lectura en curso: cancelar nunca deja una descarga viva en segundo plano.
     */
    private suspend fun Call.downloadLimited(): ByteArray = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { r ->
                        if (!r.isSuccessful) throw PlaylistException(LoadError.HTTP_STATUS)
                        r.body.byteStream().readAllLimited(Limits.PLAYLIST_MAX_BYTES)
                    }
                }
                if (cont.isActive) cont.resumeWith(result)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    private fun mapIo(e: IOException): LoadError = when (e) {
        is LimitExceededException -> LoadError.TOO_LARGE
        is PolicyViolationException -> mapRejection(e.reason)
        is BlockedHostException, is BlockedConnectionException -> LoadError.BLOCKED_URL
        is InterruptedIOException -> LoadError.TIMEOUT
        else -> LoadError.NETWORK
    }

    private fun mapRejection(reason: UrlRejection): LoadError =
        if (reason == UrlRejection.CLEARTEXT_NOT_ALLOWED) LoadError.CLEARTEXT_NOT_ALLOWED else LoadError.BLOCKED_URL
}
