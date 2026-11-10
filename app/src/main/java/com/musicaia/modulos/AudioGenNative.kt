package com.musicaia.modulos

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Wrapper JNI para Stable Audio Open Small ejecutado íntegramente en el dispositivo. */
object AudioGenNative {
    val available: Boolean = runCatching {
        System.loadLibrary("audiogen")
    }.isSuccess

    private val sessionLock = ReentrantLock(true)

    /** Mantiene los pesos cargados durante todos los segmentos de una misma canción. */
    fun <T> withGenerationSession(action: () -> T): T = sessionLock.withLock(action)

    fun generate(modelsPath: String, prompt: String, outputPath: String, seed: Int): String? =
        sessionLock.withLock {
            check(available) { "El motor nativo de Stable Audio no está disponible." }
            generateNative(modelsPath, prompt, outputPath, seed)
        }

    /** Espera a que termine la generación completa antes de liberar memoria nativa. */
    fun releaseCachedModels() {
        sessionLock.withLock {
            if (available) releaseNative()
        }
    }

    private external fun generateNative(
        modelsPath: String,
        prompt: String,
        outputPath: String,
        seed: Int
    ): String?

    private external fun releaseNative()
}
