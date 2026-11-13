package com.musicaia.modulos.voice

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BardoSpanishG2pOnnxEngineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rejectsMissingModelBeforeCreatingRuntimeSession() {
        val result = BardoSpanishG2pOnnxEngine.open(File(temp.root, "missing.onnx"), Long.MAX_VALUE)
        assertFalse(result.isLoaded)
        assertTrue(result.detail.contains("no existe", ignoreCase = true))
    }

    @Test fun rejectsNonOnnxFileBeforeCreatingRuntimeSession() {
        val model = temp.newFile("model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = BardoSpanishG2pOnnxEngine.open(model, Long.MAX_VALUE)
        assertFalse(result.isLoaded)
        assertTrue(result.detail.contains(".onnx"))
    }

    @Test fun refusesToOpenWhenAvailableMemoryIsBelowSafetyThreshold() {
        val model = temp.newFile("model.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = BardoSpanishG2pOnnxEngine.open(
            model,
            BardoSpanishG2pOnnxEngine.MIN_FREE_MEMORY_BYTES - 1
        )
        assertFalse(result.isLoaded)
        assertTrue(result.detail.contains("Memoria"))
    }

    @Test fun refusesOversizedModelBeforeReadingOrOpeningIt() {
        val model = temp.newFile("large.onnx")
        RandomAccessFile(model, "rw").use {
            it.setLength(BardoSpanishG2pOnnxEngine.MAX_MODEL_BYTES + 1)
        }
        val result = BardoSpanishG2pOnnxEngine.open(model, Long.MAX_VALUE)
        assertFalse(result.isLoaded)
        assertTrue(result.detail.contains("64 MiB"))
    }
}
