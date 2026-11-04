package com.musicaia.modulos.litert

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * Motor LiteRT-LM. Un solo executor de fondo para Engine + Conversation.
 * Cierre controlado; ninguna vista se modifica desde hilos secundarios.
 */
object EngineLiteRT {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LiteRT-Engine").apply { isDaemon = true }
    }

    fun submit(task: () -> Unit) = executor.submit(task)

    fun shutdownControlled() {
        executor.shutdown()
    }

    fun isShutdown(): Boolean = executor.isShutdown
}
