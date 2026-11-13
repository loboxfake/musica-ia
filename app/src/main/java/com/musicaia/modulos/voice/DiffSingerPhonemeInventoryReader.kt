package com.musicaia.modulos.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Lee phonemes.txt de DiffSinger sin inferir pronunciaciones ni alterar símbolos.
 * Cada línea no vacía contiene exactamente un símbolo; su posición define el ID.
 */
object DiffSingerPhonemeInventoryReader {
    data class Result(
        val phonemeToId: Map<String, Int>,
        val errors: List<String>
    ) {
        val isValid: Boolean get() = errors.isEmpty() && phonemeToId.containsKey(SILENCE_TOKEN)
    }

    const val SILENCE_TOKEN = "SP"
    const val MAX_FILE_BYTES = 1_048_576L
    const val MAX_PHONEMES = 8_192
    const val MAX_SYMBOL_LENGTH = 64

    fun read(file: File): Result {
        if (!file.isFile || file.length() <= 0L) {
            return Result(emptyMap(), listOf("No existe phonemes.txt o está vacío."))
        }
        if (file.length() > MAX_FILE_BYTES) {
            return Result(emptyMap(), listOf("El inventario de fonemas supera 1 MiB."))
        }
        val source = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (_: Exception) {
            return Result(emptyMap(), listOf("El inventario no contiene UTF-8 válido."))
        }

        val symbols = LinkedHashMap<String, Int>()
        val errors = mutableListOf<String>()
        source.removePrefix("\uFEFF").lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val symbol = line.substringBefore('#').trim()
            if (symbol.isEmpty()) return@forEachIndexed
            if (symbol.length > MAX_SYMBOL_LENGTH ||
                symbol.any { it.isWhitespace() || it.isISOControl() }) {
                errors += "Símbolo no válido en la línea ${lineIndex + 1}."
                return@forEachIndexed
            }
            if (symbols.containsKey(symbol)) {
                errors += "Símbolo duplicado '$symbol' en la línea ${lineIndex + 1}."
                return@forEachIndexed
            }
            if (symbols.size >= MAX_PHONEMES) {
                errors += "El inventario supera el máximo de $MAX_PHONEMES símbolos."
                return@forEachIndexed
            }
            symbols[symbol] = symbols.size
        }

        if (symbols.isEmpty()) errors += "El inventario no contiene símbolos."
        if (SILENCE_TOKEN !in symbols) errors += "Falta el símbolo de silencio obligatorio SP."
        return Result(symbols.toMap(), errors)
    }
}
