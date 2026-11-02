package com.musicaia.modelo

data class ContextoProyecto(
    val letraProporcionadaPorUsuario: Boolean = false,
    val indicacionesEstilo: String = "",
    val idioma: String = "es",
    val duracionSegundos: Int = 180,
    val rutaVozReferencia: String? = null
)
