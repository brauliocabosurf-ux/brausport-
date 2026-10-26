package com.brau.sportsplus.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.io.LimitExceededException
import com.brau.sportsplus.core.net.HttpIdentity
import com.brau.sportsplus.core.net.PolicyViolationException
import com.brau.sportsplus.core.net.UrlCheck
import com.brau.sportsplus.core.net.UrlPolicy
import com.brau.sportsplus.core.net.UrlRejection
import okhttp3.OkHttpClient

/**
 * Fuentes de Media3 restringidas a la política de red.
 *
 * - NO se usa DefaultDataSource (que abre file:, content:, asset:, data:, rawresource:, udp:, rtmp:).
 *   La única fuente es OkHttpDataSource sobre el cliente seguro, envuelta en [PolicyDataSource].
 * - HLS usa [SafeHlsDataSourceFactory]: Media3 pide una DataSource por tipo de dato
 *   (MANIFEST para listas maestras y de medios, MEDIA para segmentos y subtítulos, DRM para claves
 *   AES). Todas pasan por la misma validación, así que una referencia interna del manifiesto a un
 *   esquema o destino prohibido falla al abrirse, aunque la URL del canal fuera válida.
 * - Límites de bytes: manifiestos y claves sí; segmentos de video no (son flujos en vivo).
 */
@OptIn(UnstableApi::class)
class SafeMediaSources(
    client: OkHttpClient,
    private val policy: UrlPolicy,
    /** Opcional (diagnóstico y pruebas): se llama con el tipo de dato y el motivo de cada bloqueo. */
    private val onBlocked: ((dataType: Int, reason: UrlRejection) -> Unit)? = null,
) {

    private val http: DataSource.Factory = OkHttpDataSource.Factory(client).setUserAgent(HttpIdentity.USER_AGENT)
    private val loadErrors = DefaultLoadErrorHandlingPolicy(Limits.PLAYER_LOAD_RETRIES)

    private val hlsFactory = HlsMediaSource.Factory(SafeHlsDataSourceFactory(http, policy, onBlocked))
        .setLoadErrorHandlingPolicy(loadErrors)
    private val progressiveFactory = ProgressiveMediaSource.Factory(
        DataSource.Factory { PolicyDataSource(http.createDataSource(), policy, 0L, C.DATA_TYPE_MEDIA, onBlocked) },
    )
        .setLoadErrorHandlingPolicy(loadErrors)

    /** Devuelve null si la URL no cumple la política (el reproductor lo trata como fuente caída). */
    fun create(url: String, asHls: Boolean): MediaSource? {
        if (policy.check(url) !is UrlCheck.Allowed) return null
        val item = MediaItem.Builder().setUri(url).build()
        return if (asHls) hlsFactory.createMediaSource(item) else progressiveFactory.createMediaSource(item)
    }
}

@OptIn(UnstableApi::class)
class SafeHlsDataSourceFactory(
    private val http: DataSource.Factory,
    private val policy: UrlPolicy,
    private val onBlocked: ((dataType: Int, reason: UrlRejection) -> Unit)? = null,
) : HlsDataSourceFactory {
    override fun createDataSource(dataType: Int): DataSource {
        val limit = when (dataType) {
            C.DATA_TYPE_MANIFEST -> Limits.HLS_MANIFEST_MAX_BYTES
            C.DATA_TYPE_DRM -> Limits.HLS_KEY_MAX_BYTES
            else -> 0L // segmentos y subtítulos: sin límite de bytes (vivo); los acotan buffer y timeouts
        }
        return PolicyDataSource(http.createDataSource(), policy, limit, dataType, onBlocked)
    }
}

/**
 * Valida CADA apertura (incluye las que Media3 hace por su cuenta para referencias internas)
 * y, si [maxBytes] > 0, corta al superar el límite. Errores con mensaje fijo, sin la URL.
 */
@OptIn(UnstableApi::class)
class PolicyDataSource(
    private val upstream: DataSource,
    private val policy: UrlPolicy,
    private val maxBytes: Long,
    private val dataType: Int = C.DATA_TYPE_UNKNOWN,
    private val onBlocked: ((dataType: Int, reason: UrlRejection) -> Unit)? = null,
) : DataSource {
    private var bytesRead = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val check = policy.check(dataSpec.uri.toString())
        if (check is UrlCheck.Rejected) {
            onBlocked?.invoke(dataType, check.reason)
            throw PolicyViolationException(check.reason)
        }
        bytesRead = 0
        val length = upstream.open(dataSpec)
        if (maxBytes > 0 && length != C.LENGTH_UNSET.toLong() && length > maxBytes) {
            upstream.close()
            throw LimitExceededException()
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0 && maxBytes > 0) {
            bytesRead += n
            if (bytesRead > maxBytes) throw LimitExceededException()
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
