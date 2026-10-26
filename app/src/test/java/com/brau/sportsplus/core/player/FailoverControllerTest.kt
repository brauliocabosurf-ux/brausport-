package com.brau.sportsplus.core.player

import com.brau.sportsplus.core.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverControllerTest {
    private var now = 0L
    private fun controller(sources: Int, start: Int = 0) = FailoverController(sources, start, { now })

    /** Simula `ticks` comprobaciones de progreso cada 5 s con la posición avanzando `rate` ms por ms. */
    private fun play(c: FailoverController, seconds: Int, rate: Double = 1.0, startPos: Long = 0) {
        var pos = startPos
        repeat(seconds / 5 + 1) {
            c.onPlaybackProgress(pos)
            now += 5_000
            pos += (5_000 * rate).toLong()
        }
    }

    @Test fun sourceAlternatingReadyAndErrorIsBounded() {
        val c = controller(sources = 3)
        var actions = 0
        // Llega a READY un instante (un tic de progreso) y vuelve a fallar, sin parar.
        while (actions < 1_000) {
            c.onPlaybackProgress(0)
            now += 1_000
            val a = c.onError(behindLiveWindow = false, explicitHls = true)
            actions++
            if (a == FailoverAction.GiveUp) break
        }
        assertTrue("Debe rendirse pronto, no reintentar sin fin (fueron $actions)", actions <= Limits.EPISODE_MAX_ATTEMPTS + 1)
        assertTrue(c.gaveUp)
    }

    @Test fun briefReadyDoesNotResetBudget() {
        val c = controller(sources = 2)
        c.onError(false, explicitHls = true) // cambia a fuente 1
        play(c, seconds = 10) // 10 s "reproduciendo": menos que el umbral de estabilidad
        assertEquals(FailoverAction.GiveUp, c.onError(false, explicitHls = true))
    }

    @Test fun stablePlaybackWithRealProgressResetsBudget() {
        val c = controller(sources = 2)
        assertEquals(FailoverAction.SwitchSource(1), c.onError(false, explicitHls = true))
        play(c, seconds = (Limits.STABLE_PLAYBACK_MS / 1000).toInt() + 10)
        // Presupuesto restablecido: puede volver a cambiar de fuente.
        assertEquals(FailoverAction.SwitchSource(0), c.onError(false, explicitHls = true))
    }

    @Test fun playingWithoutPositionAdvanceDoesNotCountAsStable() {
        val c = controller(sources = 2)
        c.onError(false, explicitHls = true)
        play(c, seconds = 120, rate = 0.0) // "reproduciendo" pero la posición no avanza
        assertEquals(FailoverAction.GiveUp, c.onError(false, explicitHls = true))
    }

    @Test fun continuousBufferingSwitchesThroughSourcesThenGivesUp() {
        val c = controller(sources = 3)
        assertEquals(FailoverAction.SwitchSource(1), c.onStall())
        assertEquals(FailoverAction.SwitchSource(2), c.onStall())
        assertEquals(FailoverAction.GiveUp, c.onStall())
        assertEquals(FailoverAction.GiveUp, c.onStall())
    }

    @Test fun singleSourceGivesUpAfterHlsRetry() {
        val c = controller(sources = 1)
        assertEquals(FailoverAction.RetryAsHls, c.onError(false, explicitHls = false))
        assertEquals(FailoverAction.GiveUp, c.onError(false, explicitHls = false))
    }

    @Test fun allSourcesExhaustedGivesUp() {
        val c = controller(sources = 4, start = 2)
        val seen = mutableListOf<FailoverAction>()
        repeat(10) { seen += c.onError(false, explicitHls = true) }
        assertEquals(listOf(FailoverAction.SwitchSource(3), FailoverAction.SwitchSource(0), FailoverAction.SwitchSource(1)), seen.take(3))
        assertTrue(seen.drop(3).all { it == FailoverAction.GiveUp })
    }

    @Test fun behindLiveWindowIsCapped() {
        val c = controller(sources = 1)
        repeat(Limits.BEHIND_LIVE_RETRIES) { assertEquals(FailoverAction.SeekToLive, c.onError(true, explicitHls = true)) }
        assertEquals(FailoverAction.GiveUp, c.onError(true, explicitHls = true))
    }

    @Test fun episodeTimeLimitStopsSlowFailures() {
        val c = controller(sources = 8)
        assertEquals(FailoverAction.SwitchSource(1), c.onStall())
        now += Limits.EPISODE_MAX_MS + 1
        assertEquals(FailoverAction.GiveUp, c.onStall())
    }

    @Test fun manualRetryRestoresBudget() {
        val c = controller(sources = 1)
        c.onError(false, explicitHls = true)
        assertTrue(c.gaveUp)
        c.restart()
        assertEquals(false, c.gaveUp)
        assertEquals(FailoverAction.RetryAsHls, c.onError(false, explicitHls = false))
    }
}
