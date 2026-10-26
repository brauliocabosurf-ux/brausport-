package com.brau.sportsplus.core.player

import com.brau.sportsplus.core.Limits

/** Qué debe hacer el reproductor ante un fallo. */
sealed interface FailoverAction {
    /** Reintentar la misma fuente como HLS (muchos enlaces IPTV no dicen su tipo). */
    data object RetryAsHls : FailoverAction

    /** Volver al borde del directo ("detrás de la ventana en vivo"). */
    data object SeekToLive : FailoverAction

    /** Pasar a otra fuente del mismo canal. */
    data class SwitchSource(val index: Int) : FailoverAction

    /** Rendirse: detener la carga y ofrecer reintento manual. */
    data object GiveUp : FailoverAction
}

/**
 * Presupuesto de reintentos de UN episodio de fallo (lógica pura, probada con reloj simulado).
 *
 * - Episodio: empieza al iniciar la carga (creación del controlador o [restart]) o, tras una
 *   reproducción estable, en cuanto vuelve a cargar ([beginRecoveryIfNeeded], al entrar en
 *   buffering) o en el primer fallo, lo que ocurra antes. Tiene un PLAZO ABSOLUTO ([deadline], reloj
 *   monotónico) de [maxEpisodeMs]: los cambios automáticos de fuente NO lo reinician. El reproductor
 *   programa ese vencimiento con un temporizador ([RecoveryTimers]) y llama a [onDeadline]; no
 *   depende de que llegue otro error.
 * - Cada error o congelamiento consume un intento; al pasar [maxAttempts] también se rinde.
 * - Cada fuente se prueba una sola vez por episodio; "detrás de la ventana en vivo" se reintenta
 *   como máximo [Limits.BEHIND_LIVE_RETRIES] veces; HLS una vez por fuente.
 * - El presupuesto SOLO se restablece tras [stableMs] de reproducción continua con avance real de
 *   la posición. Llegar un instante a READY no cuenta: una fuente que alterna READY/error no puede
 *   provocar reintentos infinitos.
 * - Tras rendirse, todo evento devuelve GiveUp hasta un reintento manual ([restart]).
 */
class FailoverController(
    private val sourceCount: Int,
    initialSource: Int,
    private val clock: () -> Long,
    private val maxAttempts: Int = Limits.EPISODE_MAX_ATTEMPTS,
    private val maxEpisodeMs: Long = Limits.EPISODE_MAX_MS,
    private val stableMs: Long = Limits.STABLE_PLAYBACK_MS,
) {
    var source: Int = initialSource.coerceIn(0, maxOf(0, sourceCount - 1))
        private set
    var triedAsHls = false
        private set
    var gaveUp = false
        private set

    private var failedSources = 0
    private var behindLive = 0
    private var attempts = 0
    /** Inicio del episodio actual (reloj monotónico) o -1 si no hay episodio activo. */
    private var episodeStart = clock() // la carga inicial ya es un episodio
    private var stableStart = -1L
    private var stablePosition = 0L

    /** Momento absoluto en que vence el episodio actual, o null si no hay episodio activo. */
    val deadline: Long?
        get() = if (episodeStart >= 0 && !gaveUp) episodeStart + maxEpisodeMs else null

    /** Llamar cuando venza el temporizador del plazo. Devuelve GiveUp si de verdad venció. */
    fun onDeadline(): FailoverAction? {
        if (gaveUp) return FailoverAction.GiveUp
        val d = deadline ?: return null
        return if (clock() >= d) giveUp() else null
    }

    /**
     * Inicia un episodio de recuperación SOLO si no hay uno activo y no se abandonó. Idempotente:
     * si ya hay episodio, conserva su plazo (nuevas entradas en buffering no lo amplían).
     * Devuelve true si inició uno nuevo.
     */
    fun beginRecoveryIfNeeded(): Boolean {
        if (gaveUp || episodeStart >= 0) return false
        episodeStart = clock()
        stableStart = -1L
        return true
    }

    /** Canal nuevo, cambio manual de fuente o reintento manual: presupuesto completo. */
    fun restart(newSource: Int = source) {
        source = newSource.coerceIn(0, maxOf(0, sourceCount - 1))
        triedAsHls = false
        gaveUp = false
        failedSources = 0
        behindLive = 0
        attempts = 0
        episodeStart = clock() // nuevo episodio de carga con plazo completo
        stableStart = -1L
    }

    fun onError(behindLiveWindow: Boolean, explicitHls: Boolean): FailoverAction {
        if (gaveUp) return FailoverAction.GiveUp
        stableStart = -1L
        if (!consumeAttempt()) return giveUp()
        if (behindLiveWindow && behindLive < Limits.BEHIND_LIVE_RETRIES) {
            behindLive++
            return FailoverAction.SeekToLive
        }
        if (!triedAsHls && !explicitHls) {
            triedAsHls = true
            return FailoverAction.RetryAsHls
        }
        return nextSourceOrGiveUp()
    }

    /** El watchdog venció (cargando sin avanzar). */
    fun onStall(): FailoverAction {
        if (gaveUp) return FailoverAction.GiveUp
        stableStart = -1L
        if (!consumeAttempt()) return giveUp()
        return nextSourceOrGiveUp()
    }

    /** La fuente no pasó la política de red. */
    fun onSourceBlocked(): FailoverAction = onStall()

    /** Llamar periódicamente mientras se reproduce, con la posición actual. */
    fun onPlaybackProgress(positionMs: Long) {
        if (gaveUp) return
        val now = clock()
        if (stableStart < 0) {
            stableStart = now
            stablePosition = positionMs
            return
        }
        val elapsed = now - stableStart
        val advanced = positionMs - stablePosition
        if (elapsed >= stableMs && advanced >= stableMs * 3 / 4) {
            // Reproducción estable con avance real: el episodio de fallo terminó.
            failedSources = 0
            behindLive = 0
            attempts = 0
            episodeStart = -1L
            triedAsHls = false
            stableStart = now
            stablePosition = positionMs
        }
    }

    /** Dejó de reproducirse (pausa, buffering, error): la ventana de estabilidad empieza de cero. */
    fun onNotPlaying() {
        stableStart = -1L
    }

    private fun consumeAttempt(): Boolean {
        val now = clock()
        if (episodeStart < 0) episodeStart = now
        attempts++
        return attempts <= maxAttempts && now - episodeStart < maxEpisodeMs
    }

    private fun nextSourceOrGiveUp(): FailoverAction {
        if (failedSources < sourceCount - 1) {
            failedSources++
            source = (source + 1) % sourceCount
            triedAsHls = false
            behindLive = 0
            return FailoverAction.SwitchSource(source)
        }
        return giveUp()
    }

    private fun giveUp(): FailoverAction {
        gaveUp = true
        return FailoverAction.GiveUp
    }
}
