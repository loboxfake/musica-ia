package com.musicaia.modulos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoAudioGenerationCleanupTest {
    @Test
    fun attemptsEveryCleanupAndSuppressesFailuresOnOriginalError() {
        val primary = IllegalStateException("generation failed")
        val release = IllegalStateException("native release failed")
        val diagnostics = IllegalStateException("diagnostics write failed")
        var laterActionRan = false

        BardoAudioGenerationCleanup.finish(
            primary,
            listOf(
                { throw release },
                { throw diagnostics },
                { laterActionRan = true }
            )
        )

        assertTrue(laterActionRan)
        assertEquals(2, primary.suppressed.size)
        assertSame(release, primary.suppressed[0])
        assertSame(diagnostics, primary.suppressed[1])
    }

    @Test
    fun surfacesCleanupFailureWhenGenerationSucceeded() {
        val release = IllegalStateException("native release failed")
        var diagnosticsAttempted = false

        val thrown = assertThrows(IllegalStateException::class.java) {
            BardoAudioGenerationCleanup.finish(
                null,
                listOf(
                    { throw release },
                    { diagnosticsAttempted = true }
                )
            )
        }

        assertSame(release, thrown)
        assertTrue(diagnosticsAttempted)
    }

    @Test
    fun doesNotSelfSuppressWhenCleanupThrowsTheOriginalInstance() {
        val primary = IllegalStateException("same instance")

        BardoAudioGenerationCleanup.finish(primary, listOf({ throw primary }))

        assertEquals(0, primary.suppressed.size)
    }
}
