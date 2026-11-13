package com.musicaia

import com.musicaia.modelo.BardoCreationReadinessPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class BardoCreationReadinessPolicyTest {
    private val completeVoiceRequest = mapOf(
        "tema" to "superar obstáculos",
        "estilo" to "rock",
        "ambiente" to "esperanza",
        "ritmo" to "medio",
        "idioma" to "español",
        "tipo" to "voz"
    )

    @Test
    fun missingVoiceSourceExplainsTheTwoSupportedOptionsWithoutDuplicatedWording() {
        assertEquals(
            "Indica una fuente de voz válida: grabación propia o archivo de voz.",
            BardoCreationReadinessPolicy.blockReason(
                completeVoiceRequest,
                hasVoiceWav = false
            )
        )
    }

    @Test
    fun instrumentalRequestDoesNotRequireAUserVoiceFile() {
        assertEquals(
            null,
            BardoCreationReadinessPolicy.blockReason(
                completeVoiceRequest + mapOf(
                    "tipo" to "solo instrumental",
                    "vozFuente" to "ninguna"
                ),
                hasVoiceWav = false
            )
        )
    }
}
