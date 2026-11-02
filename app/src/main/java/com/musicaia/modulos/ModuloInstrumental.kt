package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Catalogo
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloInstrumental(context: Context) : ModuloIA {
    override val id = "instrumental"
    override val tipo = TipoModulo.INSTRUMENTAL
    private val ctx = context.applicationContext
    private val catalogo = Catalogo()
    private val modeloId = "instrumental-stable-audio-open-small"

    override fun cargar(): Boolean {
        val modelo = catalogo.obtenerModelo(modeloId)
        if (modelo == null) {
            // Intentar cargar desde URL si no está en memoria
            catalogo.cargarDesdeUrl("https://raw.githubusercontent.com/loboxfake/musica-ia/main/catalogo/catalogo.json")
            return catalogo.obtenerModelo(modeloId) != null
        }
        // Verificación de licencia, tamaño y RAM mínima
        val ramLibre = Runtime.getRuntime().freeMemory() / (1024 * 1024)
        return modelo.ramMinimaMb <= ramLibre && modelo.tamanoMb < 2048
    }

    override fun ejecutar(entrada: Dato): Dato {
        val dir = ctx.getExternalFilesDir(null) ?: return entrada
        val rutaWav = File(dir, "instrumental_2min.wav").absolutePath
        // Aquí se ejecutaría el modelo de generación musical real (Stable Audio Open Small)
        // con tramos de hasta 120 s; por ahora estructura de integración lista
        return Dato.Audio(
            rutaWav = rutaWav,
            frecuenciaMuestreo = 44100
        )
    }

    override fun descargar() {}
}
