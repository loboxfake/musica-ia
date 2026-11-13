package com.musicaia.modulos.voice

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Comprueba que ONNX Runtime Android puede abrir el grafo acústico.
 * Abrir una sesión NO equivale a ejecutar inferencia ni a sintetizar canto.
 */
object BardoDiffSingerOnnxProbe {
    data class Result(
        val loaded: Boolean,
        val inputNames: List<String> = emptyList(),
        val outputNames: List<String> = emptyList(),
        val detail: String
    )

    fun loadAcousticGraph(model: File, availableMemoryBytes: Long): Result {
        if (!model.isFile || model.length() <= 0L) return Result(false, detail = "El modelo acústico no existe o está vacío.")
        if (availableMemoryBytes < MIN_FREE_MEMORY_BYTES) {
            return Result(false, detail = "Memoria libre insuficiente para abrir el modelo acústico de forma preventiva.")
        }
        return try {
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                environment.createSession(model.absolutePath, options).use { session ->
                    val inputs = session.inputNames.toList().sorted()
                    val outputs = session.outputNames.toList().sorted()
                    if (inputs.isEmpty() || outputs.isEmpty()) {
                        Result(false, inputs, outputs, "El grafo se abrió, pero no declara entradas y salidas utilizables.")
                    } else {
                        Result(true, inputs, outputs, "Grafo acústico ONNX abierto y metadatos leídos. No se ha ejecutado inferencia.")
                    }
                }
            }
        } catch (error: Exception) {
            Result(false, detail = "No se pudo abrir el grafo ONNX: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    const val MIN_FREE_MEMORY_BYTES: Long = 1_500L * 1024L * 1024L
}
