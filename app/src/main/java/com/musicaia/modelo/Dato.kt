package com.musicaia.modelo

sealed class Dato {
    data class Texto(val texto: String) : Dato()
    data class Audio(val rutaWav: String, val frecuenciaMuestreo: Int) : Dato()
    data class Imagen(val rutaPng: String) : Dato()
}