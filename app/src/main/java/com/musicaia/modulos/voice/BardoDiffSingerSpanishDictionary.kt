package com.musicaia.modulos.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Lector limitado y seguro del subconjunto YAML usado por dsdict-es.yaml.
 * No ejecuta tags YAML, alias, referencias ni constructores arbitrarios.
 */
object BardoDiffSingerSpanishDictionary {
    data class WordEntry(val grapheme: String, val phonemes: List<String>)
    data class Result(
        val symbols: Map<String, String>,
        val entries: Map<String, List<String>>,
        val replacements: Map<String, String>,
        val errors: List<String>
    ) {
        val isValid: Boolean get() = errors.isEmpty() && symbols.isNotEmpty() && entries.isNotEmpty()
    }
    data class ResolvedWord(val text: String, val phonemes: List<String>, val start: Int, val end: Int)
    data class Resolution(val words: List<ResolvedWord>, val unknownWords: List<String>, val unsupportedPhonemes: List<String>) {
        val isComplete: Boolean get() = words.isNotEmpty() && unknownWords.isEmpty() && unsupportedPhonemes.isEmpty()
    }

    private enum class Section { NONE, SYMBOLS, ENTRIES, REPLACEMENTS }
    private const val MAX_BYTES = 2_097_152L
    private const val MAX_SYMBOLS = 16_384
    private const val MAX_ENTRIES = 100_000
    private const val MAX_PHONEMES_PER_ENTRY = 128
    private val wordPattern = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)?")

    fun read(file: File): Result {
        if (!file.isFile || file.length() <= 0L) return invalid("No existe dsdict-es.yaml o está vacío.")
        if (file.length() > MAX_BYTES) return invalid("El diccionario español supera 2 MiB.")
        val text = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (_: Exception) {
            return invalid("El diccionario no contiene UTF-8 válido.")
        }

        val symbols = linkedMapOf<String, String>()
        val entries = linkedMapOf<String, List<String>>()
        val replacements = linkedMapOf<String, String>()
        val errors = mutableListOf<String>()
        var section = Section.NONE
        var item: MutableMap<String, String>? = null
        var itemIndent = -1
        var listKey: String? = null
        var listValues = mutableListOf<String>()

        fun finishItem(lineNumber: Int) {
            val fields = item ?: return
            when (section) {
                Section.SYMBOLS -> {
                    val symbol = fields["symbol"].orEmpty()
                    val type = fields["type"].orEmpty()
                    if (!validAtom(symbol) || type !in setOf("vowel", "consonant", "semivowel", "liquid")) {
                        errors += "Definición de símbolo inválida cerca de la línea $lineNumber."
                    } else if (symbols.putIfAbsent(symbol, type) != null) {
                        errors += "Símbolo duplicado '$symbol'."
                    }
                }
                Section.ENTRIES -> {
                    val grapheme = normalizeWord(fields["grapheme"].orEmpty())
                    val phones = fields["phonemes"]?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
                    if (grapheme.isBlank() || grapheme.length > 128 || phones.isEmpty() || phones.size > MAX_PHONEMES_PER_ENTRY ||
                        phones.any { !validAtom(it) }) {
                        errors += "Entrada de pronunciación inválida cerca de la línea $lineNumber."
                    } else if (entries.putIfAbsent(grapheme, phones) != null) {
                        errors += "Palabra duplicada '$grapheme' en el diccionario."
                    }
                }
                Section.REPLACEMENTS -> {
                    val from = fields["from"].orEmpty()
                    val to = fields["to"].orEmpty()
                    if (!validAtom(from) || !validAtom(to)) {
                        errors += "Reemplazo fonético inválido cerca de la línea $lineNumber."
                    } else if (replacements.putIfAbsent(from, to) != null) {
                        errors += "Reemplazo duplicado para '$from'."
                    }
                }
                Section.NONE -> Unit
            }
            item = null
            listKey = null
            listValues = mutableListOf()
        }

        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val content = stripComment(raw).trimEnd()
            if (content.isBlank() || content.trim() == "---") return@forEachIndexed
            val indent = content.takeWhile { it == ' ' }.length
            val body = content.trim()
            if (indent == 0 && body.endsWith(":") && !body.startsWith("-")) {
                finishItem(lineNo)
                section = when (body.removeSuffix(":").trim()) {
                    "symbols" -> Section.SYMBOLS
                    "entries" -> Section.ENTRIES
                    "replacements" -> Section.REPLACEMENTS
                    else -> Section.NONE
                }
                return@forEachIndexed
            }
            if (section == Section.NONE) return@forEachIndexed

            if (body.startsWith("- ")) {
                if (item != null && indent <= itemIndent) finishItem(lineNo)
                if (item == null) {
                    item = linkedMapOf()
                    itemIndent = indent
                }
                val pair = parsePair(body.removePrefix("- ").trim())
                if (pair != null) {
                    if (pair.first == "phonemes" && pair.second.startsWith("[") && pair.second.endsWith("]")) {
                        item!!["phonemes"] = parseInlineList(pair.second).joinToString(" ")
                    } else if (pair.second.isNotEmpty()) {
                        item!![pair.first] = pair.second
                    } else {
                        listKey = pair.first
                        listValues = mutableListOf()
                    }
                } else if (listKey != null) {
                    listValues += unquote(body.removePrefix("- ").trim())
                    item!![listKey!!] = listValues.joinToString(" ")
                }
                return@forEachIndexed
            }

            val pair = parsePair(body)
            if (pair != null && item != null && indent > itemIndent) {
                if (pair.first == "phonemes" && pair.second.startsWith("[") && pair.second.endsWith("]")) {
                    item!!["phonemes"] = parseInlineList(pair.second).joinToString(" ")
                    listKey = null
                } else if (pair.second.isNotEmpty()) {
                    item!![pair.first] = pair.second
                    listKey = null
                } else {
                    listKey = pair.first
                    listValues = mutableListOf()
                }
                return@forEachIndexed
            }
            if (body.startsWith("- ") && listKey != null && item != null) {
                listValues += unquote(body.removePrefix("- ").trim())
                item!![listKey!!] = listValues.joinToString(" ")
            }
        }
        finishItem(text.lineSequence().count())

        if (symbols.isEmpty()) errors += "El diccionario no define símbolos fonéticos."
        if (entries.isEmpty()) errors += "El diccionario no contiene pronunciaciones."
        if (symbols.size > MAX_SYMBOLS) errors += "El diccionario supera el máximo de símbolos."
        if (entries.size > MAX_ENTRIES) errors += "El diccionario supera el máximo de entradas."
        entries.forEach { (word, phones) ->
            phones.filter { it !in symbols }.forEach { errors += "La palabra '$word' usa el símbolo no declarado '$it'." }
        }
        return Result(symbols.toMap(), entries.toMap(), replacements.toMap(), errors.distinct())
    }

    fun resolveLyrics(text: String, dictionary: Result, voicePhonemeIds: Map<String, Int>): Resolution {
        if (!dictionary.isValid) return Resolution(emptyList(), emptyList(), listOf("El diccionario español no es válido."))
        val words = mutableListOf<ResolvedWord>()
        val unknown = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        wordPattern.findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).forEach { match ->
            val word = normalizeWord(match.value)
            val sourcePhones = dictionary.entries[word]
            if (sourcePhones == null) {
                unknown += word
                return@forEach
            }
            val mapped = sourcePhones.map { phone ->
                dictionary.replacements[phone] ?: when {
                    "es/$phone" in voicePhonemeIds -> "es/$phone"
                    phone in voicePhonemeIds -> phone
                    else -> {
                        unsupported += "$word:$phone"
                        ""
                    }
                }
            }.filter { it.isNotEmpty() }
            if (mapped.size == sourcePhones.size) {
                words += ResolvedWord(word, mapped, match.range.first, match.range.last + 1)
            }
        }
        return Resolution(words, unknown.distinct(), unsupported.distinct())
    }

    private fun invalid(message: String) = Result(emptyMap(), emptyMap(), emptyMap(), listOf(message))
    private fun normalizeWord(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun validAtom(value: String) = value.isNotBlank() && value.length <= 64 &&
        value.none { it.isWhitespace() || it.isISOControl() || it == '[' || it == ']' }
    private fun parsePair(value: String): Pair<String, String>? {
        val colon = value.indexOf(':')
        if (colon <= 0) return null
        val key = value.substring(0, colon).trim()
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        return key to unquote(value.substring(colon + 1).trim())
    }
    private fun unquote(value: String): String = value.trim().removeSurrounding("\"").removeSurrounding("'")
    private fun parseInlineList(value: String): List<String> =
        value.removePrefix("[").removeSuffix("]").split(',').map { unquote(it.trim()) }.filter { it.isNotBlank() }
    private fun stripComment(value: String): String {
        var quote: Char? = null
        value.forEachIndexed { index, c ->
            if ((c == '"' || c == '\'') && (index == 0 || value[index - 1] != '\\')) {
                quote = if (quote == c) null else if (quote == null) c else quote
            }
            if (c == '#' && quote == null && (index == 0 || value[index - 1].isWhitespace())) {
                return value.substring(0, index)
            }
        }
        return value
    }
}
