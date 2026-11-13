package com.musicaia.modulos

import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo

/** Fake determinista exclusivo de pruebas unitarias; nunca se incluye en producción. */
class ModuloLetraStub : ModuloIA {
    override val id: String = "letra_stub"
    override val tipo: TipoModulo = TipoModulo.LETRA
    override fun cargar(): Boolean = true
    override fun ejecutar(entrada: Dato): Dato = Dato.Texto("stub")
    override fun descargar() {}
}
