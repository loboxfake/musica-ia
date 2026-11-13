package com.musicaia.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoAvatarStateTest {
    @Test
    fun exposesFunctionalChatStatesWithoutChangingTheLogo() {
        assertEquals(
            listOf(
                "En reposo",
                "Recibiendo texto",
                "Leyendo",
                "Estudiando",
                "Pensando",
                "Respondiendo",
                "Creando",
                "En espera",
                "Completado",
                "Atención"
            ),
            BardoAvatarState.values().map { it.label }
        )
    }

    @Test
    fun onlyActiveWorkStatesAnimate() {
        assertFalse(BardoAvatarState.IDLE.animated)
        assertTrue(BardoAvatarState.LISTENING.animated)
        assertTrue(BardoAvatarState.READING.animated)
        assertTrue(BardoAvatarState.STUDYING.animated)
        assertTrue(BardoAvatarState.THINKING.animated)
        assertTrue(BardoAvatarState.RESPONDING.animated)
        assertTrue(BardoAvatarState.GENERATING.animated)
        assertTrue(BardoAvatarState.WAITING.animated)
        assertFalse(BardoAvatarState.SUCCESS.animated)
        assertFalse(BardoAvatarState.ERROR.animated)
    }
}
