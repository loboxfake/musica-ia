package com.musicaia modelo

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