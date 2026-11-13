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
        val isValid: Boolean get() = errors.isEmpty() && symbols.isNotEmpty()
    }
    data class ResolvedWord(val text: String, val phonemes: List<String>, val start: Int, val end: Int)
    data class G2pFallback(val phonemes: List<String>, val error: String? = null)
    data class Resolution(
        val words: List<ResolvedWord>,
        val unknownWords: List<String>,
        val unsupportedPhonemes: List<String>,
        val g2pErrors: List<String> = emptyList()
    ) {
        val isComplete: Boolean
            get() = words.isNotEmpty() && unknownWords.isEmpty() &&
                unsupportedPhonemes.isEmpty() && g2pErrors.isEmpty()
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
                    if (!validAtom(symbol) || type !in setOf("vowel", "consonant", "semivowel", "liquid", "stop", "affricate", "fricative", "aspirate", "nasal")) {
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

        fun assignField(key: String, value: String, lineNo: Int) {
            val allowed = when (section) {
                Section.SYMBOLS -> setOf("symbol", "type")
                Section.ENTRIES -> setOf("grapheme", "phonemes")
                Section.REPLACEMENTS -> setOf("from", "to")
                Section.NONE -> emptySet()
            }
            if (key !in allowed) {
                errors += "Campo '$key' no admitido en la sección $section (línea $lineNo)."
                return
            }
            val fields = item ?: return
            if (key in fields || listKey == key) {
                errors += "Campo duplicado '$key' en la línea $lineNo."
                return
            }
            if (key == "phonemes" && value.startsWith("[") && value.endsWith("]")) {
                val parsed = parseInlineList(value)
                if (parsed.isEmpty()) errors += "Lista de fonemas vacía en la línea $lineNo."
                fields[key] = parsed.joinToString(" ")
                listKey = null
            } else if (value.isNotEmpty()) {
                fields[key] = value
                listKey = null
            } else {
                if (key != "phonemes") {
                    errors += "El campo '$key' no admite una lista en la línea $lineNo."
                } else {
                    listKey = key
                    listValues = mutableListOf()
                }
            }
        }

        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val content = stripComment(raw).trimEnd()
            if (content.isBlank() || content.trim() == "---" || content.trim() == "%YAML 1.2") return@forEachIndexed
            val indent = content.takeWhile { it == ' ' }.length
            val body = content.trim()
            if (indent == 0 && body.endsWith(":") && !body.startsWith("-")) {
                finishItem(lineNo)
                val sectionName = body.removeSuffix(":").trim()
                section = when (sectionName) {
                    "symbols" -> Section.SYMBOLS
                    "entries" -> Section.ENTRIES
                    "replacements" -> Section.REPLACEMENTS
                    else -> {
                        errors += "Sección YAML no admitida '$sectionName' en la línea $lineNo."
                        Section.NONE
                    }
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
                val listItem = body.removePrefix("- ").trim()
                val pair = parsePair(listItem)
                if (pair != null) {
                    assignField(pair.first, pair.second, lineNo)
                } else if (section == Section.SYMBOLS && listItem.startsWith("{") && listItem.endsWith("}")) {
                    val inlineFields = listItem.removeSurrounding("{", "}").split(',')
                    if (inlineFields.isEmpty()) {
                        errors += "Mapa de símbolo vacío en la línea $lineNo."
                    } else {
                        inlineFields.forEach { field ->
                            val inlinePair = parsePair(field.trim())
                            if (inlinePair == null) errors += "Campo de símbolo inline inválido en la línea $lineNo."
                            else assignField(inlinePair.first, inlinePair.second, lineNo)
                        }
                    }
                } else if (listKey == "phonemes") {
                    val phone = unquote(listItem)
                    if (!validAtom(phone)) {
                        errors += "Fonema de lista inválido en la línea $lineNo."
                    } else {
                        listValues += phone
                        item!!["phonemes"] = listValues.joinToString(" ")
                    }
                } else {
                    errors += "Elemento YAML no admitido en la línea $lineNo."
                }
                return@forEachIndexed
            }

            val pair = parsePair(body)
            if (pair != null && item != null && indent > itemIndent) {
                assignField(pair.first, pair.second, lineNo)
                return@forEachIndexed
            }
            errors += "Estructura YAML no admitida en la línea $lineNo."
        }
        finishItem(text.lineSequence().count())

        if (symbols.isEmpty()) errors += "El diccionario no define símbolos fonéticos."
        // Un diccionario solo de símbolos es válido: el G2P externo puede aportar las pronunciaciones.
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
                val replacement = dictionary.replacements[phone]
                when {
                    replacement != null && replacement in voicePhonemeIds -> replacement
                    replacement != null -> {
                        unsupported += "$word:$phone->$replacement"
                        ""
                    }
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

    /**
     * Resuelve primero el diccionario específico de la voz. Solo las palabras
     * ausentes usan el G2P base; después se aplican replacements y el fallback
     * es/<fonema> de DiffSinger, validando cada símbolo contra la voz cargada.
     */
    fun resolveLyricsWithG2pFallback(
        text: String,
        dictionary: Result,
        voicePhonemeIds: Map<String, Int>,
        predict: (String) -> G2pFallback
    ): Resolution {
        if (!dictionary.isValid) {
            return Resolution(emptyList(), emptyList(), listOf("El diccionario español no es válido."))
        }

        val words = mutableListOf<ResolvedWord>()
        val unknown = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val g2pErrors = mutableListOf<String>()

        wordPattern.findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).forEach { match ->
            val word = normalizeWord(match.value)
            val dictionaryPhones = dictionary.entries[word]
            val sourcePhones = if (dictionaryPhones != null) {
                dictionaryPhones
            } else {
                val fallback = try {
                    predict(word)
                } catch (error: Exception) {
                    G2pFallback(emptyList(), "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                }
                if (fallback.error != null) {
                    g2pErrors += "$word: ${fallback.error}"
                    emptyList()
                } else {
                    fallback.phonemes
                }
            }

            if (sourcePhones.isEmpty()) {
                unknown += word
                return@forEach
            }

            val mapped = sourcePhones.map { phone ->
                val replacement = dictionary.replacements[phone]
                when {
                    replacement != null && replacement in voicePhonemeIds -> replacement
                    replacement != null -> {
                        unsupported += "$word:$phone->$replacement"
                        ""
                    }
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
        return Resolution(words, unknown.distinct(), unsupported.distinct(), g2pErrors.distinct())
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
