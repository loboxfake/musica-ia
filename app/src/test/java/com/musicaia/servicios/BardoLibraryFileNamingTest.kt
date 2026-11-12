package com.musicaia.servicios

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoLibraryFileNamingTest {
    @Test
    fun sameStyleAndTimestampStillProduceDistinctLibraryNames() {
        val first = BardoLibraryFileNaming.createBaseName(
            1_794_480_000_000L, "Rock / Pop", "123e4567-e89b-42d3-a456-426614174000"
        )
        val second = BardoLibraryFileNaming.createBaseName(
            1_794_480_000_000L, "Rock / Pop", "123e4567-e89b-42d3-a456-426614174001"
        )

        assertNotEquals(first, second)
        assertTrue(first.startsWith("BARDO_"))
        assertTrue(first.contains("_Rock_Pop_"))
        assertTrue(first.matches(Regex("^BARDO_[0-9]{8}_[0-9]{6}_[0-9]{3}_Rock_Pop_[0-9a-f-]{36}$")))
    }

    @Test
    fun emptyOrUnsafeStyleFallsBackToSafeInstrumentalLabel() {
        val name = BardoLibraryFileNaming.createBaseName(
            1_794_480_000_000L, "../../", "123e4567-e89b-42d3-a456-426614174000"
        )
        assertTrue(name.contains("_instrumental_"))
        assertEquals(false, name.contains("/"))
    }
}
