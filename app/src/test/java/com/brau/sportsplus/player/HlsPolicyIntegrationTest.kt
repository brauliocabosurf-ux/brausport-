package com.brau.sportsplus.player

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brau.sportsplus.core.net.AddressPolicy
import com.brau.sportsplus.core.net.UrlPolicy
import com.brau.sportsplus.core.net.UrlRejection
import com.brau.sportsplus.net.SafeHttp
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Integración REAL con Media3 (ExoPlayer + HLS) bajo Robolectric: el canal es válido, pero sus
 * referencias INTERNAS (manifiesto secundario, segmento, clave AES) apuntan a destinos o esquemas
 * prohibidos. La política debe bloquearlas al abrirse, y el reproductor debe terminar en error.
 *
 * Requiere Android/Robolectric: se ejecuta en CI (./gradlew testDebugUnitTest). No se pudo ejecutar
 * en el entorno donde se escribió (sin Android SDK ni repositorio Maven de Google).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HlsPolicyIntegrationTest {
    private lateinit var server: HttpServer
    private val files = mutableMapOf<String, String>()
    private val served = Collections.synchronizedList(mutableListOf<String>())
    private val blocked = Collections.synchronizedList(mutableListOf<Pair<Int, UrlRejection>>())
    private val loopbackOnly = AddressPolicy { it.isLoopbackAddress }
    private val policy = UrlPolicy(listOf("127.0.0.1"), loopbackOnly)

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") { ex ->
            served += ex.requestURI.path
            val body = files[ex.requestURI.path]?.toByteArray()
            if (body == null) ex.sendResponseHeaders(404, -1) else {
                ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    private fun base() = "http://127.0.0.1:${server.address.port}"

    // Robolectric mantiene detenido el reloj de Android. Media3 necesita un reloj que avance
    // para procesar los mensajes de reproducción y reintento, igual que sus propias pruebas.
    private fun newPlayer() = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
        .setClock(FakeClock(true))
        .setUsePlatformDiagnostics(false)
        .build()

    private fun awaitError(player: ExoPlayer) {
        try {
            TestPlayerRunHelper.advance(player).withTimeoutMs(30_000).untilPlayerError()
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("HLS no terminó: state=${player.playbackState}, served=$served, blocked=$blocked", e)
        }
    }

    private fun playUntilError(url: String) {
        val sources = SafeMediaSources(SafeHttp.buildClient(policy, loopbackOnly), policy) { type, reason -> blocked += type to reason }
        val player = newPlayer()
        try {
            player.setMediaSource(sources.create(url, asHls = true)!!)
            player.prepare()
            player.play()
            awaitError(player)
        } finally {
            player.release()
        }
    }

    private fun media(lines: String) = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n$lines\n#EXT-X-ENDLIST\n"

    @Test fun secondaryManifestOnPrivateNetworkIsBlocked() {
        files["/master.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS=\"avc1.42e01e,mp4a.40.2\"\nhttp://10.0.0.1/variante.m3u8\n"
        playUntilError("${base()}/master.m3u8")
        assertTrue("$blocked", C.DATA_TYPE_MANIFEST to UrlRejection.BLOCKED_ADDRESS in blocked)
    }

    @Test fun segmentOnPrivateNetworkIsBlocked() {
        files["/media.m3u8"] = media("#EXTINF:10.0,\nhttps://192.168.1.5/seg0.ts")
        playUntilError("${base()}/media.m3u8")
        assertTrue("$blocked", C.DATA_TYPE_MEDIA to UrlRejection.BLOCKED_ADDRESS in blocked)
    }

    @Test fun segmentWithLocalSchemeIsBlocked() {
        files["/media.m3u8"] = media("#EXTINF:10.0,\ncontent://com.android.providers.media/x")
        playUntilError("${base()}/media.m3u8")
        assertTrue("$blocked", blocked.any { it.first == C.DATA_TYPE_MEDIA && it.second == UrlRejection.SCHEME_NOT_ALLOWED })
    }

    @Test fun encryptionKeyFromFileIsBlocked() {
        files["/media.m3u8"] = media(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"file:///data/local/tmp/clave.key\",IV=0x00000000000000000000000000000001\n" +
                "#EXTINF:10.0,\nseg0.ts",
        )
        playUntilError("${base()}/media.m3u8")
        assertTrue("$blocked", C.DATA_TYPE_DRM to UrlRejection.SCHEME_NOT_ALLOWED in blocked)
        assertTrue("El segmento cifrado no debe pedirse sin clave", "/seg0.ts" !in served)
    }

    /**
     * Manifiesto inicial PERMITIDO (laboratorio en 127.0.0.1) con clave AES en un destino PROHIBIDO
     * (127.0.0.2, donde escucha un segundo servidor que registra si alguien llega). La excepción del
     * laboratorio vive solo aquí: la política permite exactamente 127.0.0.1; la de producción no cambia.
     */
    @Test fun encryptionKeyOnForbiddenDestinationIsBlockedWhenOpeningTheKey() {
        val forbidden = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0), 0)
        val forbiddenHits = Collections.synchronizedList(mutableListOf<String>())
        forbidden.createContext("/") { ex ->
            forbiddenHits += ex.requestURI.path
            ex.sendResponseHeaders(200, 16)
            ex.responseBody.use { it.write(ByteArray(16)) }
            ex.close()
        }
        forbidden.start()
        try {
            val lab = InetAddress.getByName("127.0.0.1")
            val onlyLab = AddressPolicy { it == lab }
            val labPolicy = UrlPolicy(listOf("127.0.0.1"), onlyLab)
            files["/media.m3u8"] = media(
                "#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.2:${forbidden.address.port}/clave.key\"," +
                    "IV=0x00000000000000000000000000000001\n#EXTINF:10.0,\nseg0.ts",
            )
            val sources = SafeMediaSources(SafeHttp.buildClient(labPolicy, onlyLab), labPolicy) { type, reason ->
                blocked += type to reason
            }
            val player = newPlayer()
            try {
                // La URL inicial SÍ es válida: el bloqueo debe ocurrir dentro, al abrir la clave.
                player.setMediaSource(sources.create("${base()}/media.m3u8", asHls = true)!!)
                player.prepare()
                player.play()
                awaitError(player)
            } finally {
                player.release()
            }
            assertTrue("Se pidió el manifiesto permitido", "/media.m3u8" in served)
            assertTrue("$blocked", C.DATA_TYPE_DRM to UrlRejection.BLOCKED_ADDRESS in blocked)
            assertTrue("Ninguna petición debe llegar al destino prohibido: $forbiddenHits", forbiddenHits.isEmpty())
            assertTrue("El segmento cifrado no debe descargarse sin clave", "/seg0.ts" !in served)
        } finally {
            forbidden.stop(0)
        }
    }
}
