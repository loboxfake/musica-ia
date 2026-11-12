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
}
