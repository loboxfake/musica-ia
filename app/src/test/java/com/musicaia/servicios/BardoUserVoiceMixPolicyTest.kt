package com.musicaia.servicios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoUserVoiceMixPolicyTest {
    @Test
    fun advertisedVoiceWithoutPathIsRejected() {
        val reason = BardoUserVoiceMixPolicy.blockReason(false, true, null, false, 0L)
        assertTrue(reason.orEmpty().contains("no se recibió la ruta"))
        assertFalse(BardoUserVoiceMixPolicy.shouldMix(false, null))
    }

    @Test
    fun suppliedVoiceMustExistAndRespectSizeLimits() {
        assertTrue(BardoUserVoiceMixPolicy.blockReason(false, false, "/voice.wav", false, 80L).orEmpty().contains("no existe"))
        assertTrue(BardoUserVoiceMixPolicy.blockReason(false, false, "/voice.wav", true, 43L).orEmpty().contains("demasiado pequeño"))
        assertTrue(BardoUserVoiceMixPolicy.blockReason(false, false, "/voice.wav", true, 121L, 120L).orEmpty().contains("120 MiB"))
    }

    @Test
    fun validVoicePathRequestsAnActualMix() {
        assertNull(BardoUserVoiceMixPolicy.blockReason(false, true, "/voice.wav", true, 100L))
        assertTrue(BardoUserVoiceMixPolicy.shouldMix(false, "/voice.wav"))
    }

    @Test
    fun avoidsMixingVoiceTwiceForNewServiceResultsButKeepsLegacyRecovery() {
        assertFalse(BardoUserVoiceMixPolicy.shouldPostMixForLegacyResult(true, true))
        assertTrue(BardoUserVoiceMixPolicy.shouldPostMixForLegacyResult(true, false))
        assertFalse(BardoUserVoiceMixPolicy.shouldPostMixForLegacyResult(false, false))
    }

    @Test
    fun instrumentalOnlyNeverRequiresOrMixesVoice() {
        assertNull(BardoUserVoiceMixPolicy.blockReason(true, true, null, false, 0L))
        assertFalse(BardoUserVoiceMixPolicy.shouldMix(true, "/voice.wav"))
    }
}
