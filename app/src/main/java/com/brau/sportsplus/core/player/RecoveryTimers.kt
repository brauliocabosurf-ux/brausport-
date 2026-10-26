package com.brau.sportsplus.core.player

/** Programa tareas con retardo. En la app se implementa con Handler; en pruebas, con tiempo virtual. */
fun interface TaskScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ScheduledTask
}

fun interface ScheduledTask {
    fun cancel()
}

/**
 * Temporizadores de recuperación del reproductor: watchdog de carga y plazo absoluto del episodio.
 *
 * Cada [reset] (salir, cambiar de canal, reintento manual) cancela lo pendiente y abre una nueva
 * "época": un callback que ya estaba en cola de la época anterior se descarta aunque llegue a
 * ejecutarse, así nunca detiene el canal nuevo.
 */
class RecoveryTimers(
    private val scheduler: TaskScheduler,
    private val clock: () -> Long,
    private val stallMs: Long,
) {
    private var epoch = 0L
    private var stallTask: ScheduledTask? = null
    private var deadlineTask: ScheduledTask? = null

    fun reset() {
        epoch++
        cancelAll()
    }

    fun cancelAll() {
        stallTask?.cancel()
        deadlineTask?.cancel()
        stallTask = null
        deadlineTask = null
    }

    /** Empezó a cargar: si no avanza en [stallMs], se llama a [onStall]. */
    fun onBuffering(onStall: () -> Unit) {
        stallTask?.cancel()
        val e = epoch
        stallTask = scheduler.schedule(stallMs) { if (e == epoch) onStall() }
    }

    fun onNotBuffering() {
        stallTask?.cancel()
        stallTask = null
    }

    /** (Re)programa el plazo absoluto. [deadlineAt] null = no hay episodio activo: se cancela. */
    fun armDeadline(deadlineAt: Long?, onDeadline: () -> Unit) {
        deadlineTask?.cancel()
        deadlineTask = null
        if (deadlineAt == null) return
        val e = epoch
        deadlineTask = scheduler.schedule(maxOf(0L, deadlineAt - clock())) { if (e == epoch) onDeadline() }
    }
}
