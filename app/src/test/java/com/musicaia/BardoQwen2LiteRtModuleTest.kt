package com.musicaia

import com.musicaia.modulos.litert.BardoQwen2LiteRtModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoQwen2LiteRtModuleTest {
    @Test
    fun pinnedModelManifestHasExpectedProvenanceAndIntegrity() {
        assertEquals("litert-community/Qwen2-0.5B-Instruct", BardoQwen2LiteRtModule.REPOSITORY)
        assertEquals(647_377_840L, BardoQwen2LiteRtModule.EXPECTED_SIZE_BYTES)
        assertEquals(
            "0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157",
            BardoQwen2LiteRtModule.EXPECTED_SHA256
        )
        assertEquals("Apache-2.0", BardoQwen2LiteRtModule.LICENSE)
        assertTrue(BardoQwen2LiteRtModule.DOWNLOAD_URL.startsWith("https://"))
    }
}
