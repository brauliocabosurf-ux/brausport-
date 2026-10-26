package com.brau.sportsplus.net

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.net.AddressPolicy
import com.brau.sportsplus.core.net.BlockedConnectionException
import com.brau.sportsplus.core.net.BlockedHostException
import com.brau.sportsplus.core.net.HttpIdentity
import com.brau.sportsplus.core.net.PolicyViolationException
import com.brau.sportsplus.core.net.PublicAddressPolicy
import com.brau.sportsplus.core.net.UrlPolicy
import com.brau.sportsplus.core.net.UrlRejection
import com.brau.sportsplus.core.playlist.LoadError
import com.brau.sportsplus.core.playlist.PlaylistException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Pruebas de la red REAL (OkHttp 5.5.1) contra servidores locales controlados (HTTP y HTTPS del
 * propio JDK). No tocan servidores IPTV ni Internet.
 *
 * Los servidores escuchan en 127.0.0.1, que la política real bloquea; por eso, salvo en las
 * pruebas que verifican justamente ese bloqueo, se usa una política que SOLO permite loopback.
 */
class SafeHttpTest {
    private class Recorded(val path: String, val userAgent: String?)

    private lateinit var http: HttpServer
    private val requests = ConcurrentLinkedQueue<Recorded>()
    private val handlers = ConcurrentLinkedQueue<(HttpExchange) -> Unit>()
    private val loopbackOnly = AddressPolicy { it.isLoopbackAddress }

    @Before fun setUp() {
        http = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        http.createContext("/") { ex -> serve(ex) }
        http.start()
    }

    @After fun tearDown() = http.stop(0)

    private fun serve(ex: HttpExchange) {
        requests += Recorded(ex.requestURI.toString(), ex.requestHeaders.getFirst("User-Agent"))
        val h = handlers.poll() ?: { e: HttpExchange -> respond(e, 404, "no hay respuesta preparada") }
        try {
            h(ex)
        } catch (ignored: IOException) {
        } finally {
            ex.close()
        }
    }

    private fun enqueue(h: (HttpExchange) -> Unit) { handlers += h }

    private fun respond(ex: HttpExchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private fun redirect(location: String, code: Int = 302) = { ex: HttpExchange -> respond(ex, code, "", mapOf("Location" to location)) }
    private fun ok(body: String) = { ex: HttpExchange -> respond(ex, 200, body) }

    private fun base() = "http://127.0.0.1:${http.address.port}"
    private fun urlPolicy(address: AddressPolicy = loopbackOnly, hosts: List<String> = listOf("127.0.0.1")) = UrlPolicy(hosts, address)

    private fun client(address: AddressPolicy = loopbackOnly, dns: Dns = Dns.SYSTEM, policy: UrlPolicy = urlPolicy(address)) =
        SafeHttp.buildClient(policy, address, dns)

    private fun get(url: String, c: OkHttpClient = client()): String =
        c.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    private fun expectRejection(reason: UrlRejection, block: () -> Unit) {
        try {
            block()
            fail("Se esperaba $reason")
        } catch (e: PolicyViolationException) {
            assertEquals(reason, e.reason)
        }
    }

    // ---------------- Redirecciones ----------------

    @Test fun allowedRedirectIsFollowedManually() {
        enqueue(redirect("/destino"))
        enqueue(ok("ok"))
        assertEquals("ok", get("${base()}/inicio"))
        assertEquals(listOf("/inicio", "/destino"), requests.map { it.path })
    }

    @Test fun redirectToPrivateNetworkIsBlockedBeforeConnecting() {
        enqueue(redirect("http://10.0.0.1/admin"))
        expectRejection(UrlRejection.BLOCKED_ADDRESS) { get("${base()}/a") }
        assertEquals(1, requests.size)
    }

    @Test fun redirectToForbiddenSchemeIsBlocked() {
        enqueue(redirect("file:///data/data/x", 301))
        expectRejection(UrlRejection.SCHEME_NOT_ALLOWED) { get("${base()}/a") }
    }

    @Test fun redirectLoopStopsAtLimit() {
        repeat(Limits.MAX_REDIRECTS + 3) { enqueue(redirect("/otra")) }
        expectRejection(UrlRejection.TOO_MANY_REDIRECTS) { get("${base()}/a") }
        assertEquals(Limits.MAX_REDIRECTS + 1, requests.size)
    }

    // ---------------- Socket y DNS ----------------

    @Test fun socketGuardBlocksLoopbackEvenIfUrlCheckPassed() {
        // La política de URL deja pasar 127.0.0.1, pero el socket aplica la política real.
        val c = SafeHttp.buildClient(urlPolicy(loopbackOnly), PublicAddressPolicy)
        enqueue(ok("no debería llegar"))
        try {
            get("${base()}/x", c)
            fail()
        } catch (e: IOException) {
            assertTrue("Debe ser un bloqueo de socket: $e", e is BlockedConnectionException || e.cause is BlockedConnectionException)
        }
        assertEquals(0, requests.size)
    }

    @Test fun dnsAnswerWithPrivateAddressIsBlocked() {
        val evilDns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val policy = UrlPolicy(listOf("rebind.example.com"), PublicAddressPolicy)
        val c = SafeHttp.buildClient(policy, PublicAddressPolicy, evilDns)
        try {
            get("http://rebind.example.com:${http.address.port}/", c)
            fail()
        } catch (e: BlockedHostException) {
        }
        assertEquals(0, requests.size)
    }

    @Test fun dnsRebindingBetweenConnectionsIsBlocked() {
        val calls = AtomicInteger()
        val dns = Dns {
            if (calls.getAndIncrement() == 0) listOf(InetAddress.getByName("127.0.0.1")) else listOf(InetAddress.getByName("10.1.2.3"))
        }
        val policy = UrlPolicy(listOf("rebind.example.com"), loopbackOnly)
        val c = SafeHttp.buildClient(policy, loopbackOnly, dns).newBuilder()
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS)) // fuerza conexión nueva
            .build()
        enqueue(ok("1"))
        assertEquals("1", get("http://rebind.example.com:${http.address.port}/", c))
        try {
            get("http://rebind.example.com:${http.address.port}/", c)
            fail()
        } catch (e: BlockedHostException) {
        }
        assertEquals(1, requests.size)
    }

    // ---------------- User-Agent ----------------

    @Test fun userAgentIsTheAppsOnEveryRequestIncludingRedirects() = runBlocking {
        enqueue(redirect("/lista.m3u"))
        enqueue(ok("#EXTM3U\n"))
        PlaylistFetcher(client(), urlPolicy()).fetch("${base()}/inicio")
        assertEquals(listOf(HttpIdentity.USER_AGENT, HttpIdentity.USER_AGENT), requests.map { it.userAgent })
    }

    @Test fun userAgentCannotBeReplacedByTheRequest() {
        enqueue(ok("x"))
        client().newCall(Request.Builder().url("${base()}/x").header("User-Agent", "otro").build()).execute().close()
        assertEquals(HttpIdentity.USER_AGENT, requests.single().userAgent)
    }

    // ---------------- Descarga de listas ----------------

    private fun fetcher(timeoutMs: Long = 10_000) = PlaylistFetcher(client(), urlPolicy(), timeoutMs)

    private fun expectLoadError(error: LoadError, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("Se esperaba $error")
        } catch (e: PlaylistException) {
            assertEquals(error, e.error)
        }
    }

    @Test fun compressedBombIsCutByDecompressedLimit() {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gz -> val z = ByteArray(1 shl 20); repeat(32) { gz.write(z) } }
        val gz = raw.toByteArray()
        enqueue { ex ->
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(200, gz.size.toLong())
            ex.responseBody.use { it.write(gz) }
        }
        expectLoadError(LoadError.TOO_LARGE) { fetcher().fetch("${base()}/lista.m3u") }
    }

    @Test fun slowServerHitsTotalTimeout() {
        enqueue { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> repeat(20) { out.write('#'.code); out.flush(); Thread.sleep(300) } }
        }
        expectLoadError(LoadError.TIMEOUT) { fetcher(timeoutMs = 1_500).fetch("${base()}/lista.m3u") }
    }

    @Test fun interruptedResponseIsNetworkError() {
        enqueue { ex ->
            ex.sendResponseHeaders(200, 100_000)
            ex.responseBody.write("#EXTM3U\n".toByteArray())
            ex.responseBody.flush()
            ex.close() // corta antes de los 100,000 bytes prometidos
        }
        expectLoadError(LoadError.NETWORK) { fetcher().fetch("${base()}/lista.m3u") }
    }

    @Test fun httpErrorStatusIsControlled() {
        enqueue { ex -> respond(ex, 500, "error con datos internos") }
        expectLoadError(LoadError.HTTP_STATUS) { fetcher().fetch("${base()}/lista.m3u") }
    }

    @Test fun cancellingCoroutineCancelsTheCall() = runBlocking {
        enqueue { ex -> Thread.sleep(30_000); respond(ex, 200, "#EXTM3U\n") }
        val started = System.nanoTime()
        val job = async { fetcher(timeoutMs = 60_000).fetch("${base()}/lista.m3u") }
        delay(300)
        job.cancel()
        try {
            job.await()
        } catch (e: CancellationException) {
        }
        assertTrue("La cancelación debe ser inmediata", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test fun cleartextToUnlistedHostIsRejectedBeforeNetwork() {
        val strict = urlPolicy(hosts = emptyList())
        val f = PlaylistFetcher(client(policy = strict), strict)
        expectLoadError(LoadError.CLEARTEXT_NOT_ALLOWED) { f.fetch("${base()}/lista.m3u") }
        assertEquals(0, requests.size)
    }

    // ---------------- TLS real: HTTPS → HTTP y verificación activa ----------------

    private fun tlsContexts(): Pair<SSLContext, X509TrustManager> {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/tls/test-server.p12")!!.use { ks.load(it, "test-only".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "test-only".toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        val server = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return server to tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun withHttps(block: (port: Int, trust: X509TrustManager) -> Unit) {
        val (serverCtx, trust) = tlsContexts()
        val https = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        https.httpsConfigurator = HttpsConfigurator(serverCtx)
        https.createContext("/") { ex -> serve(ex) }
        https.start()
        try {
            block(https.address.port, trust)
        } finally {
            https.stop(0)
        }
    }

    /** Cliente seguro que además confía en el certificado de PRUEBA (no se desactiva la verificación). */
    private fun tlsClient(trust: X509TrustManager): OkHttpClient {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return client().newBuilder().sslSocketFactory(ctx.socketFactory, trust).build()
    }

    @Test fun httpsToHttpDowngradeIsBlockedWithRealTls() = withHttps { port, trust ->
        enqueue(redirect("${base()}/inseguro")) // HTTPS → HTTP hacia un host que SÍ tiene HTTP permitido
        expectRejection(UrlRejection.DOWNGRADE) { get("https://127.0.0.1:$port/seguro", tlsClient(trust)) }
        assertEquals(listOf("/seguro"), requests.map { it.path })
    }

    @Test fun httpsWorksAndCertificateVerificationStaysOn() = withHttps { port, trust ->
        enqueue(ok("tls ok"))
        assertEquals("tls ok", get("https://127.0.0.1:$port/", tlsClient(trust)))
        // El cliente normal (CAs del sistema) rechaza el certificado de prueba: TLS NO está desactivado.
        try {
            get("https://127.0.0.1:$port/", client())
            fail("Debió rechazar un certificado no confiable")
        } catch (e: SSLHandshakeException) {
        }
    }
}
