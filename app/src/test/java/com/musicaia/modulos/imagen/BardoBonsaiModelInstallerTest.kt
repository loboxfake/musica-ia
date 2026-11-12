package com.musicaia.modulos.imagen

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun verifiedMarkerDetectsSameSizeFileChangesWithoutRehashingOnEveryRead() {
        val directory = Files.createTempDirectory("bardo-bonsai-marker").toFile()
        try {
            val file = File(directory, "small-model.bin")
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val modified = file.lastModified()
            val spec = BardoBonsaiModelInstaller.FileSpec(
                path = "small-model.bin",
                size = 5L,
                sha256 = "a".repeat(64)
            )

            BardoBonsaiModelInstaller.writeVerifiedMarker(directory, "revision-1", listOf(spec))
            assertTrue(
                BardoBonsaiModelInstaller.isVerifiedInstall(directory, "revision-1", listOf(spec))
            )

            file.writeBytes(byteArrayOf(5, 4, 3, 2, 1))
            file.setLastModified(modified + 5_000L)
            assertFalse(
                BardoBonsaiModelInstaller.isVerifiedInstall(directory, "revision-1", listOf(spec))
            )
            assertFalse(
                BardoBonsaiModelInstaller.isVerifiedInstall(directory, "revision-2", listOf(spec))
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun oldRevisionOnlyMarkerIsNotTrustedAsAFileIntegrityManifest() {
        val directory = Files.createTempDirectory("bardo-bonsai-legacy-marker").toFile()
        try {
            File(directory, ".verified").writeText("revision-1")
            val file = File(directory, "small-model.bin")
            file.writeBytes(byteArrayOf(1, 2, 3))
            val spec = BardoBonsaiModelInstaller.FileSpec("small-model.bin", 3L, null)

            assertFalse(
                BardoBonsaiModelInstaller.isVerifiedInstall(directory, "revision-1", listOf(spec))
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
