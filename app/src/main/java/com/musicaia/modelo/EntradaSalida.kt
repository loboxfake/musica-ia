package com.musicaia.modelo

sealed class Entrada {
    data class Texto(val texto: String) : Entrada()
    data class Audio(val rutaWav: String, val frecuenciaMuestreo: Int) : Entrada()
    data class Imagen(val rutaPng: String) : Entrada()
}

sealed class Salida {
    data class Texto(val texto: String) : Salida()
    data class Audio(val rutaWav: String, val frecuenciaMuestreo: Int) : Salida()
    data class Imagen(val rutaPng: String) : Salida()
}
fun Salida.comoEntrada(): Entrada = when (this) {
    is Salida.Texto -> Entrada.Texto(this.texto)
    is Salida.Audio -> Entrada.Audio(this.rutaWav, this.frecuenciaMuestreo)
    is Salida.Imagen -> Entrada.Imagen(this.rutaPng)
}
