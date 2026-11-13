package com.musicaia.modulos.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoSpanishG2pAutoregressiveDecoderTest {

    @Test fun advancesSourceIndexOnlyWhenModelEmitsReservedStepToken() {
        val source = intArrayOf(6, 7)
        val result = BardoSpanishG2pAutoregressiveDecoder.decode(source) { _, target, sourceIndex ->
            when {
                sourceIndex == 0 && target.size == 1 -> 4
                sourceIndex == 0 -> BardoSpanishG2pAutoregressiveDecoder.START_OR_ADVANCE_TOKEN_ID
                sourceIndex == 1 && target.size == 2 -> 5
                else -> BardoSpanishG2pAutoregressiveDecoder.START_OR_ADVANCE_TOKEN_ID
            }
        }
        assertTrue(result.isComplete)
        assertEquals(listOf("a", "b"), result.phonemes)
        assertEquals(4, result.steps)
        assertEquals(2, result.processedSourceTokens)
    }

    @Test fun rejectsInvalidModelOutputIdsImmediately() {
        val result = BardoSpanishG2pAutoregressiveDecoder.decode(intArrayOf(6)) { _, _, _ -> 99 }
        assertFalse(result.isComplete)
        assertTrue(result.errors.single().contains("ID de fonema inválido"))
        assertEquals(1, result.steps)
    }

    @Test fun stopsWhenTheMaximumPhonemeCountIsReached() {
        val result = BardoSpanishG2pAutoregressiveDecoder.decode(intArrayOf(6)) { _, _, _ -> 4 }
        assertFalse(result.isComplete)
        assertTrue(result.errors.single().contains("alcanzó el límite"))
        assertEquals(BardoSpanishG2pAutoregressiveDecoder.MAX_OUTPUT_PHONEMES, result.outputTokenIds.size)
    }

    @Test fun rejectsInvalidOrExcessiveSourceTokenSequences() {
        val invalid = BardoSpanishG2pAutoregressiveDecoder.decode(intArrayOf(1)) { _, _, _ -> 2 }
        assertFalse(invalid.isComplete)
        assertTrue(invalid.errors.single().contains("IDs de grafema"))

        val oversized = BardoSpanishG2pAutoregressiveDecoder.decode(
            IntArray(BardoSpanishG2pAutoregressiveDecoder.MAX_SOURCE_TOKENS + 1) { 6 }
        ) { _, _, _ -> 2 }
        assertFalse(oversized.isComplete)
        assertTrue(oversized.errors.single().contains("límite"))
    }

    @Test fun convertsInferenceExceptionsIntoExplicitErrors() {
        val result = BardoSpanishG2pAutoregressiveDecoder.decode(intArrayOf(6)) { _, _, _ ->
            throw IllegalStateException("model unavailable")
        }
        assertFalse(result.isComplete)
        assertTrue(result.errors.single().contains("model unavailable"))
    }
}
