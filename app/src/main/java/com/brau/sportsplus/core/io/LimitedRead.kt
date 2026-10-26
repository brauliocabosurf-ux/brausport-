package com.brau.sportsplus.core.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.min

/** Se superó un límite de tamaño. Mensaje fijo. */
class LimitExceededException : IOException("Se superó el límite de tamaño")

/**
 * Lee todo el flujo contando los bytes REALES que entrega (si el flujo ya viene descomprimido,
 * cuenta los descomprimidos). No confía en Content-Length. Corta en cuanto se pasa del límite,
 * así una "bomba" gzip no llega a ocupar memoria.
 */
fun InputStream.readAllLimited(maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream(min(maxBytes, 64L * 1024).toInt())
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw LimitExceededException()
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** UTF-8 tolerante: bytes inválidos se reemplazan por U+FFFD y se quita el BOM. */
fun decodeUtf8(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
}
