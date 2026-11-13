package com.musicaia.importacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoImageVisionTest {
    @Test
    fun removesWeakDuplicateAndBlankLabels() {
        val filtered = BardoImageVision.filterLabels(listOf(
            BardoImageVision.Label("Cat", 0.91f),
            BardoImageVision.Label("cat", 0.80f),
            BardoImageVision.Label("Tree", 0.62f),
            BardoImageVision.Label("Weak", 0.12f),
            BardoImageVision.Label(" ", 0.99f)
        ))
        assertEquals(listOf("Cat", "Tree"), filtered.map { it.text })
        assertTrue(filtered.all { it.confidence >= 0.30f })
    }

    @Test
    fun ordersAndLimitsPredictions() {
        val filtered = BardoImageVision.filterLabels((1..20).map {
            BardoImageVision.Label("label-$it", it / 20f)
        })
        assertEquals(12, filtered.size)
        assertEquals("label-20", filtered.first().text)
    }
}
