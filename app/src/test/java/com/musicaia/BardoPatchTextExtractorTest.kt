package com.musicaia

import com.musicaia.desarrollo.BardoPatchTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoPatchTextExtractorTest {
    private val diff = """--- a/docs/nota.md
+++ b/docs/nota.md
@@ -1 +1 @@
-texto antiguo
+texto nuevo"""

    @Test
    fun removesSourceEvidenceEnvelopeFromThePatch() {
        val response = diff + """

BARDO_SOURCE_EVIDENCE_V1
path=docs/nota.md
start=1
end=1
quote64=dGV4dG8=
"""
        assertEquals(diff, BardoPatchTextExtractor.extract(response))
    }

    @Test
    fun extractsDiffFromMarkdownFenceAndDropsFollowingExplanation() {
        val response = "Propuesta:\n\`\`\`diff\n" + diff +
            "\n\`\`\`\nEl bloque de evidencia viene después."
        assertEquals(diff, BardoPatchTextExtractor.extract(response))
    }

    @Test
    fun leavesPlainUnifiedDiffUnchanged() {
        assertEquals(diff, BardoPatchTextExtractor.extract(diff))
    }

    @Test
    fun rejectsResponsesWithoutACompleteUnifiedDiff() {
        assertTrue(runCatching { BardoPatchTextExtractor.extract("No hay cambios.") }.isFailure)
        assertTrue(runCatching {
            BardoPatchTextExtractor.extract("--- a/nota.md\ntexto sin destino ni hunks")
        }.isFailure)
    }
}
