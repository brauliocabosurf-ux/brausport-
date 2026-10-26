package com.brau.sportsplus.core.net

import com.brau.sportsplus.core.Limits
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Motivo de rechazo. Nunca incluye la URL: se puede mostrar o registrar sin filtrar tokens. */
enum class UrlRejection {
    TOO_LONG,
    BAD_CHARACTERS,
    MALFORMED,
    SCHEME_NOT_ALLOWED,
    USERINFO,
    NO_HOST,
    BAD_PORT,
    BAD_HOST,
    LOCAL_NAME,
    BLOCKED_ADDRESS,
    CLEARTEXT_NOT_ALLOWED,
    DOWNGRADE,
    TOO_MANY_REDIRECTS,
}

sealed interface UrlCheck {
    /** [url] es exactamente la cadena recibida: los tokens de ruta o query se conservan. */
    data class Allowed(val url: String, val uri: URI, val host: String, val ipLiteral: InetAddress?) : UrlCheck

    data class Rejected(val reason: UrlRejection) : UrlCheck
}

/**
 * Política ÚNICA para toda URL que la app vaya a pedir: listas, canales, manifiestos HLS,
 * segmentos, claves, subtítulos y cada salto de redirección.
 *
 * Esta clase valida la SINTAXIS y los literales de IP. Los nombres de dominio se validan después,
 * contra las direcciones realmente resueltas ([ResolvingGuard]) y contra la dirección a la que se
 * abre el socket ([GuardedSocketFactory]).
 */
class UrlPolicy(
    cleartextHosts: Collection<String>,
    private val addressPolicy: AddressPolicy = PublicAddressPolicy,
) {
    /** Hosts autorizados para HTTP sin cifrar (deben coincidir con network_security_config.xml). */
    val cleartextHosts: Set<String> = cleartextHosts.map { it.trim().lowercase(Locale.ROOT).trimEnd('.') }
        .filter { it.isNotEmpty() }.toSet()

    fun check(raw: String): UrlCheck {
        if (raw.length > Limits.URL_MAX_CHARS) return reject(UrlRejection.TOO_LONG)
        // Solo ASCII imprimible: fuera espacios, caracteres de control y Unicode (homógrafos, IDN crudo).
        if (raw.isEmpty() || raw.any { it.code < 0x21 || it.code > 0x7E }) return reject(UrlRejection.BAD_CHARACTERS)

        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return reject(UrlRejection.MALFORMED)
        }
        if (!uri.isAbsolute || uri.isOpaque) return reject(UrlRejection.MALFORMED)

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return reject(UrlRejection.SCHEME_NOT_ALLOWED)

        // usuario:contraseña@host. java.net.URI puede degradar a "registry authority" (host = null)
        // con autoridades raras, así que también se revisa la autoridad cruda.
        val rawAuthority = uri.rawAuthority ?: return reject(UrlRejection.NO_HOST)
        if (uri.rawUserInfo != null || rawAuthority.contains('@')) return reject(UrlRejection.USERINFO)
        val rawHost = uri.host ?: return reject(UrlRejection.NO_HOST)

        if (uri.port != -1 && uri.port !in 1..65535) return reject(UrlRejection.BAD_PORT)
        if (uri.port == -1 && rawAuthority.endsWith(":")) return reject(UrlRejection.BAD_PORT)

        val parsedHost = parseHost(rawHost) ?: return reject(UrlRejection.BAD_HOST)
        val (host, ip) = parsedHost

        if (ip == null && isLocalName(host)) return reject(UrlRejection.LOCAL_NAME)
        if (ip != null && !addressPolicy.isAllowed(ip)) return reject(UrlRejection.BLOCKED_ADDRESS)
        if (scheme == "http" && host !in cleartextHosts) return reject(UrlRejection.CLEARTEXT_NOT_ALLOWED)

        return UrlCheck.Allowed(raw, uri, host, ip)
    }

    private fun reject(reason: UrlRejection) = UrlCheck.Rejected(reason)

    /** Devuelve (host normalizado, IP si es literal) o null si el host no es válido. */
    private fun parseHost(rawHost: String): Pair<String, InetAddress?>? {
        val lower = rawHost.lowercase(Locale.ROOT)
        if (lower.startsWith("[")) {
            if (!lower.endsWith("]")) return null
            val literal = lower.substring(1, lower.length - 1)
            // Sin zona (%eth0) y solo caracteres de IPv6: así getByName nunca consulta DNS.
            if (!literal.contains(':') || !literal.all { it in IPV6_CHARS }) return null
            val ip = try {
                InetAddress.getByName(literal)
            } catch (e: Exception) {
                return null
            }
            return literal to ip
        }

        val host = lower.trimEnd('.')
        if (host.isEmpty() || host.length > 253) return null

        DOTTED_QUAD.matchEntire(host)?.let { m ->
            val parts = m.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return null
            val bytes = ByteArray(4) { parts[it].toByte() }
            return host to InetAddress.getByAddress(bytes)
        }
        // Formas numéricas ambiguas que algunos resolvedores convierten en IP
        // (2130706433, 127.1, 0x7f.0.0.1, 0177.0.0.1): se rechazan.
        if (host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.any { HEX_LABEL.matches(it) }) return null
        if (labels.any { !LABEL.matches(it) }) return null
        return host to null
    }

    private fun isLocalName(host: String): Boolean =
        !host.contains('.') ||
            LOCAL_SUFFIXES.any { host == it.removePrefix(".") || host.endsWith(it) }

    companion object {
        private const val IPV6_CHARS = "0123456789abcdef:."
        private val DOTTED_QUAD = Regex("""(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})""")
        private val HEX_LABEL = Regex("""0x[0-9a-f]*""")
        private val LABEL = Regex("""[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?""")
        private val LOCAL_SUFFIXES = listOf(
            ".localhost", ".local", ".localdomain", ".internal", ".lan", ".home", ".home.arpa", ".intranet", ".corp",
        )

        /**
         * Versión segura para mostrar o registrar: solo esquema y host.
         * Nunca incluye ruta, query, credenciales ni tokens.
         */
        fun redact(raw: String?): String {
            if (raw == null) return "[sin URL]"
            return try {
                val uri = URI(raw)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val host = uri.host
                if (scheme == null || host == null || uri.rawUserInfo != null) "[URL oculta]" else "$scheme://$host/…"
            } catch (e: Exception) {
                "[URL oculta]"
            }
        }
    }
}
