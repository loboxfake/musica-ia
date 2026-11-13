package com.musicaia

import com.musicaia.modelo.Catalogo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CatalogoShaTest {
    @Test
    fun testVerificarSha256Real() {
        val catalogo = Catalogo()
        val archivo = File.createTempFile("test", ".bin")
        archivo.writeText("contenido de prueba")
        // SHA-256 de "contenido de prueba"
        val shaReal = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        // Este archivo no coincide con ese hash vacío, debe borrarlo y devolver false
        val coincide = catalogo.verificarSha256(archivo, shaReal)
        assertFalse(coincide)
        assertFalse(archivo.exists()) // borrado porque no coincide
    }
}