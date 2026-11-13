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
            require(urlObj.protocol.equals("https", ignoreCase = true)) {
                "El catálogo de modelos solo admite HTTPS."
            }
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                conn.connect()
                require(conn.responseCode == HttpURLConnection.HTTP_OK) {
                    "El catálogo respondió HTTP ${conn.responseCode}."
                }
                val json = conn.inputStream.bufferedReader().useLines { lines -> lines.joinToString("\n") }
                val array = JSONArray(json)
                val cargados = mutableListOf<ManifiestoModelo>()
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
                    atribucion = obj.optString("atribucion", ""),
                    usoComercial = obj.getBoolean("uso_comercial"),
                    urlDescarga = obj.getString("url_descarga"),
                    sha256 = obj.getString("sha256"),
                    tiempo_estimado_s = obj.optInt("tiempo_estimado_s", 10),
                    motor = obj.getString("motor")
                )
                require(modelo.sha256.matches(Regex("(?i)[0-9a-f]{64}"))) {
                    "El modelo ${modelo.id} no declara un SHA-256 válido."
                }
                require(modelo.licencia.isNotBlank() && modelo.atribucion.isNotBlank()) {
                    "El modelo ${modelo.id} debe declarar licencia y atribución."
                }
                cargados += modelo
                Log.d(TAG, "Validado modelo ${modelo.id}")
                }
                synchronized(modelos) {
                    modelos.clear()
                    cargados.forEach { modelos[it.id] = it }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando catálogo", e)
            throw IllegalStateException("No se pudo cargar el catálogo verificado: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    fun obtenerModelo(id: String): ManifiestoModelo? = synchronized(modelos) { modelos[id] }

    fun listarModelos(): List<ManifiestoModelo> = synchronized(modelos) { modelos.values.toList() }

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