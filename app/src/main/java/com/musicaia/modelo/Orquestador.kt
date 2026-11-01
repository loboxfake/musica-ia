package com.musicaia.modelo

import android.util.Log

class Orquestador {
    private val TAG = "Orquestador"
    private var cancelado = false
    private var isRunningFlag = false
    private var listener: ProgresoListener? = null

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

    fun ejecutarModulos(modulos: List<ModuloIA>, entradaInicial: Dato): List<Pair<String, Dato>> {
        val resultados = mutableListOf<Pair<String, Dato>>()
        isRunningFlag = true
        cancelado = false
        try {
            var actual: Dato = entradaInicial
            for ((i, modulo) in modulos.withIndex()) {
                if (cancelado) {
                    resultados += modulo.id to actual
                    listener?.onProgreso("cancelado", 0f, "Cancelado por usuario")
                    return resultados
                }
                val paso = "${i + 1}/${modulos.size} ${modulo.tipo.name}"
                listener?.onProgreso(paso, 0f, "Cargando ${modulo.id}...")
                if (!modulo.cargar()) {
                    resultados += modulo.id to actual
                    Log.e(TAG, "Error cargando ${modulo.id}")
                    listener?.onError("Error cargando ${modulo.id}")
                    return resultados
                }
                listener?.onProgreso(paso, 0.5f, "Ejecutando ${modulo.id}...")
                actual = modulo.ejecutar(actual)
                modulo.descargar()
                resultados += modulo.id to actual
                listener?.onProgreso(paso, 0.9f, "Descargando ${modulo.id}...")
            }
        } finally {
            isRunningFlag = false
        }
        listener?.onCompletado(resultados)
        return resultados
    }
}