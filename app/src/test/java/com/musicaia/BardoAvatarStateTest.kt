package com.musicaia

import com.musicaia.ui.BardoAvatarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoAvatarStateTest {
    @Test fun idleAndTerminalStatesRemainStatic() {
        listOf(BardoAvatarState.IDLE, BardoAvatarState.SUCCESS, BardoAvatarState.ERROR).forEach {
            assertFalse("${it.name} no debe animarse", it.animated)
            assertEquals(0f, it.pulseScale, 0f)
            assertEquals(0f, it.pulseRotation, 0f)
            assertEquals(0L, it.pulseDurationMs)
        }
    }

    @Test fun activeStatesHaveDistinctBoundedMotionProfiles() {
        val active = listOf(
            BardoAvatarState.LISTENING,
            BardoAvatarState.READING,
            BardoAvatarState.STUDYING,
            BardoAvatarState.THINKING,
            BardoAvatarState.RESPONDING,
            BardoAvatarState.GENERATING,
            BardoAvatarState.WAITING
        )
        assertTrue(active.all { it.animated })
        assertTrue(active.all { it.pulseScale in 0.001f..0.03f })
        assertTrue(active.all { it.pulseRotation in 0f..1f })
        assertTrue(active.all { it.pulseDurationMs in 800L..3000L })
        assertEquals(active.size, active.map { it.label }.distinct().size)
        assertTrue(active.map { it.pulseDurationMs }.distinct().size > 1)
    }
}
