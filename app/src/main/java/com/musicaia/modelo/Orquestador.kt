package com.musicaia.modelo

import android.util.Log
import java.io.File

class Orquestador {
    private val TAG = "Orquestador"
    private var cancelado = false
    private var progresoActual = 0f
    private var listener: ProgresoListener? = null

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

    fun ejecutarModulos(modulos: List<ModuloIA>, entradaInicial: Entrada): Boolean {
        cancelado = false
        var entradaActual = entradaInicial
        var salidaFinal: Salida? = null

        for (i in modulos.indices) {
            if (cancelado) {
                listener?.onProgreso("cancelado", 0f, "Cancelado por usuario")
                return false
            }

            val modulo = modulos[i]
            val paso = "${i + 1}/${modulos.size} ${modulo.tipo.name}"

            // 1. Comprobar RAM libre
            val ramLibreMb = obtenerRamLibreMb()
            // Asumimos que cada módulo declara su ram_minima_mb en su manifiesto o constante
            val ramNecesaria = 500 // Valor por defecto si no hay manifiesto
            if (ramLibreMb < ramNecesaria) {
                val msg = "RAM insuficiente: ${ramLibreMb} MB libre, se necesitan ${ramNecesaria} MB"
                Log.e(TAG, msg)
                listener?.onError(msg)
                return false
            }

            listener?.onProgreso(paso, 0f, "Cargando modelo ${modulo.id}...")

            // 2. Cargar modelo
            val cargado = modulo.cargar()
            if (!cargado) {
                val msg = "Error cargando modelo ${modulo.id}"
                Log.e(TAG, msg)
                listener?.onError(msg)
                return false
            }

            listener?.onProgreso(paso, 0.3f, "Ejecutando ${modulo.id}...")

            // 3. Ejecutar
            val salida = try {
                modulo.ejecutar(entradaActual)
            } catch (e: Exception) {
                val msg = "Error ejecutando ${modulo.id}: ${e.message}"
                Log.e(TAG, msg, e)
                listener?.onError(msg)
                modulo.descargar()
                return false
            }

            listener?.onProgreso(paso, 0.8f, "Descargando modelo ${modulo.id}...")

            // 4. Descargar modelo antes del siguiente
            modulo.descargar()

            // 5. Preparar siguiente iteración
            salidaFinal = salida
            entradaActual = salida.comoEntrada()

            listener?.onProgreso(paso, 1f, "Paso completado")
        }

        salidaFinal?.let { listener?.onCompletado(it) }
        return true
    }

    private fun obtenerRamLibreMb(): Int {
        // En Android real: ActivityManager.getMemoryInfo()
        // Aquí devolvemos un valor fijo para pruebas
        return 2048
    }

}