package com.musicaia.modulos.voice

/**
 * Decodificador greedy común al G2P español de OpenUtau.
 *
 * El callback representa un único paso del modelo ONNX; esta clase mantiene
 * tgt y t, limita la salida y convierte los IDs en fonemas. Puede probarse sin
 * cargar pesos ni inicializar el runtime nativo.
 */
object BardoSpanishG2pAutoregressiveDecoder {
    data class Result(
        val phonemes: List<String>,
        val outputTokenIds: IntArray,
        val steps: Int,
        val processedSourceTokens: Int,
        val errors: List<String>
    ) {
        val isComplete: Boolean
            get() = errors.isEmpty() && processedSourceTokens > 0 && phonemes.isNotEmpty()
    }

    /**
     * [inferStep] receives src [1,n], tgt [1,m] and the current source index t.
     * It returns one int32 token. Token 2 advances t; other valid output IDs
     * append a phoneme to tgt.
     */
    fun decode(
        sourceTokens: IntArray,
        inferStep: (src: IntArray, tgt: IntArray, sourceIndex: Int) -> Int
    ): Result {
        if (sourceTokens.isEmpty()) return failure("La entrada G2P está vacía.")
        if (sourceTokens.size > MAX_SOURCE_TOKENS) {
            return failure("La palabra supera el límite de $MAX_SOURCE_TOKENS grafemas.")
        }
        if (sourceTokens.any { it !in BardoSpanishG2pCodec.FIRST_GRAPHEME_ID..BardoSpanishG2pCodec.MAX_GRAPHEME_ID }) {
            return failure("La entrada contiene IDs de grafema fuera del vocabulario español.")
        }

        var target = intArrayOf(START_OR_ADVANCE_TOKEN_ID)
        var sourceIndex = 0
        var steps = 0
        while (sourceIndex < sourceTokens.size && target.size < MAX_TARGET_TENSOR_LENGTH) {
            val predicted = try {
                inferStep(sourceTokens.copyOf(), target.copyOf(), sourceIndex)
            } catch (error: Exception) {
                return Result(
                    emptyList(), target.copyOfRange(1, target.size), steps, sourceIndex,
                    listOf("Falló un paso de inferencia G2P: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                )
            }
            steps++
            when {
                predicted == START_OR_ADVANCE_TOKEN_ID -> sourceIndex++
                predicted in BardoSpanishG2pCodec.FIRST_PHONEME_ID..BardoSpanishG2pCodec.MAX_PHONEME_ID -> {
                    target = target + predicted
                }
                else -> {
                    return Result(
                        emptyList(), target.copyOfRange(1, target.size), steps, sourceIndex,
                        listOf("El modelo G2P devolvió un ID de fonema inválido: $predicted.")
                    )
                }
            }
        }

        val outputIds = target.copyOfRange(1, target.size)
        if (sourceIndex < sourceTokens.size) {
            return Result(
                emptyList(), outputIds, steps, sourceIndex,
                listOf("El modelo G2P alcanzó el límite de $MAX_OUTPUT_PHONEMES fonemas antes de terminar la palabra.")
            )
        }

        val decoded = BardoSpanishG2pCodec.decodeOutputIds(outputIds)
        if (!decoded.isComplete) {
            return Result(emptyList(), outputIds, steps, sourceIndex,
                listOf("El modelo G2P produjo IDs de salida no válidos: ${decoded.invalidIds.joinToString()}."))
        }
        if (decoded.phonemes.isEmpty()) {
            return Result(emptyList(), outputIds, steps, sourceIndex,
                listOf("El modelo G2P no produjo ningún fonema para la palabra."))
        }
        return Result(decoded.phonemes, outputIds, steps, sourceIndex, emptyList())
    }

    private fun failure(message: String) = Result(emptyList(), intArrayOf(), 0, 0, listOf(message))

    const val START_OR_ADVANCE_TOKEN_ID = BardoSpanishG2pCodec.START_OR_ADVANCE_TOKEN_ID
    const val MAX_SOURCE_TOKENS = 128
    const val MAX_OUTPUT_PHONEMES = BardoSpanishG2pCodec.MAX_OUTPUT_PHONEMES
    const val MAX_TARGET_TENSOR_LENGTH = MAX_OUTPUT_PHONEMES + 1
}
