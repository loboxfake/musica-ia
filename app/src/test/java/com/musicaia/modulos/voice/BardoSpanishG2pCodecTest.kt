package com.musicaia.modulos.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSpanishG2pCodecTest {

    @Test fun encodesUppercaseAndAccentedSpanishGraphemes() {
        val result = BardoSpanishG2pCodec.encodeWord("ÁRBOL")
        assertTrue(result.isComplete)
        assertArrayEquals(intArrayOf(37, 23, 7, 20, 17), result.tokenIds)
        assertTrue(result.unsupportedGraphemes.isEmpty())
    }

    @Test fun encodesNWithTildeAndKeepsSpanishCaseNormalization() {
        val result = BardoSpanishG2pCodec.encodeWord("NIÑO")
        assertTrue(result.isComplete)
        assertArrayEquals(intArrayOf(19, 14, 43, 20), result.tokenIds)
    }

    @Test fun normalizesDecomposedUnicodeBeforeEncoding() {
        val result = BardoSpanishG2pCodec.encodeWord("cancio\u0301n")
        assertTrue(result.isComplete)
        assertArrayEquals(intArrayOf(8, 6, 19, 8, 22, 19), result.tokenIds)
    }

    @Test fun reportsUnsupportedCharactersInsteadOfSilentlyDroppingThem() {
        val result = BardoSpanishG2pCodec.encodeWord("casa!")
        assertFalse(result.isComplete)
        assertArrayEquals(intArrayOf(8, 6, 24, 6), result.tokenIds)
        assertEquals(listOf("!"), result.unsupportedGraphemes)
    }

    @Test fun rejectsEmptyAndOversizedWords() {
        assertFalse(BardoSpanishG2pCodec.encodeWord("").isComplete)
        assertEquals(
            listOf("<word-too-long>"),
            BardoSpanishG2pCodec.encodeWord("a".repeat(BardoSpanishG2pCodec.MAX_WORD_UTF16_UNITS + 1))
                .unsupportedGraphemes
        )
    }

    @Test fun decodesModelOutputIdsIntoSpanishPhonemes() {
        val result = BardoSpanishG2pCodec.decodeOutputIds(intArrayOf(4, 24, 22, 18))
        assertTrue(result.isComplete)
        assertEquals(listOf("a", "r", "o", "l"), result.phonemes)
    }

    @Test fun rejectsReservedAndOutOfRangeOutputIds() {
        val result = BardoSpanishG2pCodec.decodeOutputIds(intArrayOf(2, 35, -1))
        assertFalse(result.isComplete)
        assertEquals(listOf(2, 35, -1), result.invalidIds)
        assertTrue(result.phonemes.isEmpty())
    }

    @Test fun enforcesTheReferenceDecoderOutputLimit() {
        val result = BardoSpanishG2pCodec.decodeOutputIds(
            IntArray(BardoSpanishG2pCodec.MAX_OUTPUT_PHONEMES + 1) { 4 }
        )
        assertFalse(result.isComplete)
        assertTrue(result.phonemes.isEmpty())
        assertEquals(BardoSpanishG2pCodec.MAX_OUTPUT_PHONEMES + 1, result.invalidIds.size)
    }
}
