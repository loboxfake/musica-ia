package com.musicaia.modulos.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File

/**
 * Runtime CPU local del G2P español de referencia.
 *
 * Solo abre un ONNX aportado por el usuario/gestor de paquetes. Esta clase no
 * descarga pesos, no declara su licencia y no se conecta a ningún servicio.
 */
class BardoSpanishG2pOnnxEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession
) : Closeable {

    data class OpenResult(val engine: BardoSpanishG2pOnnxEngine?, val detail: String) {
        val isLoaded: Boolean get() = engine != null
    }

    data class Prediction(
        val phonemes: List<String>,
        val outputTokenIds: IntArray = intArrayOf(),
        val unsupportedGraphemes: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
        val inferenceSteps: Int = 0
    ) {
        val isComplete: Boolean get() = errors.isEmpty() && unsupportedGraphemes.isEmpty() && phonemes.isNotEmpty()
    }

    @Synchronized
    fun predictWord(word: String): Prediction {
        val encoded = BardoSpanishG2pCodec.encodeWord(word)
        if (!encoded.isComplete) {
            return Prediction(
                phonemes = emptyList(),
                unsupportedGraphemes = encoded.unsupportedGraphemes,
                errors = if (encoded.unsupportedGraphemes.isEmpty()) {
                    listOf("La palabra no se pudo codificar para el G2P español.")
                } else emptyList()
            )
        }

        val decoded = BardoSpanishG2pAutoregressiveDecoder.decode(encoded.tokenIds) { src, tgt, sourceIndex ->
            inferNextToken(src, tgt, sourceIndex)
        }
        return Prediction(
            phonemes = decoded.phonemes,
            outputTokenIds = decoded.outputTokenIds,
            errors = decoded.errors,
            inferenceSteps = decoded.steps
        )
    }

    private fun inferNextToken(sourceTokens: IntArray, targetTokens: IntArray, sourceIndex: Int): Int {
        OnnxTensor.createTensor(environment, arrayOf(sourceTokens)).use { srcTensor ->
            OnnxTensor.createTensor(environment, arrayOf(targetTokens)).use { tgtTensor ->
                OnnxTensor.createTensor(environment, intArrayOf(sourceIndex)).use { stepTensor ->
                    val inputs = mapOf(
                        BardoSpanishG2pCodec.SRC_INPUT_NAME to srcTensor,
                        BardoSpanishG2pCodec.TGT_INPUT_NAME to tgtTensor,
                        BardoSpanishG2pCodec.STEP_INPUT_NAME to stepTensor
                    )
                    session.run(inputs).use { outputs ->
                        if (outputs.size() < 1) {
                            throw IllegalStateException("El modelo G2P no devolvió salidas.")
                        }
                        val first = outputs.get(0) as? OnnxTensor
                            ?: throw IllegalStateException("La primera salida G2P no es un tensor.")
                        val values = first.getIntBuffer()
                            ?: throw IllegalStateException("La salida G2P no es un tensor int32.")
                        if (values.remaining() < 1) {
                            throw IllegalStateException("La salida G2P está vacía.")
                        }
                        values.get(0)
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(this) {
            session.close()
        }
    }

    companion object {
        const val MIN_FREE_MEMORY_BYTES = 256L * 1024L * 1024L
        const val MAX_MODEL_BYTES = 64L * 1024L * 1024L
        private val REQUIRED_INPUTS = setOf(
            BardoSpanishG2pCodec.SRC_INPUT_NAME,
            BardoSpanishG2pCodec.TGT_INPUT_NAME,
            BardoSpanishG2pCodec.STEP_INPUT_NAME
        )

        fun open(model: File, availableMemoryBytes: Long): OpenResult {
            if (!model.isFile || model.length() <= 0L) {
                return OpenResult(null, "No existe el modelo G2P ONNX o está vacío.")
            }
            if (!model.extension.equals("onnx", ignoreCase = true)) {
                return OpenResult(null, "El modelo G2P debe ser un archivo .onnx.")
            }
            if (model.length() > MAX_MODEL_BYTES) {
                return OpenResult(null, "El modelo G2P supera el límite preventivo de 64 MiB.")
            }
            if (availableMemoryBytes < MIN_FREE_MEMORY_BYTES) {
                return OpenResult(null, "Memoria disponible insuficiente para abrir el modelo G2P.")
            }

            var createdSession: OrtSession? = null
            return try {
                val environment = OrtEnvironment.getEnvironment()
                val openedSession = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    options.setInterOpNumThreads(1)
                    environment.createSession(model.absolutePath, options)
                }
                createdSession = openedSession
                val missingInputs = REQUIRED_INPUTS - openedSession.inputNames
                if (missingInputs.isNotEmpty() || openedSession.outputNames.isEmpty()) {
                    openedSession.close()
                    createdSession = null
                    OpenResult(
                        null,
                        "El grafo G2P no cumple el contrato ONNX. Entradas ausentes: ${missingInputs.joinToString()}."
                    )
                } else {
                    OpenResult(
                        BardoSpanishG2pOnnxEngine(environment, openedSession),
                        "Sesión G2P ONNX abierta en CPU; metadatos de entrada/salida comprobados. Aún no se ha ejecutado inferencia."
                    )
                }
            } catch (error: Exception) {
                runCatching { createdSession?.close() }
                OpenResult(
                    null,
                    "No se pudo abrir el modelo G2P ONNX: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            }
        }
    }
}
