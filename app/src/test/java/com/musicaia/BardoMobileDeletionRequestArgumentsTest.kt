package com.musicaia

import com.musicaia.storage.BardoMobileDeletionRequestArgumentsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoMobileDeletionRequestArgumentsTest {
    private fun args(permanent: String = "false") = mapOf(
        "path" to " Skills/obsolete.bskill ",
        "permanent" to permanent,
        "reason" to " Skill no compatible; el usuario revisará la retirada "
    )

    @Test fun parsesExplicitTrashRequestAndTrimsText() {
        val parsed = BardoMobileDeletionRequestArgumentsParser.parse(args("false"))
        assertEquals("Skills/obsolete.bskill", parsed.relativePath)
        assertFalse(parsed.permanent)
        assertEquals("Skill no compatible; el usuario revisará la retirada", parsed.reason)
    }

    @Test fun parsesExplicitPermanentDeleteChoice() {
        assertTrue(BardoMobileDeletionRequestArgumentsParser.parse(args("TRUE")).permanent)
    }

    @Test fun missingOrAdditionalArgumentsAreRejected() {
        assertTrue(runCatching {
            BardoMobileDeletionRequestArgumentsParser.parse(args().minus("permanent"))
        }.isFailure)
        assertTrue(runCatching {
            BardoMobileDeletionRequestArgumentsParser.parse(args() + ("extra" to "ignored"))
        }.isFailure)
    }

    @Test fun ambiguousPermanentFlagIsRejectedInsteadOfDefaultingToTrash() {
        for (value in listOf("", "yes", "1", "trash", "false-or-true")) {
            assertTrue(value, runCatching {
                BardoMobileDeletionRequestArgumentsParser.parse(args(value))
            }.isFailure)
        }
    }

    @Test fun emptyReasonAndOversizedPathAreRejected() {
        assertTrue(runCatching {
            BardoMobileDeletionRequestArgumentsParser.parse(args().plus("reason" to "  "))
        }.isFailure)
        assertTrue(runCatching {
            BardoMobileDeletionRequestArgumentsParser.parse(args().plus("path" to "x".repeat(513)))
        }.isFailure)
    }
}
