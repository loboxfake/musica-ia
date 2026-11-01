package com.musicaia.modelo

interface ModuloIA {
    val id: String
    val tipo: TipoModulo

    fun cargar(): Boolean
    fun ejecutar(entrada: Dato): Dato
    fun descargar(): Unit
}