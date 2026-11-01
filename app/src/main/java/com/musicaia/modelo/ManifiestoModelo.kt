package com.musicaia.modelo

import org.json.JSONObject

class ManifiestoModelo(
    val id: String,
    val tipo: TipoModulo,
    val nombre: String,
    val version: String,
    val tamanoMb: Double,
    val ramMinimaMb: Int,
    val licencia: String,
    val usoComercial: Boolean,
    val urlDescarga: String,
    val sha256: String,
    val tiempo_estimado_s: Int = 10,
    val motor: String // llamacpp, onnx o executorch
) {
    fun aJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("tipo", tipo.name)
            put("nombre", nombre)
            put("version", version)
            put("tamano_mb", tamanoMb)
            put("ram_minima_mb", ramMinimaMb)
            put("licencia", licencia)
            put("uso_comercial", usoComercial)
            put("url_descarga", urlDescarga)
            put("sha256", sha256)
            put("tiempo_estimado_s", 2)
            put("motor", motor)
        }
    }
}