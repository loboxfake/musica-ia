package com.musicaia.servicios

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoTaskProgressTest {
    @Test
    fun timelineFailureDoesNotPreventTaskUpdate() {
        val failure = IOException("disk full")
        var updatePublished = false
        var reported: Exception? = null

        recordTaskProgressSafely(
            record = { throw failure },
            onFailure = { reported = it }
        )
        // The service calls its UI/broadcast update after this helper returns.
        updatePublished = true

        assertTrue(updatePublished)
        assertSame(failure, reported)
    }

    @Test
    fun successfulTimelineWriteDoesNotReportAnError() {
        var writes = 0
        var reports = 0
        recordTaskProgressSafely(
            record = { writes++ },
            onFailure = { reports++ }
        )
        assertEquals(1, writes)
        assertEquals(0, reports)
    }

    @Test
    fun errorReporterFailureIsNotMistakenForTheOriginalStorageFailure() {
        val original = IOException("disk full")
        val reporter = IllegalStateException("logger failed")
        val propagated = runCatching {
            recordTaskProgressSafely(
                record = { throw original },
                onFailure = { throw reporter }
            )
        }.exceptionOrNull()
        assertSame(reporter, propagated)
        assertTrue(propagated !== original)
    }
}
