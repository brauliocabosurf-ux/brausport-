package com.brau.sportsplus.core.playlist

import com.brau.sportsplus.core.Limits
import com.brau.sportsplus.core.net.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class ParsersTest {
    private val policy = UrlPolicy(listOf("cleartext.example.net"))
    private val decoder = PlaylistDecoder(policy) { KXmlParser() }

    private fun expectError(error: LoadError, block: () -> Unit) {
        try {
            block()
            fail("Se esperaba $error")
        } catch (e: PlaylistException) {
            assertEquals(error, e.error)
        }
    }

    // ---------------- M3U ----------------

    @Test fun m3uBasicAndDirectivesAreIgnored() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://evil.example/x.png" group-title="Deportes",Canal Uno
            #EXTVLCOPT:http-user-agent=Malicioso
            #EXTVLCOPT:http-referrer=https://evil.example
            #KODIPROP:inputstream.adaptive.license_key=https://10.0.0.1/key
            https://cdn.example.net/uno.m3u8
        """.trimIndent()
        val p = M3uParser.parse(text)
        assertEquals(1, p.entries.size)
        assertEquals(Entry("Canal Uno", "https://cdn.example.net/uno.m3u8", "Deportes"), p.entries[0])
    }

    @Test fun m3uRejectsLongUrlsWithoutTruncating() {
        val long = "https://cdn.example.net/" + "a".repeat(Limits.URL_MAX_CHARS)
        val p = M3uParser.parse("#EXTINF:-1,Largo\n$long\n#EXTINF:-1,Bien\nhttps://cdn.example.net/ok")
        assertEquals(listOf("Bien"), p.entries.map { it.name })
        assertEquals(1, p.invalidUrls)
    }

    @Test fun m3uLongLineIsSkippedWithItsEntry() {
        val huge = "#EXTINF:-1," + "x".repeat(Limits.M3U_LINE_MAX_CHARS + 10)
        val p = M3uParser.parse("$huge\nhttps://cdn.example.net/a\n#EXTINF:-1,B\nhttps://cdn.example.net/b")
        assertEquals(1, p.skippedLines)
        // La URL tras la línea descartada queda sin título: recibe un nombre neutral, nunca la URL.
        assertEquals(listOf("Canal 1", "B"), p.entries.map { it.name })
    }

    @Test fun m3uNamesAreCleanedAndLimited() {
        val name = "Canal\u0007 \t con   control " + "ñ".repeat(400)
        val p = M3uParser.parse("#EXTINF:-1,$name\nhttps://cdn.example.net/a")
        val n = p.entries[0].name
        assertEquals(Limits.NAME_MAX_CHARS, n.length)
        assertFalse(n.any { it.isISOControl() })
        assertTrue(n.startsWith("Canal con control "))
    }

    @Test fun m3uEntryLimit() {
        val sb = StringBuilder("#EXTM3U\n")
        repeat(Limits.PLAYLIST_MAX_ENTRIES + 50) { sb.append("#EXTINF:-1,C$it\nhttps://cdn.example.net/$it\n") }
        val p = M3uParser.parse(sb.toString())
        assertEquals(Limits.PLAYLIST_MAX_ENTRIES, p.entries.size)
        assertTrue(p.truncated)
    }

    @Test fun m3uCorruptInputDoesNotCrash() {
        val garbage = (0 until 5000).map { ((it * 31) % 256).toChar() }.joinToString("")
        M3uParser.parse(garbage)
        M3uParser.parse("#EXTINF\n#EXTINF:,\n\n,,,\nftp://x\n")
        expectError(LoadError.EMPTY) { decoder.decode("#EXTM3U\n#EXTINF:-1,Nada\n".toByteArray()) }
    }

    @Test fun decoderRejectsOversizedInput() {
        expectError(LoadError.TOO_LARGE) { decoder.decode(ByteArray((Limits.PLAYLIST_MAX_BYTES + 1).toInt()) { 'a'.code.toByte() }) }
    }

    // ---------------- XSPF ----------------

    private fun xspf(tracks: String) = """<?xml version="1.0" encoding="UTF-8"?>
<playlist xmlns="http://xspf.org/ns/0/" xmlns:vlc="http://www.videolan.org/vlc/playlist/ns/0/" version="1">
  <title>Lista</title>
  <trackList>$tracks</trackList>
</playlist>"""

    @Test fun xspfBasicWithNamespacesAndStandardEntities() {
        val doc = xspf("""
    <track><location>https://cdn.example.net/a?x=1&amp;y=2</location><title>Fox &amp; Amigos &#233;</title>
      <extension application="http://www.videolan.org/vlc/playlist/0"><vlc:id>0</vlc:id><vlc:option>network-caching=1000</vlc:option></extension>
    </track>""")
        val p = XspfParser { KXmlParser() }.parse(doc)
        assertEquals(1, p.entries.size)
        assertEquals("https://cdn.example.net/a?x=1&y=2", p.entries[0].url)
        assertEquals("Fox & Amigos é", p.entries[0].name)
    }

    @Test fun xspfDtdAndExternalEntitiesAreRejected() {
        val xxe = """<?xml version="1.0"?>
<!DOCTYPE playlist [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<playlist xmlns="http://xspf.org/ns/0/"><trackList><track><location>https://a.example.net/&xxe;</location></track></trackList></playlist>"""
        expectError(LoadError.DTD_NOT_ALLOWED) { XspfParser { KXmlParser() }.parse(xxe) }
        val lol = """<?xml version="1.0"?><!doctype lolz [<!entity lol "lol"><!entity lol2 "&lol;&lol;">]><playlist/>"""
        expectError(LoadError.DTD_NOT_ALLOWED) { XspfParser { KXmlParser() }.parse(lol) }
        expectError(LoadError.DTD_NOT_ALLOWED) { decoder.decode(xxe.toByteArray()) }
    }

    @Test fun xspfUndefinedEntityIsRejected() {
        val doc = xspf("<track><location>https://a.example.net/&noexiste;</location></track>")
        expectError(LoadError.BAD_FORMAT) { XspfParser { KXmlParser() }.parse(doc) }
    }

    @Test fun xspfWrongRootOrCorruptIsRejected() {
        expectError(LoadError.BAD_FORMAT) { XspfParser { KXmlParser() }.parse("<html><body/></html>") }
        expectError(LoadError.BAD_FORMAT) { XspfParser { KXmlParser() }.parse(xspf("<track><location>https://a")) }
        val otherNs = """<p:playlist xmlns:p="urn:otro"><p:trackList/></p:playlist>"""
        expectError(LoadError.BAD_FORMAT) { XspfParser { KXmlParser() }.parse(otherNs) }
    }

    @Test fun xspfDepthAndTextLimits() {
        val deep = "<a>".repeat(Limits.XML_MAX_DEPTH + 2) + "</a>".repeat(Limits.XML_MAX_DEPTH + 2)
        expectError(LoadError.BAD_FORMAT) { XspfParser { KXmlParser() }.parse(xspf("<track>$deep</track>")) }
        val longTitle = "t".repeat(Limits.XML_MAX_TEXT_CHARS + 1)
        expectError(LoadError.BAD_FORMAT) {
            XspfParser { KXmlParser() }.parse(xspf("<track><location>https://a.example.net/x</location><title>$longTitle</title></track>"))
        }
    }

    @Test fun xspfIgnoresTracksOutsideTrackListAndNonHttpLocations() {
        val doc = xspf("""
            <track><location>file:///sdcard/x.mp4</location><title>Local</title></track>
            <track><location>https://a.example.net/ok</location><title>Ok</title></track>""")
        val p = XspfParser { KXmlParser() }.parse(doc)
        assertEquals(listOf("Ok"), p.entries.map { it.name })
        assertEquals(1, p.invalidUrls)
    }

    // ---------------- Catálogo ----------------

    @Test fun catalogAppliesPolicyToEverySource() {
        val text = """
            #EXTINF:-1,Bueno
            https://cdn.example.net/1
            #EXTINF:-1,HTTP autorizado
            http://cleartext.example.net/2
            #EXTINF:-1,HTTP no autorizado
            http://otro.example.net/3
            #EXTINF:-1,Privado
            https://192.168.0.10/4
            #EXTINF:-1,Credenciales
            https://u:p@cdn.example.net/5
            #EXTINF:-1,Local
            https://localhost/6
        """.trimIndent()
        val r = decoder.decode(text.toByteArray())
        assertEquals(setOf("Bueno", "HTTP autorizado"), r.channels.map { it.name }.toSet())
        assertEquals(4, r.blockedSources)
    }

    @Test fun allSourcesBlockedGivesSpecificError() {
        expectError(LoadError.NO_ALLOWED_SOURCES) {
            decoder.decode("#EXTINF:-1,X\nhttp://no-autorizado.example.net/x\n".toByteArray())
        }
    }

    @Test fun sourcesPerChannelAreCapped() {
        val sb = StringBuilder()
        repeat(12) { sb.append("#EXTINF:-1,TUDN\nhttps://cdn.example.net/tudn/$it\n") }
        val r = decoder.decode(sb.toString().toByteArray())
        assertEquals(Limits.SOURCES_PER_CHANNEL, r.channels.single().sources.size)
        assertEquals(12 - Limits.SOURCES_PER_CHANNEL, r.droppedSources)
    }

    @Test fun realChannelNamesAreClassified() {
        val bytes = File("src/test/resources/fixtures/lista_nombres_reales.m3u").readBytes()
        val r = decoder.decode(bytes)
        val bySection = r.channels.groupBy { if (it.isEvent) "VIVO/" + it.sport else it.section }
        assertTrue(r.channels.size in 100..166)
        assertFalse("Nada debería caer en Otros", bySection.containsKey(Catalog.OTHER))
        assertEquals("Liga MX", r.channels.first { it.name == "América vs Pachuca" }.sport)
        assertEquals("Europa", r.channels.first { it.name == "Barcelona vs Real Madrid" }.sport)
        assertEquals(3, r.channels.first { it.name == "Barcelona vs Real Madrid" }.sources.size)
        assertEquals("Deportes MX", r.channels.first { it.name == "TUDN" }.section)
        assertEquals("logo_tudn_mx", r.channels.first { it.name == "TUDN" }.logoName)
    }

    @Test fun everyLogoResourceExists() {
        for (name in LogoNames.allResourceNames) {
            assertTrue(name, File("src/main/res/drawable-nodpi/$name.png").exists())
        }
    }

    @Test fun filtersMatchMainScreenBehaviour() {
        val r = decoder.decode(File("src/test/resources/fixtures/lista_nombres_reales.m3u").readBytes())
        val live = ChannelFilters.apply(r.channels, Catalog.LIVE, "", emptySet())
        assertTrue(live.all { it.isEvent })
        val nba = ChannelFilters.apply(r.channels, ChannelFilters.SPORT_PREFIX + "NBA", "", emptySet())
        assertTrue(nba.isNotEmpty() && nba.all { it.sport == "NBA" })
        val search = ChannelFilters.apply(r.channels, ChannelFilters.ALL, "pachuca", emptySet())
        assertEquals(listOf("América vs Pachuca"), search.map { it.name })
        val fav = ChannelFilters.apply(r.channels, ChannelFilters.FAVORITES, "", setOf("ch:tudn"))
        assertEquals(listOf("TUDN"), fav.map { it.name })
    }

    // ---------------- Nombres sin URL ----------------

    private val secretPath = "SECRETO-RUTA-7f3a9c"
    private val secretQuery = "TOKEN-QUERY-55bd21"

    private fun tokenUrl(n: Int) = "https://cdn.example.net/live/$secretPath/$n/index.m3u8?token=$secretQuery&n=$n"

    private fun assertNoTokens(r: CatalogResult) {
        for (c in r.channels) {
            for (text in listOf(c.name, c.subtitle, c.section, c.sport.orEmpty()) + c.sources.map { it.label }) {
                assertFalse("Token visible en «$text»", text.contains(secretPath) || text.contains(secretQuery) || text.contains("index.m3u8"))
            }
        }
    }

    @Test fun untitledM3uEntriesGetNeutralNamesAndKeepUrlIntact() {
        val text = "#EXTM3U\n${tokenUrl(1)}\n#EXTINF:-1,\n${tokenUrl(2)}\n#EXTINF:-1,Real\nhttps://cdn.example.net/real\n"
        val r = decoder.decode(text.toByteArray())
        assertNoTokens(r)
        assertEquals(setOf("Canal 1", "Canal 2", "Real"), r.channels.map { it.name }.toSet())
        // La URL se conserva exacta para reproducir.
        assertEquals(tokenUrl(1), r.channels.first { it.name == "Canal 1" }.sources.single().url)
    }

    @Test fun untitledXspfTracksGetNeutralNames() {
        val doc = xspf("<track><location>${tokenUrl(1).replace("&", "&amp;")}</location></track>" +
            "<track><location>${tokenUrl(2).replace("&", "&amp;")}</location><title></title></track>")
        val r = decoder.decode(doc.toByteArray())
        assertNoTokens(r)
        assertEquals(listOf("Canal 1", "Canal 2"), r.channels.map { it.name })
        assertEquals(tokenUrl(2), r.channels[1].sources.single().url)
    }

    @Test fun generatedNamesNeverMerge() {
        // Dos canales sin título + uno que de verdad se llama «Canal 1»: tres canales distintos.
        val text = "${tokenUrl(1)}\n${tokenUrl(2)}\n#EXTINF:-1,Canal 1\nhttps://cdn.example.net/otro\n"
        val r = decoder.decode(text.toByteArray())
        assertEquals(3, r.channels.size)
        assertTrue(r.channels.all { it.sources.size == 1 })
        assertEquals(2, r.channels.count { it.name == "Canal 1" })
    }

    @Test fun errorsAndRedactionNeverContainTokens() {
        assertFalse(UrlPolicy.redact(tokenUrl(1)).contains(secretPath))
        assertFalse(UrlPolicy.redact(tokenUrl(1)).contains(secretQuery))
        for (e in LoadError.values()) assertFalse(PlaylistException(e).message!!.contains(secretPath))
    }
}
