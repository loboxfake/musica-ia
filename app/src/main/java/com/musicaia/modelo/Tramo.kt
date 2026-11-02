package com.musicaia.modelo

/**
 * Representa un tramo de canción (segmento de 60-90 s) que se enlaza con fundidos.
 * Fase 4: canciones de 3 min o más por tramos enlazados.
 */
data class Tramo(
    val id: String,
    val rutaWav: String,
    val duracionSegundos: Int = 90,
    val fundidoEntradaMs: Int = 500,
    val fundidoSalidaMs: Int = 500
)

fun enlazarTramos(tramos: List<Tramo>): List<Tramo> {
    // Enlace básico: tramos secuenciales con fundidos; en producción usar mezcla de audio real.
    return tramos.mapIndexed { index, tramo ->
        tramo.copy(
            rutaWav = tramo.rutaWav.replace(".wav", "_enlazado_$index.wav")
        )
    }
}
