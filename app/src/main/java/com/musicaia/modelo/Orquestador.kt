package com.musicaia.modelo

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Núcleo secuencial de ejecución BARDO.
 * Cada módulo se descarga antes de activar el siguiente para no mantener
 * dos modelos pesados residentes simultáneamente.
 */
class Orquestador(
    private val dispatchToMain: ((() -> Unit) -> Unit)? = null
) {
    private val tag = "Orquestador"
    @Volatile private var cancelado = false
    @Volatile private var isRunningFlag = false
    @Volatile private var listener: ProgresoListener? = null
    @Volatile private var executionGeneration = 0L
    private val mainHandler: Handler? = runCatching {
        Looper.getMainLooper()?.let(::Handler)
    }.getOrNull()

    val isRunning: Boolean get() = isRunningFlag

    interface ProgresoListener {
        fun onProgreso(paso: String, progreso: Float, mensaje: String)
        fun onCompletado(resultados: List<Pair<String, Dato>>)
        fun onError(mensaje: String)
    }

    fun setListener(listener: ProgresoListener) {
        this.listener = listener
    }

    fun start() { isRunningFlag = true }

    fun cancelar() { cancelado = true }

    fun stop() { isRunningFlag = false }

    /**
     * Publica callbacks solo para la ejecución y el listener que los originaron.
     * Si empieza otra tarea antes de vaciar la cola principal, los callbacks
     * pendientes de la tarea anterior se descartan y no contaminan su estado.
     */
    private fun postMain(
        generation: Long,
        targetListener: ProgresoListener?,
        action: (ProgresoListener) -> Unit
    ) {
        if (targetListener == null) return
        val dispatch = {
            if (executionGeneration == generation && listener === targetListener) {
                action(targetListener)
            }
        }
        val injectedDispatcher = dispatchToMain
        if (injectedDispatcher != null) {
            injectedDispatcher(dispatch)
        } else {
            val handler = mainHandler
            if (handler != null) handler.post(dispatch) else dispatch()
        }
    }

    /**
     * Ejecuta el plan en el orden recibido. cargar/ejecutar/descargar se
     * ejecutan en el hilo trabajador del llamador; solo los callbacks UI
     * se publican en el hilo principal.
     */
    fun ejecutarModulos(
        modulos: List<ModuloIA>,
        entradaInicial: Dato
    ): List<Pair<String, Dato>> {
        val generation = synchronized(this) {
            check(!isRunningFlag) { "BARDO ya está ejecutando otro plan." }
            isRunningFlag = true
            cancelado = false
            executionGeneration += 1L
            executionGeneration
        }
        val resultados = mutableListOf<Pair<String, Dato>>()
        var moduloActual = "preparación"

        try {
            var actual = entradaInicial
            for ((index, modulo) in modulos.withIndex()) {
                moduloActual = modulo.id
                if (cancelado) {
                    val destino = listener
                    postMain(generation, destino) {
                        it.onProgreso("cancelado", 0f, "Cancelado por el usuario antes de iniciar ${modulo.id}.")
                    }
                    return resultados.toList()
                }

                val paso = "${index + 1}/${modulos.size} ${modulo.tipo.name}"
                val listenerPreparacion = listener
                postMain(generation, listenerPreparacion) {
                    it.onProgreso(paso, 0f, "Preparando ${modulo.id}…")
                }

                var cargaIntentada = false
                var falloPrincipal: Throwable? = null
                try {
                    cargaIntentada = true
                    if (!modulo.cargar()) {
                        throw IllegalStateException("El módulo ${modulo.id} no pudo cargarse; no se ejecutó.")
                    }
                    val listenerEjecucion = listener
                    postMain(generation, listenerEjecucion) {
                        it.onProgreso(paso, 0.35f, "Ejecutando ${modulo.id}…")
                    }
                    actual = modulo.ejecutar(actual)
                } catch (error: Throwable) {
                    falloPrincipal = error
                    throw error
                } finally {
                    if (cargaIntentada) {
                        try {
                            modulo.descargar()
                        } catch (errorLiberacion: Throwable) {
                            if (falloPrincipal != null) {
                                falloPrincipal.addSuppressed(errorLiberacion)
                                runCatching { Log.e(tag, "También falló la liberación de ${modulo.id}", errorLiberacion) }
                            } else {
                                throw IllegalStateException(
                                    "El módulo ${modulo.id} terminó, pero no pudo liberar sus recursos.",
                                    errorLiberacion
                                )
                            }
                        }
                    }
                }

                resultados += modulo.id to actual
                val listenerCompletado = listener
                postMain(generation, listenerCompletado) {
                    it.onProgreso(paso, 1f, "${modulo.id} completado y recursos liberados.")
                }
            }

            val copiaResultados = resultados.toList()
            val listenerFinal = listener
            postMain(generation, listenerFinal) { it.onCompletado(copiaResultados) }
            return copiaResultados
        } catch (error: Throwable) {
            val detalle = "${moduloActual}: ${error.message ?: error.javaClass.simpleName}"
            runCatching { Log.e(tag, "Falló el plan de BARDO en $detalle", error) }
            val listenerError = listener
            postMain(generation, listenerError) { it.onError(detalle) }
            throw error
        } finally {
            isRunningFlag = false
        }
    }
}
