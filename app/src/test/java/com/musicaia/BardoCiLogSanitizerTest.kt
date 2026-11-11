package com.musicaia

import com.musicaia.desarrollo.BardoCiLogSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoCiLogSanitizerTest {
    @Test
    fun removesGithubTokensAuthorizationValuesAndSignedUrlQueries() {
        val raw = """
            GITHUB_TOKEN=github_pat_1234567890abcdefghijklmnop
            Authorization: Bearer ghp_1234567890abcdefghijklmnopqrstuv
            upload https://example.blob.core.windows.net/log.txt?sig=private-signature&se=tomorrow
            FAILURE: Build failed
        """.trimIndent()
        val safe = BardoCiLogSanitizer.selectRelevantExcerpt(raw)
        assertFalse(safe.contains("github_pat_"))
        assertFalse(safe.contains("ghp_123"))
        assertFalse(safe.contains("private-signature"))
        assertTrue(safe.contains("<REDACTED>"))
        assertTrue(safe.contains("FAILURE:"))
    }

    @Test
    fun selectsFailureContextAndBoundsTheOutput() {
        val raw = buildString {
            repeat(150) { appendLine("ordinary runner output line $it") }
            appendLine("FAILURE: Build failed with an exception.")
            repeat(100) { appendLine("Caused by: synthetic diagnostic line $it") }
        }
        val safe = BardoCiLogSanitizer.selectRelevantExcerpt(raw, maxChars = 1000)
        assertTrue(safe.length <= 1000)
        assertTrue(safe.contains("FAILURE:"))
        assertTrue(safe.contains("Caused by:"))
    }

    @Test
    fun emptyLogsAndInvalidLimitsAreHandledDeterministically() {
        assertEquals(
            "GitHub devolvió un log vacío.",
            BardoCiLogSanitizer.selectRelevantExcerpt("  ")
        )
        assertTrue(runCatching {
            BardoCiLogSanitizer.selectRelevantExcerpt("log", 100)
        }.isFailure)
    }
}
