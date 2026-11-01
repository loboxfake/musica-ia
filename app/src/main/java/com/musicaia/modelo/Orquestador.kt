package com.musicaia.modelo

import android.util.Log

class Orquestador {
    private val TAG = "Orquestador"
    private var cancelado = false
    private var listener: ProgresoListener? = null

    val isRunning: Boolean get() = !cancelado

    interface ProgresoListener {
        fun onProgreso(paso: String, progreso: Float, mensaje: String)
        fun onCompletado(resultado: Dato)
        fun onError(mensaje: String)
    }

    fun setListener(listener: ProgresoListener) {
        this.listener = listener
    }

    fun cancelar() {
        cancelado = true
    }

    fun ejecutarModulos(modulos: List<ModuloIA>, entradaInicial: Dato): Dato? {
        cancelado = false
        var actual: Dato = entradaInicial
        for ((i, modulo) in modulos.withIndex()) {
            if (cancelado) {
                listener?.onProgreso("cancelado", 0f, "Cancelado por usuario")
                return actual
            }
            val paso = "${i + 1}/${modulos.size} ${modulo.tipo.name}"
            listener?.onProgreso(paso, 0f, "Cargando modelo ${modulo.id}...")
            val cargado = modulo.cargar()
            if (!cargado) {
                Log.e(TAG, "Error cargando ${modulo.id}")
                listener?.onError("Error cargando ${modulo.id}")
                return actual
            }
            listener?.onProgreso(paso, 0.5f, "Ejecutando ${modulo.id}...")
            actual = modulo.ejecutar(actual)
            listener?.onProgreso(paso, 0.9f, "Descargando ${modulo.id}...")
            modulo.descargar()
            listener?.onProgreso(paso, 1f, "Paso completado")
        }
        listener?.onCompletado(actual)
        return actual
    }

    private fun obtenerRamLibreMb(): Int = 2048
}