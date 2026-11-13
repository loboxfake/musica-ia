package com.musicaia.modulos.litert

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Manifiesto fijado para el modelo local Qwen2-0.5B-Instruct convertido a LiteRT-LM. */
object BardoQwen2LiteRtModule {
    const val REPOSITORY = "litert-community/Qwen2-0.5B-Instruct"
    const val REVISION = "0e209e1"
    const val FILE_NAME = "Qwen2_0.5B_Instruct.litertlm"
    const val EXPECTED_SIZE_BYTES = 647_377_840L
    const val EXPECTED_SHA256 = "0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157"
    const val LICENSE = "Apache-2.0"
    const val DOWNLOAD_URL =
        "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILE_NAME"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), FILE_NAME)

    fun verificarModelo(file: File): Boolean {
        if (!file.isFile || file.length() != EXPECTED_SIZE_BYTES) return false
        return runCatching { sha256(file).equals(EXPECTED_SHA256, ignoreCase = true) }
            .getOrDefault(false)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
