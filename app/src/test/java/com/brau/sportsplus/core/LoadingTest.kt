package com.brau.sportsplus.core

import com.brau.sportsplus.core.io.LimitExceededException
import com.brau.sportsplus.core.io.readAllLimited
import com.brau.sportsplus.core.load.Committed
import com.brau.sportsplus.core.load.PlaylistRepository
import com.brau.sportsplus.core.load.StaleResultException
import com.brau.sportsplus.core.net.UrlPolicy
import com.brau.sportsplus.core.playlist.LoadError
import com.brau.sportsplus.core.playlist.PlaylistDecoder
import com.brau.sportsplus.core.playlist.PlaylistException
import com.brau.sportsplus.core.storage.FilePlaylistStore
import com.brau.sportsplus.core.storage.PlaylistStorage
import com.brau.sportsplus.core.storage.StoredPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class LoadingTest {
    private val policy = UrlPolicy(emptyList())
    private val decoder = PlaylistDecoder(policy) { KXmlParser() }
    private fun list(name: String) = "#EXTM3U\n#EXTINF:-1,$name\nhttps://cdn.example.net/$name\n".toByteArray()
    private fun tempDir(): File = Files.createTempDirectory("brau").toFile()

    // ---------------- Lectura limitada ----------------

    @Test fun gzipBombIsStoppedByDecompressedSize() {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gz -> val zeros = ByteArray(1 shl 20); repeat(64) { gz.write(zeros) } } // 64 MiB → pocos KB
        assertTrue("comprimido pequeño", raw.size() < 200_000)
        try {
            GZIPInputStream(ByteArrayInputStream(raw.toByteArray())).readAllLimited(Limits.PLAYLIST_MAX_BYTES)
            fail()
        } catch (e: LimitExceededException) {
        }
    }

    @Test fun exactLimitIsAccepted() {
        val data = ByteArray(Limits.PLAYLIST_MAX_BYTES.toInt())
        assertEquals(data.size, ByteArrayInputStream(data).readAllLimited(Limits.PLAYLIST_MAX_BYTES).size)
    }

    @Test fun interruptedStreamPropagatesIoError() {
        val broken = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100) 'a'.code else throw IOException("conexión cortada")
            override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
        }
        try {
            broken.readAllLimited(1000)
            fail()
        } catch (e: IOException) {
            assertTrue(e !is LimitExceededException)
        }
    }

    // ---------------- Persistencia transaccional ----------------

    private val urlA = "https://a.example.net/lista.m3u"
    private val urlB = "https://b.example.net/lista.m3u"

    @Test fun storeKeepsUrlAndContentTogether() {
        val store = FilePlaylistStore(tempDir())
        store.write(urlA, list("A"))
        val s = store.read()!!
        assertEquals(urlA, s.url)
        assertArrayEquals(list("A"), s.bytes)
        store.write(urlB, list("B"))
        assertEquals(urlB, store.read()!!.url)
        assertArrayEquals(list("B"), store.read()!!.bytes)
    }

    @Test fun crashBetweenTempWriteAndRenameKeepsPreviousVersion() {
        val dir = tempDir()
        val ok = FilePlaylistStore(dir)
        ok.write(urlA, list("A"))
        val crashing = FilePlaylistStore(dir) { throw IOException("simulación de corte antes del rename") }
        try {
            crashing.write(urlB, list("B"))
            fail()
        } catch (e: IOException) {
        }
        val s = FilePlaylistStore(dir).read()!!
        assertEquals(urlA, s.url)
        assertArrayEquals(list("A"), s.bytes)
    }

    @Test fun leftoverTempFromKilledProcessIsIgnored() {
        val dir = tempDir()
        FilePlaylistStore(dir).write(urlA, list("A"))
        // Proceso muerto a mitad de escribir el temporal (no llegó a ejecutar ninguna limpieza).
        File(dir, "playlist.dat.tmp").writeBytes("BRAU-STORE-2\n basura truncada".toByteArray())
        val s = FilePlaylistStore(dir).read()!!
        assertEquals(urlA, s.url)
    }

    @Test fun truncatedOrTamperedFileIsDiscarded() {
        val dir = tempDir()
        val store = FilePlaylistStore(dir)
        store.write(urlA, list("A"))
        val f = File(dir, "playlist.dat")
        val good = f.readBytes()
        f.writeBytes(good.copyOf(good.size - 10))
        assertNull(store.read())
        assertTrue(!f.exists())
        store.write(urlA, list("A"))
        val bytes = f.readBytes()
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertNull(store.read())
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read())
    }

    @Test fun oversizedFileIsDiscarded() {
        val dir = tempDir()
        File(dir, "playlist.dat").writeBytes(ByteArray((Limits.PLAYLIST_MAX_BYTES + 10_000).toInt()))
        assertNull(FilePlaylistStore(dir).read())
    }

    /** Almacén en memoria con fallos y demoras controladas. */
    private class FakeStorage : PlaylistStorage {
        @Volatile var stored: StoredPlaylist? = null
        @Volatile var failWrites = false
        @Volatile var readGate: CompletableDeferred<Unit>? = null
        override fun read(): StoredPlaylist? {
            readGate?.let { runBlocking { it.await() } }
            return stored
        }
        private var counter = 0
        override fun write(url: String, bytes: ByteArray) {
            if (failWrites) throw IOException("disco lleno")
            stored = StoredPlaylist(url, bytes, "v${++counter}")
        }
        override fun clearIfVersion(version: String): Boolean {
            if (stored?.version != version) return false
            stored = null
            return true
        }
    }

    private fun repo(storage: PlaylistStorage, timeoutMs: Long = 5_000, fetch: suspend (String) -> ByteArray) =
        PlaylistRepository(fetch, decoder::decode, storage, Dispatchers.IO, Dispatchers.Default, timeoutMs)

    private fun names(c: Committed?) = c?.result?.channels?.map { it.name }

    @Test fun commitPublishesExactlyWhatIsOnDisk() = runBlocking {
        val storage = FakeStorage()
        val r = repo(storage) { list(it.substringAfterLast('/')) }
        val c = r.refresh(urlA, r.begin())
        assertEquals(urlA, c.url)
        assertEquals(urlA, storage.stored!!.url)
        assertEquals(c, r.committed.value)
    }

    @Test fun storageFailureKeepsPreviousVersionEverywhere() = runBlocking {
        val storage = FakeStorage()
        val r = repo(storage) { url -> list(if (url == urlA) "A" else "B") }
        r.refresh(urlA, r.begin())
        storage.failWrites = true
        try {
            r.refresh(urlB, r.begin())
            fail()
        } catch (e: PlaylistException) {
            assertEquals(LoadError.STORAGE, e.error)
        }
        assertEquals(urlA, storage.stored!!.url)
        assertEquals(urlA, r.committed.value!!.url)
        assertEquals(listOf("A"), names(r.committed.value))
    }

    @Test fun olderSlowResponseCannotOverwriteNewerSelection() = runBlocking {
        val storage = FakeStorage()
        val releaseA = CompletableDeferred<Unit>()
        val r = repo(storage) { url ->
            if (url == urlA) releaseA.await()
            list(if (url == urlA) "A" else "B")
        }
        val tokenA = r.begin()
        val a = async { runCatching { r.refresh(urlA, tokenA) } }
        delay(50)
        val b = r.refresh(urlB, r.begin())
        assertEquals(urlB, b.url)
        releaseA.complete(Unit)
        assertTrue(a.await().exceptionOrNull() is StaleResultException)
        assertEquals(urlB, storage.stored!!.url)
        assertEquals(urlB, r.committed.value!!.url)
    }

    @Test fun interruptionsBetweenStagesLeaveNoPartialChanges() = runBlocking {
        val storage = FakeStorage()
        val r = repo(storage) { list("A") }
        r.refresh(urlA, r.begin())

        // (1) Cancelada durante la descarga.
        val cancelled = AtomicBoolean(false)
        val r1 = repo(storage) {
            suspendCancellableCoroutine<ByteArray> { cont -> cont.invokeOnCancellation { cancelled.set(true) } }
        }
        val job = async { r1.refresh(urlB, r1.begin()) }
        delay(50)
        job.cancel()
        runCatching { job.await() }
        assertTrue(cancelled.get())
        assertEquals(urlA, storage.stored!!.url)

        // (2) Obsoleta entre descarga y confirmación (otra operación empezó mientras parseaba).
        lateinit var r2: PlaylistRepository
        r2 = repo(storage) { list("B").also { r2.begin() } }
        try {
            r2.refresh(urlB, r2.begin())
            fail()
        } catch (e: StaleResultException) {
        }
        assertEquals(urlA, storage.stored!!.url)
        assertNull(r2.committed.value)

        // (3) Contenido inválido: no se guarda nada.
        val r3 = repo(storage) { "<html>no es una lista</html>".toByteArray() }
        try {
            r3.refresh(urlB, r3.begin())
            fail()
        } catch (e: PlaylistException) {
        }
        assertEquals(urlA, storage.stored!!.url)
    }

    @Test fun restartRecoversSamePairFromDisk() = runBlocking {
        val dir = tempDir()
        val first = repo(FilePlaylistStore(dir)) { list("Persistente") }
        first.refresh(urlA, first.begin())
        // "Proceso nuevo": objetos nuevos, solo queda el disco.
        val second = repo(FilePlaylistStore(dir)) { error("sin red") }
        val c = second.loadStored(second.begin())!!
        assertEquals(urlA, c.url)
        assertEquals(listOf("Persistente"), names(c))
    }

    @Test fun corruptStoredListIsClearedOnLoad() = runBlocking {
        val storage = FakeStorage().apply { stored = StoredPlaylist(urlA, "basura".toByteArray(), "a") }
        val r = repo(storage) { error("no se usa") }
        assertNull(r.loadStored(r.begin()))
        assertNull(storage.stored)
    }

    @Test fun slowStartupCannotReplaceOrCancelANewerSelection() = runBlocking {
        val storage = FakeStorage().apply { stored = StoredPlaylist(urlA, list("A"), "a") }
        storage.readGate = CompletableDeferred()
        val r = repo(storage) { list("B") }
        // Arranque: toma turno y se queda leyendo el disco (lento).
        val startup = async(Dispatchers.Default) {
            val token = r.begin()
            r.loadStored(token)
            r.refresh(storage.stored!!.url, token) // no debe llegar aquí
        }
        delay(100)
        // El usuario elige otra lista mientras tanto.
        val b = async { r.refresh(urlB, r.begin()) }
        assertEquals(urlB, b.await().url)
        storage.readGate!!.complete(Unit)
        assertTrue(runCatching { startup.await() }.exceptionOrNull() is StaleResultException)
        assertEquals(urlB, r.committed.value!!.url)
        assertEquals(urlB, storage.stored!!.url)
    }

    @Test fun playerRecoveryDoesNotReplaceANewerCommit() = runBlocking {
        val storage = FakeStorage().apply { stored = StoredPlaylist(urlA, list("A"), "a") }
        val r = repo(storage) { list("B") }
        r.refresh(urlB, r.begin())
        storage.stored = StoredPlaylist(urlA, list("A"), "a") // disco "viejo" simulado
        assertEquals(urlB, r.loadStored()!!.url)
    }

    @Test fun conditionalClearOnlyDeletesTheVersionThatWasRead() {
        val dir = tempDir()
        val store = FilePlaylistStore(dir)
        store.write(urlA, list("A"))
        val a = store.read()!!
        store.write(urlB, list("B"))
        assertTrue("Versión vieja: no debe borrar", !store.clearIfVersion(a.version))
        assertEquals(urlB, store.read()!!.url)
        val b = store.read()!!
        assertTrue(store.clearIfVersion(b.version))
        assertNull(store.read())
    }

    @Test fun staleCleanupNeverDeletesANewerValidList() = runBlocking {
        // Secuencia forzada: se lee A (inválida) → se confirma B válida → falla el parseo de A
        // → la limpieza de A NO debe borrar B, ni en disco ni en el estado publicado.
        val dir = tempDir()
        val storage = FilePlaylistStore(dir)
        val badA = "#EXTM3U sin canales: no es una lista válida".toByteArray()
        storage.write(urlA, badA)
        val aRead = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val decode: (ByteArray) -> com.brau.sportsplus.core.playlist.CatalogResult = { bytes ->
            if (bytes.contentEquals(badA)) {
                aRead.complete(Unit)
                runBlocking { releaseA.await() } // A se queda "parseando" mientras se guarda B
            }
            decoder.decode(bytes) // A lanza PlaylistException; B es válida
        }
        val r = PlaylistRepository({ list("B") }, decode, storage, Dispatchers.IO, Dispatchers.Default, 5_000)
        val loading = async { r.loadStored() }
        aRead.await()
        val b = r.refresh(urlB, r.begin())
        assertEquals(urlB, b.url)
        releaseA.complete(Unit)
        assertNull("La lectura de A falla", loading.await())
        // B sigue intacta en disco y publicada.
        val onDisk = FilePlaylistStore(dir).read()!!
        assertEquals(urlB, onDisk.url)
        assertArrayEquals(list("B"), onDisk.bytes)
        assertEquals(urlB, r.committed.value!!.url)
        assertEquals(listOf("B"), names(r.committed.value))
    }

    // ---------------- Concurrencia, cancelación y tiempos ----------------

    @Test fun cancellingTheCoroutineCancelsTheRequest() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val r = repo(FakeStorage()) {
            suspendCancellableCoroutine<ByteArray> { cont -> cont.invokeOnCancellation { cancelled.set(true) } }
        }
        val job = async { r.refresh(urlA, r.begin()) }
        delay(50)
        job.cancel()
        try {
            job.await()
            fail()
        } catch (e: CancellationException) {
        }
        assertTrue("La petición en curso debe cancelarse", cancelled.get())
    }

    @Test fun totalTimeoutBecomesControlledError() = runBlocking {
        val r = repo(FakeStorage(), timeoutMs = 100) { delay(10_000); list("X") }
        try {
            r.refresh(urlA, r.begin())
            fail()
        } catch (e: PlaylistException) {
            assertEquals(LoadError.TIMEOUT, e.error)
        }
    }

    @Test fun outerCancellationIsNotReportedAsTimeout() = runBlocking {
        val r = repo(FakeStorage(), timeoutMs = 10_000) { delay(10_000); list("X") }
        try {
            withTimeout(100) { r.refresh(urlA, r.begin()) }
            fail()
        } catch (e: PlaylistException) {
            fail("Una cancelación externa no es un error de lista")
        } catch (e: CancellationException) {
        }
    }
}
