package com.musicaia.modulos.imagen

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instalador opcional y offline-first de Bonsai Image 4B (LiteRT).
 * Ningún modelo se descarga sin una acción afirmativa del usuario en la UI.
 * Los grafos y tokenizer se fijan a una revisión inmutable y se verifican por
 * tamaño + SHA-256. pipeline_meta.json se fija por revisión, tamaño y esquema.
 */
object BardoBonsaiModelInstaller {
    const val REVISION = "a15c5766bf5fe099762c81df7246185f793b150b"
    const val TOTAL_BYTES = 4_268_742_555L
    const val REQUIRED_FREE_BYTES = TOTAL_BYTES + 512L * 1024L * 1024L
    private const val BASE_URL =
        "https://huggingface.co/litert-community/Bonsai-Image-ternary-4B/resolve/$REVISION/"

    data class FileSpec(
        val path: String,
        val size: Long,
        val sha256: String?
    )

    val files = listOf(
        FileSpec("pipeline_meta.json", 6_577L, null),
        FileSpec("tokenizer/vocab.json", 2_776_833L,
            "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        FileSpec("tokenizer/merges.txt", 1_671_853L,
            "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        FileSpec("textenc_int4.tflite", 1_798_100_240L,
            "12937850e4a1670986e79663649d071f9db4095d9a74ce1cca045551447f7683"),
        FileSpec("dit_int4b32.tflite", 2_267_355_872L,
            "580c3485d2a878cd6b3c116373b7685032fd2974ae974ca04720b0b55a187646"),
        FileSpec("vae_dec_fp32.tflite", 198_831_180L,
            "96fff2562ecb0c8ffa673126d24c78f340b0b6fb794e90a3b29180aa7a23cacf")
    )

    fun modelDir(context: Context): File = File(context.filesDir, "bardo_bonsai_image")

    fun availableBytes(context: Context): Long =
        StatFs(context.filesDir.absolutePath).availableBytes

    fun requiredFreeBytes(context: Context): Long {
        val dir = modelDir(context)
        val remaining = files.sumOf { spec ->
            val target = File(dir, spec.path)
            if (target.isFile && verifyFile(target, spec)) {
                0L
            } else {
                val part = File(target.parentFile ?: dir, target.name + ".part")
                val partial = part.length().coerceIn(0L, spec.size)
                spec.size - partial
            }
        }
        return remaining + 512L * 1024L * 1024L
    }

    fun hasEnoughStorage(context: Context): Boolean =
        availableBytes(context) >= requiredFreeBytes(context)

    fun isInstalled(context: Context): Boolean =
        isVerifiedInstall(modelDir(context), REVISION, files)

    /** Marker de instalación ligado a tamaño y fecha de modificación de cada archivo verificado. */
    internal fun writeVerifiedMarker(directory: File, revision: String, specs: List<FileSpec>) {
        require(directory.isDirectory) { "El directorio privado del modelo no existe." }
        val entries = JSONObject()
        specs.forEach { spec ->
            val file = File(directory, spec.path)
            require(file.isFile && file.length() == spec.size) {
                "No se puede marcar como verificado un archivo incompleto: ${spec.path}"
            }
            entries.put(
                spec.path,
                JSONObject()
                    .put("size", file.length())
                    .put("last_modified", file.lastModified())
            )
        }
        val marker = JSONObject()
            .put("revision", revision)
            .put("files", entries)
        val temporary = File.createTempFile("bardo_verified_", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(marker.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            activateVerifiedPart(temporary, File(directory, ".verified"))
        } finally {
            temporary.delete()
        }
    }

    internal fun isVerifiedInstall(directory: File, revision: String, specs: List<FileSpec>): Boolean {
        val markerFile = File(directory, ".verified")
        if (!markerFile.isFile) return false
        val marker = runCatching { JSONObject(markerFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return false
        if (marker.optString("revision") != revision) return false
        val entries = marker.optJSONObject("files") ?: return false
        return specs.all { spec ->
            val file = File(directory, spec.path)
            val recorded = entries.optJSONObject(spec.path) ?: return@all false
            file.isFile &&
                file.length() == spec.size &&
                recorded.optLong("size", -1L) == file.length() &&
                recorded.optLong("last_modified", -1L) == file.lastModified()
        }
    }

    private fun activateVerifiedPart(part: File, target: File) {
        try {
            Files.move(
                part.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun download(
        context: Context,
        cancelled: AtomicBoolean,
        progress: (fileName: String, downloaded: Long, total: Long) -> Unit
    ): File {
        val appContext = context.applicationContext
        val dir = modelDir(appContext)
        if (!dir.exists() && !dir.mkdirs()) error("No se pudo crear el directorio privado del modelo.")
        if (!hasEnoughStorage(appContext)) {
            error("Se necesitan al menos %.2f GB libres para los archivos que faltan y margen temporal."
                .format(requiredFreeBytes(appContext) / 1_000_000_000.0))
        }

        var completed = 0L
        files.forEach { spec ->
            checkCancelled(cancelled)
            val target = File(dir, spec.path)
            if (target.isFile && verifyFile(target, spec)) {
                completed += spec.size
                progress(spec.path, completed, TOTAL_BYTES)
                return@forEach
            }
            downloadOne(spec, target, cancelled) { current ->
                progress(spec.path, completed + current, TOTAL_BYTES)
            }
            completed += spec.size
            progress(spec.path, completed, TOTAL_BYTES)
        }

        // Revalidar el conjunto completo antes de marcarlo instalado.
        files.forEach { spec ->
            val target = File(dir, spec.path)
            check(target.isFile && verifyFile(target, spec)) {
                "Verificación fallida: ${spec.path}"
            }
        }
        writeVerifiedMarker(dir, REVISION, files)
        check(isInstalled(appContext)) { "El conjunto del modelo no superó la verificación final." }
        return dir
    }

    private fun downloadOne(
        spec: FileSpec,
        target: File,
        cancelled: AtomicBoolean,
        onProgress: (Long) -> Unit
    ) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("No se pudo crear ${it.absolutePath}") }
        val part = File(target.parentFile, target.name + ".part")
        if (part.length() > spec.size) part.delete()
        if (part.length() == spec.size && verifyFile(part, spec)) {
            activateVerifiedPart(part, target)
            return
        }
        if (part.length() == spec.size) part.delete()

        var offset = part.length()
        val connection = (URL(BASE_URL + spec.path + "?download=true").openConnection() as HttpURLConnection)
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "BARDO-Android")
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            val response = connection.responseCode
            val append = offset > 0L && response == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                check(contentRange.startsWith("bytes $offset-")) {
                    "El servidor devolvió un rango de descarga inesperado para ${spec.path}."
                }
            } else {
                check(response == HttpURLConnection.HTTP_OK) {
                    "HTTP $response al descargar ${spec.path}"
                }
                if (offset > 0L) {
                    part.delete()
                    offset = 0L
                }
            }

            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        checkCancelled(cancelled)
                        if (count == 0) continue
                        check(part.length() + count <= spec.size) {
                            "La descarga excede el tamaño fijado de ${spec.path}."
                        }
                        output.write(buffer, 0, count)
                        onProgress(part.length())
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        check(part.length() == spec.size) {
            "Descarga incompleta de ${spec.path}: ${part.length()} / ${spec.size} bytes. Puedes reintentar y continuar."
        }
        check(verifyFile(part, spec)) {
            part.delete()
            "SHA-256 o estructura incorrecta en ${spec.path}; se ha eliminado el archivo parcial."
        }
        activateVerifiedPart(part, target)
    }

    private fun verifyFile(file: File, spec: FileSpec): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        if (spec.sha256 != null && sha256(file) != spec.sha256) return false
        if (spec.path == "pipeline_meta.json") {
            return runCatching {
                val meta = JSONObject(file.readText())
                val names = meta.getJSONObject("files")
                names.getString("dit") == "dit_int4b32.tflite" &&
                    names.getString("textenc") == "textenc_int4.tflite" &&
                    names.getString("vae") == "vae_dec_fp32.tflite" &&
                    meta.getJSONArray("latent_bn_scale").length() == 128 &&
                    meta.getJSONArray("latent_bn_shift").length() == 128
            }.getOrDefault(false)
        }
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Descarga del modelo cancelada; los fragmentos verificados se conservan para reanudar.")
        }
    }
}
