package com.musicaia.modulos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoCoverArtGeneratorTest {
    @Test
    fun exposesFiveOfflineStyles() {
        assertEquals(5, BardoCoverArtGenerator.STYLES.size)
        assertTrue(BardoCoverArtGenerator.STYLES.contains("Cosmos"))
        assertTrue(BardoCoverArtGenerator.STYLES.contains("Orgánico"))
    }

    @Test
    fun seedIsRepeatableAndDependsOnTheCreativeBrief() {
        val seed = BardoCoverArtGenerator.stableSeed("Luz", "BARDO", "esperanza", "Cosmos")
        assertEquals(seed, BardoCoverArtGenerator.stableSeed("Luz", "BARDO", "esperanza", "Cosmos"))
        assertNotEquals(seed, BardoCoverArtGenerator.stableSeed("Luz", "BARDO", "dolor", "Cosmos"))
    }

    @Test
    fun longTitleIsWrappedIntoBoundedLines() {
        val lines = BardoCoverArtGenerator.wrapTitle("Overcoming every obstacle and finding the light", 16)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.size <= 3)
        assertTrue(lines.all { it.length <= 28 })
    }
}
