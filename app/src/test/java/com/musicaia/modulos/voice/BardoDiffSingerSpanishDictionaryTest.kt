package com.musicaia.modulos.voice

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BardoDiffSingerSpanishDictionaryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun dictionary(content: String): BardoDiffSingerSpanishDictionary.Result {
        val file = temp.newFile("dsdict-es.yaml")
        file.writeText(content, StandardCharsets.UTF_8)
        return BardoDiffSingerSpanishDictionary.read(file)
    }

    @Test fun readsSymbolsEntriesAndReplacementMappings() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
              - symbol: l
                type: consonant
              - symbol: o
                type: vowel
              - symbol: h
                type: consonant
            entries:
              - grapheme: hola
                phonemes: [o, l, a]
            replacements:
              - from: a
                to: es/a
            """.trimIndent()
        )
        assertTrue(parsed.isValid)
        assertEquals(listOf("o", "l", "a"), parsed.entries["hola"])
        assertEquals("es/a", parsed.replacements["a"])
    }

    @Test fun acceptsYaml12DirectiveAndInlineSymbolMappings() {
        val parsed = dictionary(
            """
            %YAML 1.2
            ---
            symbols:
              - {symbol: a, type: vowel}
              - {symbol: p, type: stop}
            entries:
              - grapheme: papá
                phonemes: [p, a, p, a]
            """.trimIndent()
        )
        assertTrue(parsed.isValid)
        assertEquals(listOf("p", "a", "p", "a"), parsed.entries["papá"])
    }

    @Test fun acceptsOpenUtauSpanishDictionarySymbolTypes() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
              - symbol: p
                type: stop
              - symbol: ch
                type: affricate
              - symbol: s
                type: fricative
              - symbol: h
                type: aspirate
              - symbol: m
                type: nasal
            entries:
              - grapheme: ejemplo
                phonemes: [a, p, ch, s, h, m]
            """.trimIndent()
        )
        assertTrue(parsed.isValid)
        assertEquals(6, parsed.symbols.size)
    }

    @Test fun readsBlockPhonemeListsAndNormalizesWords() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
              - symbol: m
                type: consonant
            entries:
              - grapheme: mamá
                phonemes:
                  - m
                  - a
                  - m
                  - a
            """.trimIndent()
        )
        assertTrue(parsed.isValid)
        assertEquals(listOf("m", "a", "m", "a"), parsed.entries["mamá"])
    }

    @Test fun rejectsDuplicateWordsAndUndeclaredPhonemes() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
            entries:
              - grapheme: casa
                phonemes: [a, z]
              - grapheme: casa
                phonemes: [a]
            """.trimIndent()
        )
        assertFalse(parsed.isValid)
        assertTrue(parsed.errors.any { it.contains("no declarado") })
        assertTrue(parsed.errors.any { it.contains("duplicada") })
    }

    @Test fun rejectsReplacementTargetMissingFromVoiceInventory() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
            entries:
              - grapheme: casa
                phonemes: [a]
            replacements:
              - from: a
                to: es/a
            """.trimIndent()
        )
        val result = BardoDiffSingerSpanishDictionary.resolveLyrics("casa", parsed, mapOf("a" to 0))
        assertTrue(result.words.isEmpty())
        assertTrue(result.unsupportedPhonemes.any { it.contains("es/a") })
    }

    @Test fun rejectsInvalidUtf8() {
        val file = temp.newFile("bad.yaml")
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertFalse(BardoDiffSingerSpanishDictionary.read(file).isValid)
    }

    @Test fun resolvesKnownWordsAndReportsUnknownWordsWithoutGuessing() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
              - symbol: m
                type: consonant
            entries:
              - grapheme: mamá
                phonemes: [m, a, m, a]
            """.trimIndent()
        )
        val result = BardoDiffSingerSpanishDictionary.resolveLyrics(
            "¡Mamá, sorpresa!", parsed, mapOf("m" to 0, "a" to 1)
        )
        assertEquals(listOf("mamá"), result.words.map { it.text })
        assertEquals(listOf("sorpresa"), result.unknownWords)
        assertFalse(result.isComplete)
    }

    @Test fun rejectsDuplicateAndUnknownEntryFieldsInsteadOfSilentlyOverwriting() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
            entries:
              - grapheme: casa
                grapheme: cosa
                phonemes: [a]
                language: es
            """.trimIndent()
        )
        assertFalse(parsed.isValid)
        assertTrue(parsed.errors.any { it.contains("duplicado") })
        assertTrue(parsed.errors.any { it.contains("no admitido") })
    }

    @Test fun rejectsMalformedListItemsAndUnsupportedTopLevelSections() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
            entries:
              - grapheme: casa
                phonemes:
                  - a
                  - [malformed
            metadata:
              version: 1
            """.trimIndent()
        )
        assertFalse(parsed.isValid)
        assertTrue(parsed.errors.any { it.contains("Fonema de lista inválido") })
        assertTrue(parsed.errors.any { it.contains("Sección YAML no admitida") })
    }


    @Test fun prefersVoiceDictionaryAndUsesG2pOnlyForUnknownWords() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
              - symbol: o
                type: vowel
              - symbol: l
                type: consonant
            entries:
              - grapheme: hola
                phonemes: [o, l, a]
            replacements:
              - from: m
                to: es/m
            """.trimIndent()
        )
        val calls = mutableListOf<String>()
        val result = BardoDiffSingerSpanishDictionary.resolveLyricsWithG2pFallback(
            "Hola, mundo!",
            parsed,
            mapOf("o" to 0, "l" to 1, "a" to 2, "es/m" to 3, "es/u" to 4, "es/n" to 5, "es/d" to 6)
        ) { word ->
            calls += word
            BardoDiffSingerSpanishDictionary.G2pFallback(listOf("m", "u", "n", "d", "o"))
        }
        assertTrue(result.isComplete)
        assertEquals(listOf("mundo"), calls)
        assertEquals(listOf("hola", "mundo"), result.words.map { it.text })
        assertEquals(listOf("o", "l", "a"), result.words[0].phonemes)
        assertEquals(listOf("es/m", "es/u", "es/n", "es/d", "o"), result.words[1].phonemes)
    }

    @Test fun doesNotCallG2pWhenExactVoicePronunciationExists() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
            entries:
              - grapheme: casa
                phonemes: [a]
            """.trimIndent()
        )
        val result = BardoDiffSingerSpanishDictionary.resolveLyricsWithG2pFallback(
            "casa", parsed, mapOf("a" to 0)
        ) {
            throw IllegalStateException("G2P should not be called")
        }
        assertTrue(result.isComplete)
        assertEquals(listOf("a"), result.words.single().phonemes)
        assertTrue(result.g2pErrors.isEmpty())
    }

    @Test fun preservesG2pFailureAndUnsupportedVoicePhonemes() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
            entries:
              - grapheme: casa
                phonemes: [a]
            """.trimIndent()
        )
        val failed = BardoDiffSingerSpanishDictionary.resolveLyricsWithG2pFallback(
            "desconocida", parsed, mapOf("a" to 0)
        ) {
            BardoDiffSingerSpanishDictionary.G2pFallback(
                listOf("a"), "modelo G2P no disponible"
            )
        }
        assertFalse(failed.isComplete)
        assertEquals(listOf("desconocida"), failed.unknownWords)
        assertTrue(failed.g2pErrors.single().contains("modelo G2P no disponible"))
        assertTrue(failed.words.isEmpty())

        val unsupported = BardoDiffSingerSpanishDictionary.resolveLyricsWithG2pFallback(
            "nueva", parsed, mapOf("a" to 0)
        ) {
            BardoDiffSingerSpanishDictionary.G2pFallback(listOf("z"))
        }
        assertFalse(unsupported.isComplete)
        assertTrue(unsupported.unsupportedPhonemes.contains("nueva:z"))
    }


    @Test fun acceptsSymbolOnlyDictionaryWhenG2pFallbackProvidesPronunciations() {
        val parsed = dictionary(
            """
            symbols:
              - symbol: a
                type: vowel
              - symbol: b
                type: consonant
            replacements:
              - from: a
                to: es/a
            """.trimIndent()
        )
        assertTrue(parsed.isValid)
        assertTrue(parsed.entries.isEmpty())
        val result = BardoDiffSingerSpanishDictionary.resolveLyricsWithG2pFallback(
            "aba", parsed, mapOf("es/a" to 0, "b" to 1)
        ) {
            BardoDiffSingerSpanishDictionary.G2pFallback(listOf("a", "b", "a"))
        }
        assertTrue(result.isComplete)
        assertEquals(listOf("es/a", "b", "es/a"), result.words.single().phonemes)
    }
}
