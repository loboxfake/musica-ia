package com.musicaia.modelo

import android.util.Log

class Orquestador {
    private val TAG = "Orquestador"
    private var cancelado = false
    private var listener: ProgresoListener? = null

    val isRunning: Boolean get() = cancelado == false // simplificado para el ejemplo

    interface ProgresoListener {
        fun onProgreso(paso: String, progreso: Float, mensaje: String)
        fun onCompletado(resultado: Salida)
        fun onError(mensaje: String)
    }

    fun setListener(listener: ProgresoListener) {
        this.listener = listener
    }

    fun cancelar() {
        cancelado = true
    }

    fun ejecutarModulos(modulos: List<ModuloIA>, entradaInicial: Entrada): Salida? {
        cancelado = false
        var entradaActual: Entrada = entradaInicial
        var salidaFinal: Salida? = null

        for (i in modulos.indices) {
            if (cancelado) {
                listener?.onProgreso("cancelado", 0f, "Cancelado por usuario")
                return salidaFinal
            }

            val modulo = modulos[i]
            val paso = "${i + 1}/${modulos.size} ${modulo.tipo.name}"

            val ramLibreMb = obtenerRamLibreMb()
            val ramNecesaria = 500
            if (ramLibreMb < ramNecesaria) {
                val msg = "RAM insuficiente: ${ramLibreMb} MB libre, se necesitan ${ramNecesaria} MB"
                Log.e(TAG, msg)
                listener?.onError(msg)
                return salidaFinal
            }

            listener?.onProgreso(paso, 0f, "Cargando modelo ${modulo.id}...")

            val cargado = modulo.cargar()
            if (!cargado) {
                val msg = "Error cargando modelo ${modulo.id}"
                Log.e(TAG, msg)
                listener?.onError(msg)
                return salidaFinal
            }

            listener?.onProgreso(paso, 0.3f, "Ejecutando ${modulo.id}...")

            val salida: Salida = try {
                modulo.ejecutar(entradaActual)
            } catch (e: Exception) {
                val msg = "Error ejecutando ${modulo.id}: ${e.message}"
                Log.e(TAG, msg, e)
                listener?.onError(msg)
                modulo.descargar()
                return salidaFinal
            }

            listener?.onProgreso(paso, 0.8f, "Descargando modelo ${modulo.id}...")
            modulo.descargar()

            salidaFinal = salida
            entradaActual = salida.comoEntrada()

            listener?.onProgreso(paso, 1f, "Paso completado")
        }

        salidaFinal?.let { listener?.onCompletado(it) }
        return salidaFinal
    }

    private fun obtenerRamLibreMb(): Int = 2048
}