package com.brau.sportsplus.net

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.net.AddressPolicy
import com.brau.sportsplus.core.net.GuardedSocketFactory
import com.brau.sportsplus.core.net.HttpIdentity
import com.brau.sportsplus.core.net.PolicyViolationException
import com.brau.sportsplus.core.net.RedirectPolicy
import com.brau.sportsplus.core.net.ResolvingGuard
import com.brau.sportsplus.core.net.UrlCheck
import com.brau.sportsplus.core.net.UrlPolicy
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * ÚNICO cliente HTTP de la app. Lo usan la descarga de listas y Media3 (manifiestos HLS,
 * segmentos, claves y subtítulos), así que todas las peticiones pasan por las mismas barreras:
 *
 *  1. [PolicyInterceptor] (interceptor de aplicación): valida la URL inicial y hace las
 *     redirecciones A MANO, validando cada `Location` (máx. 5 saltos, sin HTTPS → HTTP).
 *     El seguimiento automático de OkHttp está apagado, así que ningún salto se le escapa.
 *  2. [NetworkRecheckInterceptor] (interceptor de red): vuelve a validar la URL de la petición
 *     que realmente sale a la red y la IP de la conexión usada (incluye conexiones reutilizadas).
 *  3. [GuardedDns]: las respuestas DNS se validan y OkHttp conecta exactamente a esas IPs.
 *  4. [GuardedSocketFactory]: comprueba la IP real al abrir CADA socket. Cubre IPs literales
 *     (OkHttp no consulta el DNS para ellas), reintentos, Happy Eyeballs y DNS rebinding.
 *  0. [UserAgentInterceptor]: un único User-Agent para todo; la lista no aporta cabeceras.
 *  5. Sin proxy ([Proxy.NO_PROXY]): un proxy del sistema no puede saltarse las barreras.
 *
 * TLS: se usa la verificación por defecto de Android (certificados del sistema + hostname).
 * No hay TrustManager ni HostnameVerifier propios.
 * HTTP en claro: además de [UrlPolicy], Android aplica network_security_config.xml a OkHttp.
 */
object SafeHttp {

    fun buildClient(
        urlPolicy: UrlPolicy,
        addressPolicy: AddressPolicy,
        systemDns: Dns = Dns.SYSTEM,
    ): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(GuardedDns(ResolvingGuard(addressPolicy) { systemDns.lookup(it) }))
        .socketFactory(GuardedSocketFactory(addressPolicy))
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(UserAgentInterceptor(HttpIdentity.USER_AGENT))
        .addInterceptor(PolicyInterceptor(urlPolicy, RedirectPolicy(urlPolicy)))
        .addNetworkInterceptor(NetworkRecheckInterceptor(urlPolicy, addressPolicy))
        .connectTimeout(Limits.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Limits.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(Limits.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Sin callTimeout global: una transmisión en vivo progresiva puede durar horas.
        // La descarga de listas fija su propio tiempo total por llamada.
        .build()
}

/**
 * Fija el User-Agent de la app en TODAS las peticiones (listas, manifiestos, segmentos, claves),
 * reemplazando cualquier valor previo. Las listas nunca aportan cabeceras.
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

class GuardedDns(private val guard: ResolvingGuard) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = guard.lookup(hostname)
}

/** Interceptor de aplicación: valida la petición inicial y cada redirección. */
class PolicyInterceptor(
    private val urlPolicy: UrlPolicy,
    private val redirectPolicy: RedirectPolicy,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var current = urlPolicy.check(request.url.toString()).allowedOrThrow()
        var redirects = 0
        while (true) {
            val response = chain.proceed(request)
            if (!response.isRedirect) return response
            val location = response.header("Location")
            response.close()
            val next = redirectPolicy.next(current.uri, location, redirects).allowedOrThrow()
            redirects++
            val sameHost = next.host == current.host
            request = request.newBuilder()
                .url(next.url)
                .apply {
                    // Nunca reenviar credenciales a otro host (la app no envía ninguna, pero por si acaso).
                    if (!sameHost) {
                        removeHeader("Authorization")
                        removeHeader("Cookie")
                    }
                }
                .build()
            current = next
        }
    }
}

/** Interceptor de red: se ejecuta por cada petición que de verdad sale, en la conexión real. */
class NetworkRecheckInterceptor(
    private val urlPolicy: UrlPolicy,
    private val addressPolicy: AddressPolicy,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        urlPolicy.check(chain.request().url.toString()).allowedOrThrow()
        val remote = (chain.connection()?.route()?.socketAddress as? InetSocketAddress)?.address
        if (remote != null && !addressPolicy.isAllowed(remote)) {
            throw PolicyViolationException(com.brau.sportsplus.core.net.UrlRejection.BLOCKED_ADDRESS)
        }
        return chain.proceed(chain.request())
    }
}

private fun UrlCheck.allowedOrThrow(): UrlCheck.Allowed = when (this) {
    is UrlCheck.Allowed -> this
    is UrlCheck.Rejected -> throw PolicyViolationException(reason)
}
