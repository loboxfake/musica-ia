package com.musicaia.modulos.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BardoDiffSingerSpanishG2pResolverTest {
    @get:Rule val temp = TemporaryFolder()

    private fun dictionary(): BardoDiffSingerSpanishDictionary.Result {
        val file = temp.newFile("dsdict-es.yaml")
        file.writeText(
            """
            symbols:
              - symbol: o
                type: vowel
              - symbol: l
                type: consonant
              - symbol: a
                type: vowel
            entries:
              - grapheme: hola
                phonemes: [o, l, a]
            replacements:
              - from: m
                to: es/m
            """.trimIndent()
        )
        return BardoDiffSingerSpanishDictionary.read(file)
    }

    private fun inspection(
        status: DiffSingerVoicebankInspector.Status = DiffSingerVoicebankInspector.Status.READY_FOR_RUNTIME_PROBE
    ) = DiffSingerVoicebankInspector.Inspection(
        status = status,
        voicebankRoot = temp.root,
        phonemeIds = mapOf("o" to 0, "l" to 1, "a" to 2, "es/m" to 3, "es/u" to 4, "es/n" to 5, "es/d" to 6),
        spanishDictionary = dictionary()
    )

    @Test fun resolvesDictionaryWordsAndUsesRealPredictionOnlyForMissingEntries() {
        val calls = mutableListOf<String>()
        val result = BardoDiffSingerSpanishG2pResolver.resolveLyrics(
            "hola mundo",
            inspection()
        ) { word ->
            calls += word
            BardoSpanishG2pOnnxEngine.Prediction(
                phonemes = listOf("m", "u", "n", "d", "o")
            )
        }

        assertTrue(result.isComplete)
        assertEquals(listOf("mundo"), calls)
        assertEquals(listOf("hola", "mundo"), result.words.map { it.text })
        assertEquals(listOf("es/m", "es/u", "es/n", "es/d", "o"), result.words.last().phonemes)
    }

    @Test fun refusesToRunPredictionWhenVoicebankInspectionFailed() {
        var called = false
        val result = BardoDiffSingerSpanishG2pResolver.resolveLyrics(
            "palabra desconocida",
            inspection(DiffSingerVoicebankInspector.Status.INVALID_CONFIG)
        ) {
            called = true
            BardoSpanishG2pOnnxEngine.Prediction(listOf("a"))
        }

        assertFalse(result.isComplete)
        assertFalse(called)
        assertTrue(result.unsupportedPhonemes.single().contains("no superó la inspección"))
    }

    @Test fun exposesG2pModelErrorsWithoutGuessingPronunciation() {
        val result = BardoDiffSingerSpanishG2pResolver.resolveLyrics(
            "desconocida",
            inspection()
        ) {
            BardoSpanishG2pOnnxEngine.Prediction(
                phonemes = emptyList(),
                errors = listOf("No se pudo abrir el modelo G2P")
            )
        }

        assertFalse(result.isComplete)
        assertEquals(listOf("desconocida"), result.unknownWords)
        assertTrue(result.g2pErrors.single().contains("No se pudo abrir el modelo G2P"))
    }
}
