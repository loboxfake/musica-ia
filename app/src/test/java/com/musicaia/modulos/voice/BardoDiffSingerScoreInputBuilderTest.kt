package com.musicaia.modulos.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoDiffSingerScoreInputBuilderTest {
    private val phonemes = mapOf("SP" to 0, "a" to 1, "b" to 2)

    @Test fun padsSilenceAndBuildsFrameAlignedPitchCurve() {
        val result = BardoDiffSingerScoreInputBuilder.build(
            spans = listOf(
                BardoDiffSingerScoreInputBuilder.PhonemeNote("a", 0.0, 200.0, 69),
                BardoDiffSingerScoreInputBuilder.PhonemeNote("b", 250.0, 100.0, 72)
            ),
            phonemeIds = phonemes,
            sampleRate = 44_100,
            hopSize = 512
        )
        assertEquals(listOf(0L, 1L, 0L, 2L, 0L), result.tokens.toList())
        assertEquals(result.totalFrames, result.durations.sum().toInt())
        assertEquals(0f, result.f0Hz[0], 0f)
        assertEquals(440f, result.f0Hz[8], 0.1f)
        assertEquals(0f, result.f0Hz[26], 0f)
        assertEquals(523.2511f, result.f0Hz[30], 0.2f)
        assertTrue(result.durations.last() >= 8L)
    }

    @Test fun rejectsUnknownPhonemesInsteadOfSubstitutingSilence() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoDiffSingerScoreInputBuilder.build(
                listOf(BardoDiffSingerScoreInputBuilder.PhonemeNote("unknown", 0.0, 100.0, 60)),
                phonemes, 44_100, 512
            )
        }
    }

    @Test fun rejectsOverlappingPhonemeSpans() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoDiffSingerScoreInputBuilder.build(
                listOf(
                    BardoDiffSingerScoreInputBuilder.PhonemeNote("a", 0.0, 150.0, 60),
                    BardoDiffSingerScoreInputBuilder.PhonemeNote("b", 100.0, 100.0, 62)
                ),
                phonemes, 44_100, 512
            )
        }
    }

    @Test fun rejectsMissingSilenceTokenAndInvalidMidi() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoDiffSingerScoreInputBuilder.build(
                listOf(BardoDiffSingerScoreInputBuilder.PhonemeNote("a", 0.0, 100.0, 60)),
                mapOf("a" to 1), 44_100, 512
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BardoDiffSingerScoreInputBuilder.build(
                listOf(BardoDiffSingerScoreInputBuilder.PhonemeNote("a", 0.0, 100.0, 128)),
                phonemes, 44_100, 512
            )
        }
    }

    @Test fun keepsUnvoicedPhonemeFramesAtZeroPitch() {
        val result = BardoDiffSingerScoreInputBuilder.build(
            listOf(BardoDiffSingerScoreInputBuilder.PhonemeNote("a", 0.0, 100.0, null)),
            phonemes, 44_100, 512
        )
        assertTrue(result.f0Hz.all { it == 0f })
    }
}
