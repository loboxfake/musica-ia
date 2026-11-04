package com.musicaia.modulos.litert

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Modelo local de texto. La inferencia se ejecuta en el teléfono. */
object LiteRTLMModule {
    const val REPO = "litert-community/Qwen3-0.6B"
    const val COMMIT_SHA = "154c5de0fc0fc5002a7da570ba8c67edffa32ca2"
    const val MODEL_FILENAME = "qwen3_0_6b_mixed_int4.litertlm"
    const val EXPECTED_SIZE_BYTES = 497_516_544L
    const val EXPECTED_SHA256 = "7900eb4e7362d88c58782c6f9999bb7a129e03544aa98b8f338ea0cc5d8c22c1"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/$COMMIT_SHA/$MODEL_FILENAME"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILENAME)

    fun verificarModelo(file: File): Boolean {
        if (!file.isFile || file.length() != EXPECTED_SIZE_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            file.delete()
            return false
        }
        return true
    }
}
