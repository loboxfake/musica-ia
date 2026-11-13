package com.musicaia.modulos.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Códec de tokens del G2P español de referencia de OpenUtau.
 *
 * Traduce grafemas españoles a IDs de entrada y los IDs de salida a fonemas.
 * No carga ni ejecuta ONNX: eso requiere un paquete G2P con pesos y licencia
 * verificados y un runtime independiente.
 *
 * El orden de IDs corresponde al contrato de SpanishG2p.cs / G2pPack.cs.
 */
object BardoSpanishG2pCodec {
    data class Encoding(
        val tokenIds: IntArray,
        val unsupportedGraphemes: List<String>
    ) {
        val isComplete: Boolean get() = tokenIds.isNotEmpty() && unsupportedGraphemes.isEmpty()
    }

    data class Decoding(
        val phonemes: List<String>,
        val invalidIds: List<Int>
    ) {
        val isComplete: Boolean get() = phonemes.isNotEmpty() && invalidIds.isEmpty()
    }

    // SpanishG2p.cs drops the first four reserved vocabulary slots.
    // IDs are therefore the position in this list plus four.
    private val inputGraphemes = listOf(
        "'", "-", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k",
        "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x",
        "y", "z", "Á", "É", "Í", "Ó", "Ú", "á", "ã", "é", "ë", "ê", "í",
        "ñ", "ó", "ú", "ü"
    )

    private val inputIds: Map<String, Int> = inputGraphemes
        .withIndex()
        .associate { it.value.lowercase(Locale.ROOT) to it.index + FIRST_GRAPHEME_ID }

    // Output IDs 0..3 are reserved; the model emits phonemes from ID 4 onward.
    private val outputPhonemes = listOf(
        "", "", "", "", "a", "b", "B", "ch", "d", "D", "e", "f", "g", "G",
        "gn", "i", "I", "k", "l", "ll", "m", "n", "o", "p", "r", "rr",
        "s", "t", "u", "U", "w", "x", "y", "Y", "z"
    )

    /**
     * Encodes exactly the supported graphemes. Unlike the reference helper,
     * unknown characters are reported instead of silently dropped.
     */
    fun encodeWord(word: String): Encoding {
        if (word.isEmpty()) return Encoding(IntArray(0), listOf("<empty>"))
        if (word.length > MAX_WORD_UTF16_UNITS) {
            return Encoding(IntArray(0), listOf("<word-too-long>"))
        }

        val normalized = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val tokens = ArrayList<Int>(normalized.length)
        val unsupported = mutableListOf<String>()
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            val grapheme = String(Character.toChars(codePoint))
            val id = inputIds[grapheme]
            if (id == null) {
                unsupported += grapheme
            } else {
                tokens += id
            }
            offset += Character.charCount(codePoint)
        }
        return Encoding(tokens.toIntArray(), unsupported.distinct())
    }

    /** Converts model output IDs to base Spanish phoneme symbols. */
    fun decodeOutputIds(ids: IntArray): Decoding {
        if (ids.size > MAX_OUTPUT_PHONEMES) {
            return Decoding(emptyList(), ids.toList())
        }
        val phonemes = mutableListOf<String>()
        val invalid = mutableListOf<Int>()
        ids.forEach { id ->
            val symbol = outputPhonemes.getOrNull(id)
            if (id < FIRST_PHONEME_ID || symbol.isNullOrEmpty()) {
                invalid += id
            } else {
                phonemes += symbol
            }
        }
        return Decoding(phonemes, invalid)
    }

    const val SRC_INPUT_NAME = "src"
    const val TGT_INPUT_NAME = "tgt"
    const val STEP_INPUT_NAME = "t"
    const val START_OR_ADVANCE_TOKEN_ID = 2
    const val FIRST_GRAPHEME_ID = 4
    const val MAX_GRAPHEME_ID = 46
    const val FIRST_PHONEME_ID = 4
    const val MAX_PHONEME_ID = 34
    const val MAX_WORD_UTF16_UNITS = 256
    // G2pPack starts tgt with one reserved token and stops while tgt.Length < 48.
    const val MAX_OUTPUT_PHONEMES = 47
}
