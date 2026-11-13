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


    @Test fun manualLyricsCanUseAnActualUserVoiceWav() {
        assertTrue(
            BardoCapabilityRegistry.manualLyricsCanProceed(
                instrumentalOnly = false,
                manualLyrics = "Esta letra debe cantarse con mi propia grabación.",
                userVoiceAvailable = true
            )
        )
    }

    @Test fun manualLyricsStillBlockWhenNoRealUserVoiceIsAvailable() {
        assertFalse(
            BardoCapabilityRegistry.manualLyricsCanProceed(
                instrumentalOnly = false,
                manualLyrics = "Esta letra no debe convertirse silenciosamente en instrumental.",
                userVoiceAvailable = false
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
