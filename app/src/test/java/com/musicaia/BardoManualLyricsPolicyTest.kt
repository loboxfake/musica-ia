package com.musicaia

import com.musicaia.modelo.BardoCapabilityRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoManualLyricsPolicyTest {
    @Test fun manualLyricsCannotSilentlyBecomeInstrumental() {
        assertFalse(
            BardoCapabilityRegistry.manualLyricsCanProceed(
                instrumentalOnly = false,
                manualLyrics = "Esta letra debe cantarse."
            )
        )
    }

    @Test fun soloInstrumentalDoesNotRequireSingingEngine() {
        assertTrue(
            BardoCapabilityRegistry.manualLyricsCanProceed(
                instrumentalOnly = true,
                manualLyrics = "Texto que no debe convertirse en voz."
            )
        )
    }

    @Test fun emptyManualLyricsDoNotTriggerThisGuard() {
        assertTrue(
            BardoCapabilityRegistry.manualLyricsCanProceed(
                instrumentalOnly = false,
                manualLyrics = ""
            )
        )
    }
}
