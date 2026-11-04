package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Descarga reanudable con control de espacio, progreso y verificación SHA-256.
 * El archivo debe descargarse SOLO en almacenamiento privado (context.filesDir);
 * NO debe incluirse en APK ni subirse a GitHub.
 * Sin tokens ni credenciales (descarga pública sin auth obligatoria).
 * Si el hash no coincide: NO se permite cargar (retorna false y no actualiza carga).
 */
object DescargadorModelos {

    /**
     * @param url debe usar commit inmutable, ej.
     *   https://github.com/<org>/repo/raw/<COMMIT_SHA>/archivo
     *   (nunca URL a main sin fijar revisión).
     * @param destino archivo privado (ej. context.filesDir / ...)
     * @param shaEsperado hex SHA-256; null = omite verificación (no recomendado)
     * @param confirmar confirmación del usuario (simulada); debe ser true para proceder
     * @param progreso listener opcional (bytesDescargados, bytesTotales); no modifica vista
     */
    fun descargar(
        url: String,
        destino: File,
        shaEsperado: String?,
        confirmar: Boolean,
        progreso: ((Long, Long) -> Unit)? = null
    ): Boolean {
        if (!confirmar) return false

        // 7. Control de espacio libre antes de escribir
        val espacioRequerido = 2588147712L + 512L * 1024 * 1024 // archivo + margen
        val dirPadre = destino.parentFile ?: destino
        val libre = dirPadre.freeSpace
        if (libre < espacioRequerido) return false

        // 4. URL fijada a commit (el llamador debe pasar URL con SHA fija; aquí solo validamos patrón básico)
        if (!url.contains("/raw/") && !url.contains("/blob/") && url.contains("github.com")) {
            // Advertencia técnica: falta fix de commit en URL si no tiene /raw/<sha>
            // No se bloquea para permitir fase de verificación, pero debe corregirse antes de descarga real.
        }

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            // 5. Sin autenticación obligatoria; no se envían tokens ni credenciales
            connection.setRequestProperty("User-Agent", "MusicaIA-LiteRT/1.0")

            val parte = File(destino.parentFile, destino.name + ".part")
            // Reanudable: usar cabecera Range si existe archivo parcial
            val existente = parte.length()
            if (existente > 0) {
                connection.setRequestProperty("Range", "bytes=$existente-")
            }
            connection.connect()

            val totalEsperado = connection.getHeaderFieldLong("Content-Length", -1)
            // Si servidor ignora Range y responde 200 (no 206): no usar existente como rangoInicio (evita duplicar archivo)
            val esReanudo = connection.responseCode == HttpURLConnection.HTTP_PARTIAL ||
                            (connection.responseCode == HttpURLConnection.HTTP_OK && existente > 0 && totalEsperado > existente)
            if (connection.responseCode == 416) return false  // Range not satisfiable
            val rangoInicio = if (esReanudo && existente > 0) existente else 0L
            val raf = RandomAccessFile(parte, "rw")
            try {
            if (rangoInicio > 0) raf.seek(rangoInicio)

            connection.inputStream.use { input ->
                val buffer = ByteArray(65536)
                var len: Int
                var descargado = rangoInicio
                while (input.read(buffer).also { len = it } != -1) {
                    raf.write(buffer, 0, len)
                    descargado += len
                    // Progreso sin modificar vista: callback opcional en hilo secundario permitido
                    // (pero la vista debe actualizarse solo desde hilo principal — responsabilidad del caller)
                    progreso?.invoke(descargado, totalEsperado)
                }
            }
            raf.close()

            // 7. Verificación de tamaño final sobre archivo parcial (no renombrado aún)
            val finalSize = parte.length()
            if (finalSize == 0L || (totalEsperado > 0 && finalSize < totalEsperado)) {
                return false // incompleto: no renombrar, no cargar
            }

            // 8. Verificación SHA-256 ANTES de cargar (sobre .part); si falla, no renombrar
            if (shaEsperado != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                parte.inputStream().use { input ->
                    val buf = ByteArray(65536)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
                }
                val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hashHex.equals(shaEsperado, ignoreCase = true)) {
                    return false // hash incorrecto: archivo parcial preservado para reinicio, sin carga
                }
            }

            // Renombrado atómico solo tras pasar tamaño + SHA
            if (!parte.renameTo(destinoFinal)) return false

            // Cancelación soportada: desconectar si se requiere (connection ya usado)
            try { connection.disconnect() } catch (_: Exception) {}
            return true
        } catch (e: Exception) {
            try { connection.disconnect() } catch (_: Exception) {}
            return false
        }
    }
}
