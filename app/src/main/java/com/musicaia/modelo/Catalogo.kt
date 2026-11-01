package com.musicaia.modelo

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

    // VERIFICACIÓN REAL SHA-256: lee archivo por bloques, compara y borra si no coincide
    fun verificarSha256(archivo: File, sha256Esperado: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        archivo.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var leidos: Int
            while (input.read(buffer).also { leidos = it } != -1) {
                digest.update(buffer, 0, leidos)
            }
        }
        val resultado = digest.digest().joinToString("") { String.format("%02x", it) }
        val coincide = resultado.equals(sha256Esperado, ignoreCase = true)
        if (!coincide) {
            archivo.delete()
        }
        return coincide
    }
}