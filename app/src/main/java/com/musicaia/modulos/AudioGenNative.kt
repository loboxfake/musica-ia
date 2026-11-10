package com.musicaia.modulos

/** Wrapper JNI para Stable Audio Open Small ejecutado íntegramente en el dispositivo. */
object AudioGenNative {
    val available: Boolean = runCatching {
        System.loadLibrary("audiogen")
    }.isSuccess

    external fun generateNative(
        modelsPath: String,
        prompt: String,
        outputPath: String,
        seed: Int
    ): String?

    /** Releases the cached ExecuTorch modules after a complete song/task. */
    external fun releaseNative()
}
