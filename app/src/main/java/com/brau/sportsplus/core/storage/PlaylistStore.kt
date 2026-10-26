package com.brau.sportsplus.core.storage

import com.brau.sportsplus.core.Limits
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * La lista vigente: su URL y su contenido SIEMPRE viajan juntos.
 * [version] identifica exactamente ese contenido guardado (SHA-256 del archivo en hex): sirve para
 * borrar solo esa versión y nunca una más nueva.
 */
class StoredPlaylist(val url: String, val bytes: ByteArray, val version: String)

/** Almacenamiento de la lista vigente (interfaz para poder simular fallos en pruebas). */
interface PlaylistStorage {
    /** La lista vigente o null si no hay ninguna válida. Nunca lanza por un archivo dañado. */
    fun read(): StoredPlaylist?

    /** Reemplaza URL + contenido como UNA sola operación atómica. Si falla, queda la versión anterior. */
    fun write(url: String, bytes: ByteArray)

    /**
     * Borra la lista guardada SOLO si sigue siendo la versión [version]. Si entre la lectura y
     * este borrado se guardó otra, no toca nada y devuelve false.
     */
    fun clearIfVersion(version: String): Boolean
}

/**
 * URL y contenido en UN archivo, reemplazado de forma atómica:
 *
 *   1. Se escribe `playlist.dat.tmp` completo y se sincroniza a disco (fsync).
 *   2. Se renombra sobre `playlist.dat` (rename es atómico en el mismo sistema de archivos).
 *
 * Si el proceso muere antes del paso 2, queda la versión anterior intacta (el temporal se ignora).
 * Si muere después, queda la nueva completa. Nunca hay URL de una versión con contenido de otra.
 *
 * Formato: "BRAU-STORE-2\n" · largo URL (int) · URL UTF-8 · largo datos (int) · datos ·
 * SHA-256 de todo lo anterior. La suma detecta archivos truncados o dañados, que se descartan.
 *
 * Límite conocido: tras un corte de energía justo después del rename, algunos sistemas de
 * archivos pueden volver a la versión anterior (no se sincroniza el directorio). Sigue siendo
 * una pareja URL/contenido coherente.
 */
class FilePlaylistStore(
    private val dir: File,
    /** Solo para pruebas: se ejecuta entre escribir el temporal y renombrarlo. */
    private val beforeCommit: () -> Unit = {},
) : PlaylistStorage {
    private val file = File(dir, "playlist.dat")
    private val tmp = File(dir, "playlist.dat.tmp")

    @Synchronized
    override fun read(): StoredPlaylist? {
        if (!file.isFile) return null
        if (file.length() > MAX_FILE_BYTES) {
            file.delete()
            return null
        }
        return try {
            val all = FileInputStream(file).use { it.readBytes() }
            parse(all) ?: run {
                file.delete()
                null
            }
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    @Synchronized
    override fun write(url: String, bytes: ByteArray) {
        if (bytes.size > Limits.PLAYLIST_MAX_BYTES) throw IOException("Lista demasiado grande")
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        if (urlBytes.size > MAX_URL_BYTES) throw IOException("URL demasiado larga")
        dir.mkdirs()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(encode(urlBytes, bytes))
                out.flush()
                out.fd.sync()
            }
            beforeCommit()
            if (!tmp.renameTo(file)) throw IOException("No se pudo reemplazar la lista guardada")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    @Synchronized
    override fun clearIfVersion(version: String): Boolean {
        if (!file.isFile) return false
        val current = try {
            if (file.length() > MAX_FILE_BYTES) null else FileInputStream(file).use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
        // Se compara la versión del archivo ACTUAL (su suma) con la leída antes.
        if (current == null || current.size < 32 || hex(current.copyOfRange(current.size - 32, current.size)) != version) {
            return false
        }
        return file.delete()
    }

    private fun encode(url: ByteArray, data: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(MAGIC.size + 8 + url.size + data.size)
        DataOutputStream(body).use { out ->
            out.write(MAGIC)
            out.writeInt(url.size)
            out.write(url)
            out.writeInt(data.size)
            out.write(data)
        }
        val payload = body.toByteArray()
        return payload + sha256(payload)
    }

    private fun parse(all: ByteArray): StoredPlaylist? {
        if (all.size < MAGIC.size + 8 + 32) return null
        val payload = all.copyOfRange(0, all.size - 32)
        if (!MessageDigest.isEqual(sha256(payload), all.copyOfRange(all.size - 32, all.size))) return null
        return try {
            DataInputStream(payload.inputStream()).use { input ->
                val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
                if (!magic.contentEquals(MAGIC)) return null
                val urlLen = input.readInt()
                if (urlLen !in 1..MAX_URL_BYTES) return null
                val url = ByteArray(urlLen).also { input.readFully(it) }.toString(Charsets.UTF_8)
                val dataLen = input.readInt()
                if (dataLen < 0 || dataLen > Limits.PLAYLIST_MAX_BYTES) return null
                val data = ByteArray(dataLen).also { input.readFully(it) }
                if (input.available() != 0) return null
                StoredPlaylist(url, data, hex(all.copyOfRange(all.size - 32, all.size)))
            }
        } catch (e: EOFException) {
            null
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        private val MAGIC = "BRAU-STORE-2\n".toByteArray(Charsets.US_ASCII)
        private const val MAX_URL_BYTES = Limits.URL_MAX_CHARS
        private val MAX_FILE_BYTES = MAGIC.size + 8L + MAX_URL_BYTES + Limits.PLAYLIST_MAX_BYTES + 32
    }
}
