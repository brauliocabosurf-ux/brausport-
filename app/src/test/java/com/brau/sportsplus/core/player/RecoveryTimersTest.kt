package com.brau.sportsplus.core.player

import com.brau.sportsplus.core.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/**
 * Plazo absoluto del episodio con temporizadores en tiempo VIRTUAL (determinista).
 * [jitterMs] simula el retraso normal con que un temporizador real (Handler) ejecuta las tareas.
 */
class RecoveryTimersTest {
    private class VirtualScheduler(private val jitterMs: Long = 0) : TaskScheduler {
        var now = 0L
        private var seq = 0L
        private class Item(val at: Long, val seq: Long, val task: () -> Unit) { var cancelled = false }
        private val queue = PriorityQueue<Item>(compareBy<Item>({ it.at }, { it.seq }))

        override fun schedule(delayMs: Long, task: () -> Unit): ScheduledTask {
            val item = Item(now + delayMs + jitterMs, seq++, task)
            queue += item
            return ScheduledTask { item.cancelled = true }
        }

        /** Ejecuta tareas hasta [limit] o hasta que no queden. */
        fun runUntil(limit: Long, stop: () -> Boolean = { false }) {
            while (queue.isNotEmpty() && !stop()) {
                val next = queue.poll()
                if (next.at > limit) { queue += next; now = limit; return }
                now = next.at
                if (!next.cancelled) next.task()
            }
        }
    }

    /**
     * Simula el reproductor con fuentes que se quedan cargando. Reproduce el cableado de
     * PlayerActivity: playCurrent → armDeadline; STATE_BUFFERING → beginRecoveryIfNeeded + armDeadline
     * + watchdog; reproducción → comprobación de progreso cada segundo.
     */
    private class FakePlayer(val sched: VirtualScheduler, sources: Int) {
        val failover = FailoverController(sources, 0, { sched.now })
        val timers = RecoveryTimers(sched, { sched.now }, Limits.STALL_TIMEOUT_MS)
        var gaveUpAt: Long? = null
        var switches = 0
        var deadlinesSeen = mutableListOf<Long?>()
        private var playing = false
        private var position = 0L

        fun start() {
            timers.reset()
            loadCurrentSource()
        }

        /** playCurrent(): el reproductor carga la fuente y entra en buffering. */
        private fun loadCurrentSource() {
            timers.onNotBuffering()
            armDeadline()
            enterBuffering()
        }

        /** Listener: STATE_BUFFERING. */
        fun enterBuffering() {
            if (playing) { playing = false; failover.onNotPlaying() }
            failover.beginRecoveryIfNeeded()
            armDeadline()
            deadlinesSeen += failover.deadline
            timers.onBuffering { apply(failover.onStall()) }
        }

        /** Listener: STATE_READY + isPlaying = true; arranca la comprobación de progreso. */
        fun startPlaying() {
            timers.onNotBuffering()
            playing = true
            sched.schedule(1_000) { progressTick() }
        }

        private fun progressTick() {
            if (!playing || gaveUpAt != null) return
            failover.onPlaybackProgress(position)
            position += 1_000
            if (failover.deadline == null) timers.armDeadline(null) {} // fin del episodio
            sched.schedule(1_000) { progressTick() }
        }

        private fun armDeadline() =
            timers.armDeadline(failover.deadline) { failover.onDeadline()?.let { apply(it) } }

        fun apply(a: FailoverAction) {
            armDeadline()
            when (a) {
                is FailoverAction.SwitchSource -> { switches++; loadCurrentSource() }
                FailoverAction.RetryAsHls, FailoverAction.SeekToLive -> loadCurrentSource()
                FailoverAction.GiveUp -> {
                    gaveUpAt = sched.now
                    playing = false
                    timers.cancelAll() // player.stop() + «Reintentar»
                }
            }
        }
    }

    @Test fun eightBufferingSourcesGiveUpAt120SecondsNotLater() {
        val sched = VirtualScheduler(jitterMs = 15) // tolerancia típica de un Handler
        val p = FakePlayer(sched, sources = 8)
        p.start()
        sched.runUntil(10 * 60_000L) { p.gaveUpAt != null }
        val at = p.gaveUpAt!!
        assertTrue("Se rindió a los $at ms", at >= Limits.EPISODE_MAX_MS && at <= Limits.EPISODE_MAX_MS + 100)
        // Sin plazo absoluto habría seguido hasta ~8 × 20 s = 160 s.
        assertTrue("Los cambios automáticos no reiniciaron el plazo (${p.switches} cambios)", p.switches in 5..6)
    }

    @Test fun automaticSwitchesDoNotMoveTheDeadline() {
        val sched = VirtualScheduler()
        val c = FailoverController(8, 0, { sched.now })
        val d0 = c.deadline!!
        sched.now = 20_000; c.onStall()
        sched.now = 40_000; c.onStall()
        assertEquals(d0, c.deadline)
    }

    @Test fun staleCallbackFromPreviousChannelDoesNothing() {
        val sched = VirtualScheduler()
        val timers = RecoveryTimers(sched, { sched.now }, Limits.STALL_TIMEOUT_MS)
        var oldFired = false
        // Programado para el canal A, pero simulamos que el Handler ya lo sacó de la cola
        // (no se puede cancelar) justo cuando el usuario cambia al canal B.
        var captured: (() -> Unit)? = null
        val capturing = TaskScheduler { _, task -> captured = task; ScheduledTask { } }
        val t2 = RecoveryTimers(capturing, { sched.now }, Limits.STALL_TIMEOUT_MS)
        t2.armDeadline(1_000) { oldFired = true }
        t2.reset() // cambio manual de canal
        captured!!.invoke()
        assertTrue("Un callback viejo no debe afectar al canal nuevo", !oldFired)
        // Y reset() cancela lo que sí estaba pendiente.
        var pendingFired = false
        timers.onBuffering { pendingFired = true }
        timers.reset()
        sched.runUntil(60_000)
        assertTrue(!pendingFired)
    }

    @Test fun stablePlaybackEndsEpisodeAndManualRetryStartsANewOne() {
        var now = 0L
        val c = FailoverController(2, 0, { now })
        // Reproducción estable con avance real: termina el episodio, no hay plazo.
        var pos = 0L
        repeat(10) { c.onPlaybackProgress(pos); now += 5_000; pos += 5_000 }
        assertNull(c.deadline)
        // Un fallo posterior abre un episodio de recuperación con plazo propio.
        c.onError(false, explicitHls = true)
        assertEquals(now + Limits.EPISODE_MAX_MS, c.deadline)
        // Al agotarlo: GiveUp. El reintento manual da un plazo completo nuevo.
        now += Limits.EPISODE_MAX_MS
        assertEquals(FailoverAction.GiveUp, c.onDeadline())
        assertNull(c.deadline)
        c.restart()
        assertEquals(now + Limits.EPISODE_MAX_MS, c.deadline)
    }

    @Test fun deadlineCallbackBeforeTimeIsIgnored() {
        var now = 0L
        val c = FailoverController(3, 0, { now })
        now = 119_000
        assertNull(c.onDeadline())
        now = 120_000
        assertEquals(FailoverAction.GiveUp, c.onDeadline())
    }

    // ---- Buffering tras una reproducción estable ----

    @Test fun bufferingAfterStablePlaybackStartsTheDeadlineImmediately() {
        val sched = VirtualScheduler(jitterMs = 15)
        val p = FakePlayer(sched, sources = 8)
        p.start()
        sched.runUntil(1_000)
        p.startPlaying()
        // Más de 30 s de reproducción estable con avance real: no queda episodio activo.
        sched.runUntil(40_000)
        assertNull("Tras 30 s estables no debe haber episodio", p.failover.deadline)
        assertNull(p.gaveUpAt)

        // Nueva entrada en buffering: el plazo empieza YA, no al primer error ni al watchdog.
        val bufferingAt = sched.now
        p.enterBuffering()
        val deadline = p.failover.deadline
        assertEquals(bufferingAt + Limits.EPISODE_MAX_MS, deadline)

        // Las ocho fuentes se quedan cargando; los cambios automáticos no desplazan el plazo.
        sched.runUntil(bufferingAt + 10 * 60_000L) { p.gaveUpAt != null }
        val at = p.gaveUpAt!!
        val elapsed = at - bufferingAt
        assertTrue("Se rindió a los $elapsed ms del buffering", elapsed >= Limits.EPISODE_MAX_MS && elapsed <= Limits.EPISODE_MAX_MS + 100)
        assertTrue("Cambios automáticos: ${p.switches}", p.switches in 5..6)
        val during = p.deadlinesSeen.drop(p.deadlinesSeen.indexOf(deadline)).filterNotNull()
        assertTrue("Ningún cambio de fuente amplió el plazo: $during", during.all { it == deadline })
        // Sin la corrección se rendiría a ~140 s (20 s de watchdog + 120 s).
    }

    @Test fun repeatedBufferingDoesNotRestartTheClock() {
        val sched = VirtualScheduler()
        val p = FakePlayer(sched, sources = 8)
        p.start()
        sched.runUntil(1_000); p.startPlaying()
        sched.runUntil(40_000)
        assertNull(p.failover.deadline)
        p.enterBuffering()
        val d = p.failover.deadline!!
        sched.runUntil(45_000); p.startPlaying() // vuelve a reproducir un momento (no estable)
        sched.runUntil(50_000); p.enterBuffering()
        sched.runUntil(52_000); p.startPlaying()
        sched.runUntil(55_000); p.enterBuffering()
        assertEquals("Nuevas entradas en buffering no amplían el plazo", d, p.failover.deadline)
        assertTrue(!p.failover.beginRecoveryIfNeeded())
        assertEquals(d, p.failover.deadline)
    }

    @Test fun stableRecoveryCancelsThePendingExpiry() {
        val sched = VirtualScheduler(jitterMs = 15)
        val p = FakePlayer(sched, sources = 8)
        p.start()
        sched.runUntil(1_000); p.startPlaying()
        sched.runUntil(40_000)
        p.enterBuffering() // episodio de recuperación: vence a los 160 s
        sched.runUntil(45_000); p.startPlaying() // se recupera y reproduce estable > 30 s
        sched.runUntil(90_000)
        assertNull("La recuperación estable cierra el episodio", p.failover.deadline)
        sched.runUntil(10 * 60_000L) { p.gaveUpAt != null }
        assertNull("El vencimiento pendiente quedó cancelado", p.gaveUpAt)
    }

    @Test fun beginRecoveryIsIdempotentAndRespectsGiveUp() {
        var now = 0L
        val c = FailoverController(2, 0, { now })
        assertTrue("La carga inicial ya es un episodio", !c.beginRecoveryIfNeeded())
        assertEquals(Limits.EPISODE_MAX_MS, c.deadline)
        now = Limits.EPISODE_MAX_MS
        assertEquals(FailoverAction.GiveUp, c.onDeadline())
        assertTrue("Tras abandonar no se inicia otro episodio", !c.beginRecoveryIfNeeded())
        assertNull(c.deadline)
    }
}
