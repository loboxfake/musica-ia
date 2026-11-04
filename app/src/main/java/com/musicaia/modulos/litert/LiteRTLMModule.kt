package com.musicaia.modulos.litert

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Modelo local de texto. La inferencia se ejecuta en el teléfono. */
object LiteRTLMModule {
    const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val COMMIT_SHA = "6e5c4f1e395deb959c494953478fa5cec4b8008f"
    const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    const val EXPECTED_SIZE_BYTES = 2_588_147_712L
    const val EXPECTED_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c1"
    const val MODEL_URL =
        "https://huggingface.co/$REPO/resolve/$COMMIT_SHA/$MODEL_FILENAME"

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
