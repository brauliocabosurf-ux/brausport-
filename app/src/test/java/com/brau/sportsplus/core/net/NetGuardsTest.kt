package com.brau.sportsplus.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class NetGuardsTest {
    private val policy = UrlPolicy(cleartextHosts = listOf("93.184.216.34"))
    private val redirects = RedirectPolicy(policy, maxRedirects = 5)

    private fun reason(c: UrlCheck) = (c as? UrlCheck.Rejected)?.reason

    // ---------- Redirecciones ----------

    @Test fun redirectToPrivateDestinationIsRejected() {
        val from = URI("https://cdn.example.com/a.m3u8")
        assertEquals(UrlRejection.BLOCKED_ADDRESS, reason(redirects.next(from, "https://10.0.0.1/b", 0)))
        assertEquals(UrlRejection.BLOCKED_ADDRESS, reason(redirects.next(from, "https://[::1]/b", 0)))
        assertEquals(UrlRejection.LOCAL_NAME, reason(redirects.next(from, "https://localhost/b", 0)))
        assertEquals(UrlRejection.SCHEME_NOT_ALLOWED, reason(redirects.next(from, "file:///etc/hosts", 0)))
    }

    @Test fun httpsToHttpDowngradeIsRejectedEvenForCleartextHost() {
        val from = URI("https://cdn.example.com/a")
        assertEquals(UrlRejection.DOWNGRADE, reason(redirects.next(from, "http://93.184.216.34/b", 0)))
    }

    @Test fun httpToHttpsUpgradeAndRelativeLocationsAreAllowed() {
        val from = URI("http://93.184.216.34/live/x/index.m3u8")
        val up = redirects.next(from, "https://cdn.example.com/y", 0) as UrlCheck.Allowed
        assertEquals("https://cdn.example.com/y", up.url)
        val rel = redirects.next(from, "../otra/index.m3u8?t=1", 0) as UrlCheck.Allowed
        assertEquals("http://93.184.216.34/live/otra/index.m3u8?t=1", rel.url)
    }

    @Test fun redirectLimitIsEnforced() {
        val from = URI("https://cdn.example.com/a")
        assertTrue(redirects.next(from, "/b", 4) is UrlCheck.Allowed)
        assertEquals(UrlRejection.TOO_MANY_REDIRECTS, reason(redirects.next(from, "/b", 5)))
    }

    @Test fun missingOrBrokenLocation() {
        val from = URI("https://cdn.example.com/a")
        assertEquals(UrlRejection.MALFORMED, reason(redirects.next(from, null, 0)))
        assertEquals(UrlRejection.BAD_CHARACTERS, reason(redirects.next(from, "https://x.com/\r\nSet-Cookie: a", 0)))
    }

    // ---------- DNS ----------

    @Test fun dnsAnswersWithForbiddenAddressesAreRejected() {
        val guard = ResolvingGuard(PublicAddressPolicy) { listOf(InetAddress.getByName("127.0.0.1")) }
        expectBlocked { guard.lookup("evil.example.com") }
        val mixed = ResolvingGuard(PublicAddressPolicy) {
            listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.0.10"))
        }
        expectBlocked { mixed.lookup("mixed.example.com") }
        val v6 = ResolvingGuard(PublicAddressPolicy) { listOf(InetAddress.getByName("fd00::1")) }
        expectBlocked { v6.lookup("v6.example.com") }
    }

    @Test fun dnsRebindingIsCheckedOnEveryResolution() {
        // 1ª respuesta pública, 2ª apunta a loopback (rebinding): cada resolución se valida.
        val calls = AtomicInteger()
        val guard = ResolvingGuard(PublicAddressPolicy) {
            if (calls.getAndIncrement() == 0) listOf(InetAddress.getByName("93.184.216.34"))
            else listOf(InetAddress.getByName("127.0.0.1"))
        }
        assertEquals(1, guard.lookup("rebind.example.com").size)
        expectBlocked { guard.lookup("rebind.example.com") }
    }

    @Test fun emptyDnsAnswerFails() {
        val guard = ResolvingGuard(PublicAddressPolicy) { emptyList() }
        try {
            guard.lookup("x.example.com")
            fail()
        } catch (e: java.net.UnknownHostException) {
        }
    }

    // ---------- Socket: la barrera final ----------

    @Test fun socketToLoopbackIsBlockedAtConnectTime() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val socket = GuardedSocketFactory(PublicAddressPolicy).createSocket()
            try {
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort), 2000)
                fail("Debió bloquear loopback")
            } catch (e: BlockedConnectionException) {
                assertTrue(socket.isClosed)
            }
        }
    }

    @Test fun socketConvenienceOverloadsAreAlsoGuarded() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                GuardedSocketFactory(PublicAddressPolicy).createSocket("127.0.0.1", server.localPort)
                fail()
            } catch (e: BlockedConnectionException) {
            }
        }
    }

    @Test fun unresolvedEndpointIsBlocked() {
        val socket = GuardedSocketFactory { true }.createSocket()
        try {
            socket.connect(InetSocketAddress.createUnresolved("example.com", 443), 1000)
            fail()
        } catch (e: BlockedConnectionException) {
        }
    }

    @Test fun socketConnectsWhenPolicyAllows() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val permissive = AddressPolicy { it.isLoopbackAddress }
            GuardedSocketFactory(permissive).createSocket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort), 2000)
                assertTrue(s.isConnected)
            }
        }
    }

    private fun expectBlocked(block: () -> Unit) {
        try {
            block()
            fail("Debió bloquear")
        } catch (e: BlockedHostException) {
        }
    }
}
