package com.brau.sportsplus.core.net

import com.brau.sportsplus.core.Limits
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.SocketFactory

/** Identidad HTTP única de la app (listas y video). La lista NO puede cambiarla ni añadir cabeceras. */
object HttpIdentity {
    /** Algunos servidores IPTV solo responden a reproductores conocidos. Es solo un texto: no cambia la seguridad. */
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
}

/** Petición rechazada por la política. El mensaje es fijo: nunca lleva la URL ni tokens. */
class PolicyViolationException(val reason: UrlRejection) : IOException("Petición bloqueada por la política de red: $reason")

/** El DNS devolvió direcciones no públicas. Mensaje fijo, sin el nombre del host. */
class BlockedHostException : UnknownHostException("El nombre resolvió a una dirección no permitida")

/** Intento de abrir un socket hacia una dirección no permitida. */
class BlockedConnectionException : ConnectException("Conexión bloqueada por la política de red")

/**
 * Valida cada salto de redirección: máximo de saltos, destino permitido y sin degradar HTTPS → HTTP.
 */
class RedirectPolicy(
    private val urlPolicy: UrlPolicy,
    private val maxRedirects: Int = Limits.MAX_REDIRECTS,
) {
    fun next(from: URI, location: String?, redirectsSoFar: Int): UrlCheck {
        if (redirectsSoFar >= maxRedirects) return UrlCheck.Rejected(UrlRejection.TOO_MANY_REDIRECTS)
        if (location.isNullOrEmpty() || location.length > Limits.URL_MAX_CHARS) {
            return UrlCheck.Rejected(UrlRejection.MALFORMED)
        }
        if (location.any { it.code < 0x21 || it.code > 0x7E }) return UrlCheck.Rejected(UrlRejection.BAD_CHARACTERS)
        val resolved = try {
            from.resolve(URI(location)).toString()
        } catch (e: URISyntaxException) {
            return UrlCheck.Rejected(UrlRejection.MALFORMED)
        } catch (e: IllegalArgumentException) {
            return UrlCheck.Rejected(UrlRejection.MALFORMED)
        }
        val check = urlPolicy.check(resolved)
        if (check is UrlCheck.Allowed &&
            from.scheme.lowercase(Locale.ROOT) == "https" &&
            check.uri.scheme.lowercase(Locale.ROOT) == "http"
        ) {
            return UrlCheck.Rejected(UrlRejection.DOWNGRADE)
        }
        return check
    }
}

/**
 * Resolución DNS validada. OkHttp conecta EXACTAMENTE a las direcciones que devuelve este
 * método (no vuelve a resolver), así que lo validado aquí es lo que se usa.
 *
 * Si el DNS mezcla direcciones públicas y privadas se rechaza el nombre completo: así un
 * atacante no puede esconder una IP interna como "segunda opción".
 */
class ResolvingGuard(
    private val policy: AddressPolicy,
    private val resolver: (String) -> List<InetAddress>,
) {
    fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolver(hostname)
        if (addresses.isEmpty()) throw UnknownHostException("Sin direcciones")
        if (addresses.any { !policy.isAllowed(it) }) throw BlockedHostException()
        return addresses
    }
}

/**
 * Última barrera: valida la IP real en el momento de abrir CADA socket.
 *
 * Cubre lo que el DNS no ve: hosts que son IP literal (OkHttp no llama a Dns para ellos),
 * conexiones nuevas tras redirecciones, reintentos, Happy Eyeballs y cualquier ruta futura.
 * Como compara la dirección final del socket, un cambio de DNS (rebinding) entre la validación
 * y la conexión no puede llevar a una IP interna.
 */
class GuardedSocketFactory(private val policy: AddressPolicy) : SocketFactory() {
    override fun createSocket(): Socket = GuardedSocket(policy)

    override fun createSocket(host: String, port: Int): Socket =
        GuardedSocket(policy).also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        GuardedSocket(policy).also {
            it.bind(InetSocketAddress(localHost, localPort))
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        GuardedSocket(policy).also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        GuardedSocket(policy).also {
            it.bind(InetSocketAddress(localAddress, localPort))
            it.connect(InetSocketAddress(address, port))
        }
}

class GuardedSocket(private val policy: AddressPolicy) : Socket() {
    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val address = (endpoint as? InetSocketAddress)?.address
        if (address == null || !policy.isAllowed(address)) {
            try {
                close()
            } catch (ignored: IOException) {
            }
            throw BlockedConnectionException()
        }
        super.connect(endpoint, timeout)
    }
}
