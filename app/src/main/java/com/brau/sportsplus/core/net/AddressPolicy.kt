package com.brau.sportsplus.core.net

import java.net.InetAddress

/** Decide si la app puede conectarse a una IP concreta. */
fun interface AddressPolicy {
    fun isAllowed(address: InetAddress): Boolean
}

/**
 * Solo direcciones públicas de Internet.
 *
 * Bloquea (IPv4): 0/8, 10/8, 100.64/10 (CGNAT), 127/8, 169.254/16, 172.16/12, 192.0.0/24,
 * 192.0.2/24, 192.31.196/24, 192.52.193/24, 192.88.99/24, 192.168/16, 192.175.48/24, 198.18/15,
 * 198.51.100/24, 203.0.113/24, 224/4 (multicast) y 240/4 (reservado + broadcast).
 *
 * IPv6: solo se permite unicast global 2000::/3, menos 2001::/23 (IETF, incluye Teredo),
 * 2001:db8::/32 y 3fff::/20 (documentación) y 2002::/16 (6to4). Con eso quedan fuera ::, ::1,
 * ::ffff:0:0/96 (se evalúa la IPv4 incrustada), 64:ff9b::/96, fc00::/7, fe80::/10, fec0::/10 y ff00::/8.
 */
object PublicAddressPolicy : AddressPolicy {

    private class Cidr(val bytes: ByteArray, val prefix: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != bytes.size) return false
            var bits = prefix
            var i = 0
            while (bits > 0) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((address[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }
    }

    private fun v4(a: Int, b: Int, c: Int, d: Int, prefix: Int) =
        Cidr(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()), prefix)

    private fun v6(vararg groups: Int, prefix: Int): Cidr {
        val out = ByteArray(16)
        groups.forEachIndexed { i, g ->
            out[i * 2] = (g shr 8).toByte()
            out[i * 2 + 1] = g.toByte()
        }
        return Cidr(out, prefix)
    }

    private val BLOCKED_V4 = listOf(
        v4(0, 0, 0, 0, 8), v4(10, 0, 0, 0, 8), v4(100, 64, 0, 0, 10), v4(127, 0, 0, 0, 8),
        v4(169, 254, 0, 0, 16), v4(172, 16, 0, 0, 12), v4(192, 0, 0, 0, 24), v4(192, 0, 2, 0, 24),
        v4(192, 31, 196, 0, 24), v4(192, 52, 193, 0, 24), v4(192, 88, 99, 0, 24), v4(192, 168, 0, 0, 16),
        v4(192, 175, 48, 0, 24), v4(198, 18, 0, 0, 15), v4(198, 51, 100, 0, 24), v4(203, 0, 113, 0, 24),
        v4(224, 0, 0, 0, 4), v4(240, 0, 0, 0, 4),
    )

    private val GLOBAL_V6 = v6(0x2000, prefix = 3)
    private val BLOCKED_V6 = listOf(
        v6(0x2001, 0x0000, prefix = 23),
        v6(0x2001, 0x0db8, prefix = 32),
        v6(0x2002, prefix = 16),
        v6(0x3fff, prefix = 20),
    )

    override fun isAllowed(address: InetAddress): Boolean = isPublic(address.address)

    /** Acepta los bytes crudos (4 = IPv4, 16 = IPv6). */
    fun isPublic(raw: ByteArray): Boolean = when (raw.size) {
        4 -> BLOCKED_V4.none { it.contains(raw) }
        16 -> isPublicV6(raw)
        else -> false
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        // IPv4 mapeada (::ffff:a.b.c.d): se decide por la IPv4 que lleva dentro.
        val mapped = (0..9).all { b[it].toInt() == 0 } &&
            (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        if (mapped) return isPublic(b.copyOfRange(12, 16))
        if (!GLOBAL_V6.contains(b)) return false
        return BLOCKED_V6.none { it.contains(b) }
    }
}
