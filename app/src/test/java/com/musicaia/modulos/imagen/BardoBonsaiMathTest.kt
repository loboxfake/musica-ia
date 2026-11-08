package com.musicaia.modulos.imagen

import org.junit.Assert.*
import org.junit.Test

class BardoBonsaiMathTest {
    @Test fun flowMatchScheduleHasFourMonotonicSteps() {
        val values = BardoBonsaiMath.sigmas(4)
        assertEquals(5, values.size)
        assertEquals(1f, values.first(), 1e-5f)
        assertEquals(0f, values.last(), 1e-6f)
        for (i in 0 until values.lastIndex) assertTrue(values[i] > values[i + 1])
    }

    @Test fun positionIdsHaveTheExpectedFixedShapes() {
        val image = BardoBonsaiMath.imgIds()
        val text = BardoBonsaiMath.txtIds()
        assertEquals(1024 * 4, image.size)
        assertEquals(256 * 4, text.size)
        assertEquals(0f, image[0], 0f)
        assertEquals(1f, image[2], 0f)
        assertEquals(255f, text[text.lastIndex], 0f)
    }

    @Test fun seededNoiseIsDeterministicAndSeedSensitive() {
        val first = BardoBonsaiMath.noise(1234L)
        val same = BardoBonsaiMath.noise(1234L)
        val other = BardoBonsaiMath.noise(1235L)
        assertEquals(1024 * 128, first.size)
        assertArrayEquals(first, same, 0f)
        assertFalse(first.contentEquals(other))
    }

    @Test fun unpatchifyUsesPerPackedChannelAffineAndReturnsVaeShape() {
        val lat = FloatArray(1024 * 128) { 1f }
        val scale = FloatArray(128) { 2f }
        val shift = FloatArray(128) { -0.5f }
        val result = BardoBonsaiMath.unpatchify(lat, scale, shift)
        assertEquals(32 * 64 * 64, result.size)
        assertTrue(result.all { it == 1.5f })
    }
}
