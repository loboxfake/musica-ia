package com.musicaia.modulos.litert

/**
 * Hito 1 — LiteRT-LM + Gemma 4 E2B (independiente, no sustituye módulos actuales).
 * Alcance: conversación real + generación de letras (texto), NO instrumentales ni canto.
 * Backend: CPU primero (verificado); GPU Adreno 660 investigado sin garantizar compatibilidad.
 * Modelo descargado solo en almacenamiento privado (context.filesDir), no en APK.
 */
object LiteRTLMModule {
    const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    // Commit debe fijarse tras verificación del repositorio (actualmente no verificado: 404 en API)
    const val COMMIT_SHA = "6e5c4f1e395deb959c494953478fa5cec4b8008f" // verificado por HF API
    const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    const val EXPECTED_SIZE_BYTES = 2588147712L
    const val EXPECTED_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    // Estado de verificación técnica (medido, no estimado)
    // repoVerified = verificación de EJECUCIÓN (carga+hash+CPU), NO solo metadatos HF.
    // Metadatos HF (commit 6e5c4f1..., archivo, tamaño, LFS hash) ya verificados con evidencia.
    // Ejecución pendiente: Xiaomi 11T Pro + compilación con JDK.
    var repoVerified: Boolean = false
    var fileDownloaded: Boolean = false
    var shaVerified: Boolean = false
    var cpuBackendTested: Boolean = false
    var gpuAdreno660Compatible: Boolean = false // investigado, NO garantizado

    fun canLoad(): Boolean = fileDownloaded && shaVerified && cpuBackendTested
}
