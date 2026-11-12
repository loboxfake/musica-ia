package com.musicaia.modulos.voice

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiffSingerPhonemeInventoryReaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(content: String): File = temp.newFile("phonemes.txt").apply {
        writeText(content, StandardCharsets.UTF_8)
    }

    @Test fun readsStableZeroBasedIdsAndIgnoresCommentsAndBlankLines() {
        val result = DiffSingerPhonemeInventoryReader.read(
            file("\uFEFF# voice inventory\nSP\n\na\ne\n# end\n")
        )
        assertTrue(result.isValid)
        assertEquals(mapOf("SP" to 0, "a" to 1, "e" to 2), result.phonemeToId)
    }

    @Test fun rejectsMissingSilenceToken() {
        val result = DiffSingerPhonemeInventoryReader.read(file("a\ne\ni\n"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("SP") })
    }

    @Test fun rejectsDuplicateSymbolsInsteadOfSilentlyChangingIds() {
        val result = DiffSingerPhonemeInventoryReader.read(file("SP\na\na\n"))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("duplicado") })
    }

    @Test fun rejectsWhitespaceInsideSymbol() {
        val result = DiffSingerPhonemeInventoryReader.read(file("SP\na b\n"))
        assertFalse(result.isValid)
    }

    @Test fun rejectsInvalidUtf8() {
        val invalid = temp.newFile("invalid.txt")
        invalid.writeBytes(byteArrayOf(0x53, 0x50, 0x0A, 0xC3.toByte(), 0x28))
        assertFalse(DiffSingerPhonemeInventoryReader.read(invalid).isValid)
    }

    @Test fun rejectsOversizedInventoryFile() {
        val oversized = temp.newFile("large.txt")
        oversized.writeBytes(ByteArray((DiffSingerPhonemeInventoryReader.MAX_FILE_BYTES + 1).toInt()))
        assertFalse(DiffSingerPhonemeInventoryReader.read(oversized).isValid)
    }
}
