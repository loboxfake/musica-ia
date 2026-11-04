package com.musicaia.modelo

import android.os.Handler
import android.os.Looper
import android.util.Log

class Orquestador {
    private val TAG = "Orquestador"
    private var cancelado = false
    private var isRunningFlag = false
    private var listener: ProgresoListener? = null
    private val mainHandler: Handler? = runCatching {
        Looper.getMainLooper()?.let { Handler(it) }
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

    private fun postMain(action: () -> Unit) {
        val handler = mainHandler
        if (handler != null) handler.post(action) else action()
    }

    fun ejecutarModulos(modulos: List<ModuloIA>, entradaInicial: Dato): List<Pair<String, Dato>> {
        val resultados = mutableListOf<Pair<String, Dato>>()
        isRunningFlag = true
        cancelado = false
        try {
            var actual: Dato = entradaInicial
            for ((i, modulo) in modulos.withIndex()) {
                if (cancelado) {
                    resultados += modulo.id to actual
                    postMain {
                        listener?.onProgreso("cancelado", 0f, "Cancelado por usuario")
                    }
                    return resultados
                }
                val paso = "${i + 1}/${modulos.size} ${modulo.tipo.name}"
                postMain {
                    listener?.onProgreso(paso, 0f, "Cargando ${modulo.id}...")
                }
                if (!modulo.cargar()) {
                    resultados += modulo.id to actual
                    Log.e(TAG, "Error cargando ${modulo.id}")
                    postMain {
                        listener?.onError("Error cargando ${modulo.id}")
                    }
                    return resultados
                }
                postMain {
                    listener?.onProgreso(paso, 0.5f, "Ejecutando ${modulo.id}...")
                }
                actual = modulo.ejecutar(actual)
                modulo.descargar()
                resultados += modulo.id to actual
                postMain {
                    listener?.onProgreso(paso, 0.9f, "Descargando ${modulo.id}...")
                }
            }
        } finally {
            isRunningFlag = false
        }
        postMain {
            listener?.onCompletado(resultados)
        }
        return resultados
    }
}
