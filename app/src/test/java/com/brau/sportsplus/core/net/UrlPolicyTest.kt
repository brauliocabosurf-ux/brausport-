package com.brau.sportsplus.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UrlPolicyTest {
    private val policy = UrlPolicy(cleartextHosts = listOf("93.184.216.34", "tv.example.com"))

    private fun reason(url: String): UrlRejection? = (policy.check(url) as? UrlCheck.Rejected)?.reason

    private fun allowed(url: String) = policy.check(url) is UrlCheck.Allowed

    @Test fun httpsPublicHostIsAllowed() {
        assertTrue(allowed("https://cdn.example.com/live/canal.m3u8"))
        assertTrue(allowed("https://8.8.8.8/x"))
        assertTrue(allowed("https://[2606:4700::1111]/x"))
    }

    @Test fun tokensInPathAndQueryArePreservedVerbatim() {
        val url = "https://cdn.example.com/live/f6166d88-48df/0aec80eb00f85084/index.m3u8?token=abc%2Fdef&exp=1"
        val check = policy.check(url) as UrlCheck.Allowed
        assertEquals(url, check.url)
    }

    @Test fun malformedAndControlCharacters() {
        assertEquals(UrlRejection.BAD_CHARACTERS, reason("https://exa mple.com/"))
        assertEquals(UrlRejection.BAD_CHARACTERS, reason("https://example.com/\u0000x"))
        assertEquals(UrlRejection.BAD_CHARACTERS, reason("https://example.com/\r\nHost: evil"))
        assertEquals(UrlRejection.BAD_CHARACTERS, reason("https://exámple.com/"))
        assertEquals(UrlRejection.BAD_CHARACTERS, reason(""))
        assertEquals(UrlRejection.MALFORMED, reason("https://example.com/%zz"))
        assertEquals(UrlRejection.MALFORMED, reason("/relativa/sin/esquema"))
        assertEquals(UrlRejection.TOO_LONG, reason("https://example.com/" + "a".repeat(5000)))
    }

    @Test fun onlyHttpAndHttpsSchemes() {
        for (u in listOf(
            "file:///sdcard/x.m3u", "content://media/x", "asset:///x", "data:text/plain,hola",
            "ftp://example.com/x", "rtmp://example.com/x", "udp://239.0.0.1:1234", "javascript:alert(1)",
        )) {
            val r = reason(u)
            assertTrue("$u -> $r", r == UrlRejection.SCHEME_NOT_ALLOWED || r == UrlRejection.MALFORMED)
        }
    }

    @Test fun userinfoIsRejected() {
        assertEquals(UrlRejection.USERINFO, reason("https://user:pass@example.com/"))
        assertEquals(UrlRejection.USERINFO, reason("https://user@example.com/"))
        assertEquals(UrlRejection.USERINFO, reason("https://example.com@127.0.0.1/"))
    }

    @Test fun ipv4PrivateAndLoopbackLiterals() {
        for (h in listOf(
            "127.0.0.1", "127.1.2.3", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1",
        )) {
            assertEquals(h, UrlRejection.BLOCKED_ADDRESS, reason("https://$h/x"))
        }
        assertTrue(allowed("https://172.32.0.1/x"))
    }

    @Test fun ambiguousNumericHostsAreRejected() {
        // Todas se rechazan; el motivo depende de si java.net.URI ya las considera host inválido.
        for (h in listOf("2130706433", "127.1", "0x7f.0.0.1", "0177.0.0.1", "017700000001", "127.0.0.01")) {
            val r = reason("https://$h/x")
            assertTrue("$h -> $r", r == UrlRejection.BAD_HOST || r == UrlRejection.NO_HOST)
        }
    }

    @Test fun ipv6NonPublicLiterals() {
        for (h in listOf(
            "[::1]", "[::]", "[fe80::1]", "[fc00::1]", "[fd12:3456::1]", "[ff02::1]",
            "[::ffff:127.0.0.1]", "[::ffff:10.0.0.1]", "[::ffff:7f00:1]", "[64:ff9b::a00:1]",
            "[2001:db8::1]", "[2002:7f00:1::1]", "[2001::1]", "[::127.0.0.1]",
        )) {
            assertEquals(h, UrlRejection.BLOCKED_ADDRESS, reason("https://$h/x"))
        }
        val zone = reason("https://[fe80::1%25eth0]/x")
        assertTrue("$zone", zone == UrlRejection.BAD_HOST || zone == UrlRejection.MALFORMED)
        val notIp = reason("https://[abc]/x")
        assertTrue("$notIp", notIp == UrlRejection.BAD_HOST || notIp == UrlRejection.MALFORMED)
    }

    @Test fun localNamesAreRejected() {
        for (h in listOf("localhost", "LOCALHOST", "localhost.", "foo.localhost", "tv.local", "router", "nas.lan", "x.internal")) {
            assertEquals(h, UrlRejection.LOCAL_NAME, reason("https://$h/x"))
        }
    }

    @Test fun cleartextOnlyForConfiguredHosts() {
        assertTrue(allowed("http://93.184.216.34/playlist.m3u"))
        assertTrue(allowed("http://TV.Example.com./x"))
        assertEquals(UrlRejection.CLEARTEXT_NOT_ALLOWED, reason("http://93.184.216.35/x"))
        assertEquals(UrlRejection.CLEARTEXT_NOT_ALLOWED, reason("http://sub.tv.example.com/x"))
        assertEquals(UrlRejection.CLEARTEXT_NOT_ALLOWED, reason("http://cdn.example.com/x"))
    }

    @Test fun cleartextHostStillNeedsPublicAddress() {
        val p = UrlPolicy(listOf("127.0.0.1"))
        assertEquals(UrlRejection.BLOCKED_ADDRESS, (p.check("http://127.0.0.1/x") as UrlCheck.Rejected).reason)
    }

    @Test fun badPorts() {
        assertEquals(UrlRejection.BAD_PORT, reason("https://example.com:70000/x"))
        assertEquals(UrlRejection.BAD_PORT, reason("https://example.com:0/x"))
        assertTrue(allowed("https://example.com:8443/x"))
    }

    @Test fun redactNeverLeaksPathQueryOrCredentials() {
        val red = UrlPolicy.redact("http://93.184.216.34/live/f6166d88/secret-token/index.m3u8?k=1")
        assertEquals("http://93.184.216.34/…", red)
        assertFalse(UrlPolicy.redact("https://u:p@h.com/x").contains("p@"))
        assertEquals("[URL oculta]", UrlPolicy.redact("no es url %%"))
    }

    @Test fun publicAddressPolicyRawBytes() {
        assertTrue(PublicAddressPolicy.isAllowed(InetAddress.getByName("1.1.1.1")))
        assertFalse(PublicAddressPolicy.isAllowed(InetAddress.getByName("192.0.2.10")))
        // Bytes crudos de ::ffff:192.168.0.1 (Java ya lo convierte a IPv4; aquí se prueba la ruta IPv6).
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 192.toByte(); it[13] = 168.toByte(); it[15] = 1 }
        assertFalse(PublicAddressPolicy.isPublic(mapped))
        val mappedPublic = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 8; it[13] = 8; it[14] = 8; it[15] = 8 }
        assertTrue(PublicAddressPolicy.isPublic(mappedPublic))
    }

    @Test fun productionExceptionAllowsOnlyThatHostOverHttp() {
        // Misma lista que gradle.properties (brau.cleartextHosts=108.175.13.7).
        val prod = UrlPolicy(listOf("108.175.13.7"))
        assertTrue(prod.check("http://108.175.13.7/playlist.m3u") is UrlCheck.Allowed)
        assertTrue(prod.check("http://108.175.13.7/live/abc/def/index.m3u8") is UrlCheck.Allowed)
        assertTrue(prod.check("https://108.175.13.7/x") is UrlCheck.Allowed)
        for (u in listOf("http://108.175.13.70/x", "http://108.175.13.7.example.com/x", "http://otro.example.com/x", "http://8.8.8.8/x")) {
            assertEquals(u, UrlRejection.CLEARTEXT_NOT_ALLOWED, (prod.check(u) as UrlCheck.Rejected).reason)
        }
        assertEquals(UrlRejection.USERINFO, (prod.check("http://u:p@108.175.13.7/x") as UrlCheck.Rejected).reason)
    }
}
