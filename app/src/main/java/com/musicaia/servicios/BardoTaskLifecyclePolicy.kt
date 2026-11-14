package com.musicaia.servicios

import java.util.concurrent.CancellationException

/** Reglas puras de cierre de tareas; separadas del ciclo Android para probarlas en JVM. */
internal object BardoTaskLifecyclePolicy {
    fun terminalState(cancellationRequested: Boolean, error: Throwable): String =
        if (cancellationRequested || error is CancellationException) "cancelled" else "failed"

    fun mayFinalize(activeTaskId: String, finishingTaskId: String, taskRunning: Boolean): Boolean =
        activeTaskId.isNotBlank() && activeTaskId == finishingTaskId && !taskRunning

    fun ensureCanCommitCompletion(cancellationRequested: Boolean) {
        if (cancellationRequested) {
            throw CancellationException("Cancelación solicitada antes de confirmar la tarea.")
        }
    }
}
