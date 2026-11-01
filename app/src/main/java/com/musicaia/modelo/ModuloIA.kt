package com.musicaia.modelo

interface ModuloIA {
    val id: String
    val tipo: TipoModulo

    fun cargar(): Boolean
    fun ejecutar(entrada: Salida): Entrada
    fun descargar(): Unit
}