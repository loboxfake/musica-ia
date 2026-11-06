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
        tamanoEsperadoBytes: Long = 497_516_544L,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        fun fallar(mensaje: String): Boolean {
            runCatching { onError?.invoke(mensaje) }
            return false
        }
        if (!confirmar) return fallar("La descarga no fue confirmada.")
        if (url.isBlank()) return fallar("La dirección de descarga está vacía.")
        if (tamanoEsperadoBytes <= 0L) return fallar("El tamaño esperado del modelo no es válido.")
        if (shaEsperado.isNullOrBlank() || !shaEsperado.matches(Regex("(?i)[0-9a-f]{64}"))) {
            return fallar("El SHA-256 esperado no tiene un formato válido.")
        }
        val padre = destino.absoluteFile.parentFile ?: return fallar("No se pudo determinar la carpeta privada de destino.")
        if (!padre.exists() && !padre.mkdirs()) return fallar("Android no pudo crear la carpeta privada del modelo.")
        if (!padre.isDirectory) return fallar("La ruta de destino no es una carpeta.")
        val espacioRequerido = tamanoEsperadoBytes + 512L * 1024L * 1024L
        val espacioDisponible = padre.usableSpace
        if (espacioDisponible < espacioRequerido) {
            val disponibleGb = espacioDisponible / (1024.0 * 1024.0 * 1024.0)
            val requeridoGb = espacioRequerido / (1024.0 * 1024.0 * 1024.0)
            return fallar("Espacio insuficiente en el almacenamiento privado: disponibles %.2f GB; necesarios aproximadamente %.2f GB.".format(disponibleGb, requeridoGb))
        }
        val parte = File(padre, destino.name + ".part")
        if (parte.length() > tamanoEsperadoBytes) return fallar("El archivo parcial supera el tamaño oficial esperado; no se ha sobrescrito.")

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BARDO-LiteRT/1.0")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val existente = parte.length()
            if (existente > 0L) connection.setRequestProperty("Range", "bytes=$existente-")
            connection.connect()
            val status = connection.responseCode
            if (status == 416) {
                if (existente == tamanoEsperadoBytes && sha256(parte).equals(shaEsperado, ignoreCase = true)) {
                    publicar(parte, destino)
                    return if (destino.isFile && destino.length() == tamanoEsperadoBytes) true
                    else fallar("El modelo se validó, pero Android no pudo publicarlo en su ubicación final.")
                }
                if (existente >= tamanoEsperadoBytes) parte.delete()
                return fallar("El servidor rechazó la reanudación (HTTP 416). Puedes volver a intentarlo desde cero.")
            }
            val inicio: Long
            val totalRemoto: Long
            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = connection.getHeaderField("Content-Range")
                        ?: return fallar("El servidor respondió HTTP 206 sin cabecera Content-Range.")
                    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(contentRange.trim())
                        ?: return fallar("El servidor devolvió un Content-Range no válido: $contentRange")
                    val startByte = match.groupValues[1].toLong()
                    val endByte = match.groupValues[2].toLong()
                    totalRemoto = match.groupValues[3].toLong()
                    if (existente <= 0L || startByte != existente || endByte < startByte ||
                        totalRemoto != tamanoEsperadoBytes || endByte >= totalRemoto
                    ) return fallar("La respuesta de reanudación no coincide con el tamaño oficial del modelo.")
                    inicio = existente
                }
                HttpURLConnection.HTTP_OK -> {
                    inicio = 0L
                    totalRemoto = connection.getHeaderFieldLong("Content-Length", -1L)
                    if (totalRemoto != tamanoEsperadoBytes) {
                        return fallar("El servidor anunció $totalRemoto bytes; se esperaban $tamanoEsperadoBytes.")
                    }
                }
                else -> return fallar("El servidor de modelos respondió HTTP $status ${connection.responseMessage.orEmpty()}.")
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
                        if (descargado + leidos > tamanoEsperadoBytes) {
                            return fallar("La descarga superó el tamaño oficial del modelo; se detuvo por seguridad.")
                        }
                        raf.write(buffer, 0, leidos)
                        descargado += leidos
                        progreso?.invoke(descargado, tamanoEsperadoBytes)
                    }
                    raf.fd.sync()
                }
            }
            if (!parte.isFile || parte.length() != tamanoEsperadoBytes) {
                return fallar("Descarga incompleta: ${parte.length()} de $tamanoEsperadoBytes bytes. El parcial se conserva para reanudar.")
            }
            if (!sha256(parte).equals(shaEsperado, ignoreCase = true)) {
                parte.delete()
                return fallar("El SHA-256 no coincide con el catálogo oficial. Se eliminó el archivo no válido.")
            }
            publicar(parte, destino)
            return if (destino.isFile && destino.length() == tamanoEsperadoBytes) true
            else fallar("El modelo se validó, pero no pudo publicarse en su ubicación final.")
        } catch (e: Exception) {
            val detalle = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return fallar("Error durante la descarga (${e.javaClass.simpleName}): $detalle")
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
