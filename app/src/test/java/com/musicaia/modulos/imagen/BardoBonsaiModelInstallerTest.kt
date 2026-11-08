package com.musicaia.modulos.imagen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoBonsaiModelInstallerTest {
    @Test fun pinnedAssetSizesAddUpToTheAdvertisedDownload() {
        assertEquals(BardoBonsaiModelInstaller.TOTAL_BYTES, BardoBonsaiModelInstaller.files.sumOf { it.size })
        assertEquals("pipeline_meta.json", BardoBonsaiModelInstaller.files.first().path)
        assertEquals(6, BardoBonsaiModelInstaller.files.size)
    }

    @Test fun everyModelGraphAndTokenizerAssetHasSha256Pinned() {
        val checked = BardoBonsaiModelInstaller.files.filter { it.sha256 != null }
        assertEquals(5, checked.size)
        assertTrue(checked.all { it.sha256!!.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(BardoBonsaiModelInstaller.files.single { it.path == "pipeline_meta.json" }.sha256 == null)
    }

    @Test fun revisionIsImmutableCommitAndFreeSpaceIncludesSafetyReserve() {
        assertTrue(BardoBonsaiModelInstaller.REVISION.matches(Regex("[0-9a-f]{40}")))
        assertEquals(
            BardoBonsaiModelInstaller.TOTAL_BYTES + 512L * 1024L * 1024L,
            BardoBonsaiModelInstaller.REQUIRED_FREE_BYTES
        )
    }
}
