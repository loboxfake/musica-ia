package com.musicaia.modelo

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class Catalogo {
    private val TAG = "Catalogo"
    private val modelos = mutableMapOf<String, ManifiestoModelo>()

    fun cargarDesdeUrl(url: String) {
        try {
            val urlObj = URL(url)
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.connect()
            val stream: InputStream = conn.inputStream
            val json = String(conn.inputStream.bufferedReader().useLines().toList().joinToString("\n"))
            conn.disconnect()

            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val modelo = ManifiestoModelo(
                    id = obj.getString("id"),
                    tipo = TipoModulo.valueOf(obj.getString("tipo")),
                    nombre = obj.getString("nombre"),
                    version = obj.getString("version"),
                    tamanoMb = obj.getDouble("tamano_mb"),
                    ramMinimaMb = obj.getInt("ram_minima_mb"),
                    licencia = obj.getString("licencia"),
                    usoComercial = obj.getBoolean("uso_comercial"),
                    urlDescarga = obj.getString("url_descarga"),
                    sha256 = obj.getString("sha256"),
                    motor = obj.getString("motor")
                )
                modelos[modelo.id] = modelo
                Log.d(TAG, "Cargado modelo ${modelo.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando catálogo", e)
        }
    }

    fun obtenerModelo(id: String): ManifiestoModelo? {
        return modelos[id]
    }

    fun listarModelos(): List<ManifiestoModelo> {
        return modelos.values.toList()
    }
}