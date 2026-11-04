package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Descarga reanudable para modelos grandes.
 *
 * El destino debe estar en almacenamiento privado (preferiblemente context.filesDir).
 * Nunca incluye modelos en el APK ni envía credenciales.
 * Solo publica el archivo final después de validar tamaño y SHA-256.
 */
object DescargadorModelos {

    fun descargar(
        url: String,
        destino: File,
        shaEsperado: String?,
        confirmar: Boolean,
        progreso: ((Long, Long) -> Unit)? = null,
        tamanoEsperadoBytes: Long = 2_588_147_712L
    ): Boolean {
        if (!confirmar || url.isBlank() || tamanoEsperadoBytes <= 0L) return false
        if (shaEsperado.isNullOrBlank() || !shaEsperado.matches(Regex("(?i)[0-9a-f]{64}"))) {
            return false
        }

        val padre = destino.absoluteFile.parentFile ?: return false
        if (!padre.exists() && !padre.mkdirs()) return false
        if (!padre.isDirectory) return false

        // Se reserva el tamaño completo más 512 MiB para temporales/runtime.
        val espacioRequerido = tamanoEsperadoBytes + 512L * 1024L * 1024L
        if (padre.usableSpace < espacioRequerido) return false

        val parte = File(padre, destino.name + ".part")
        if (parte.length() > tamanoEsperadoBytes) return false

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MusicaIA-LiteRT/1.0")
                setRequestProperty("Accept-Encoding", "identity")
            }

            val existente = parte.length()
            if (existente > 0L) {
                connection.setRequestProperty("Range", "bytes=$existente-")
            }
            connection.connect()

            val status = connection.responseCode
            if (status == 416) {
                // Si el parcial ya tiene el tamaño completo, solo se acepta si el SHA coincide.
                if (existente == tamanoEsperadoBytes && sha256(parte).equals(shaEsperado, ignoreCase = true)) {
                    publicar(parte, destino)
                    return destino.isFile && destino.length() == tamanoEsperadoBytes
                }
                if (existente >= tamanoEsperadoBytes) parte.delete()
                return false
            }

            val inicio: Long
            val totalRemoto: Long
            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = connection.getHeaderField("Content-Range")
                        ?: return false
                    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(contentRange.trim())
                        ?: return false
                    val start = match.groupValues[1].toLong()
                    val end = match.groupValues[2].toLong()
                    totalRemoto = match.groupValues[3].toLong()
                    if (existente <= 0L || start != existente || end < start ||
                        totalRemoto != tamanoEsperadoBytes || end >= totalRemoto
                    ) return false
                    inicio = existente
                }
                HttpURLConnection.HTTP_OK -> {
                    // El servidor ignoró Range: empezar desde cero, nunca anexar una copia completa.
                    inicio = 0L
                    totalRemoto = connection.getHeaderFieldLong("Content-Length", -1L)
                    if (totalRemoto != tamanoEsperadoBytes) return false
                }
                else -> return false
            }

            RandomAccessFile(parte, "rw").use { raf ->
                if (inicio == 0L) raf.setLength(0L) else raf.seek(inicio)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var descargado = inicio
                    while (true) {
                        val leidos = input.read(buffer)
                        if (leidos < 0) break
                        if (leidos == 0) continue
                        if (descargado + leidos > tamanoEsperadoBytes) return false
                        raf.write(buffer, 0, leidos)
                        descargado += leidos
                        progreso?.invoke(descargado, tamanoEsperadoBytes)
                    }
                    raf.fd.sync()
                }
            }

            if (!parte.isFile || parte.length() != tamanoEsperadoBytes) return false

            if (!sha256(parte).equals(shaEsperado, ignoreCase = true)) {
                // Un parcial completo con hash incorrecto no se puede reanudar de forma segura.
                parte.delete()
                return false
            }

            publicar(parte, destino)
            return destino.isFile && destino.length() == tamanoEsperadoBytes
        } catch (_: Exception) {
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(archivo: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        archivo.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val leidos = input.read(buffer)
                if (leidos < 0) break
                if (leidos > 0) digest.update(buffer, 0, leidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun publicar(parte: File, destino: File) {
        // Mismo directorio: sustituye el destino solo después de validar el parcial.
        try {
            Files.move(
                parte.toPath(),
                destino.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(parte.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
