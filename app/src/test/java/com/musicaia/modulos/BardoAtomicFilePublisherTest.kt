package com.musicaia.modulos

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoAtomicFilePublisherTest {
    @Test
    fun replacesDestinationOnlyAfterStagedFileIsReady() {
        val directory = Files.createTempDirectory("bardo-atomic-publish").toFile()
        try {
            val staged = File(directory, "ready.tmp").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val destination = File(directory, "analysis.wav").apply { writeText("previous-valid-file") }

            BardoAtomicFilePublisher.publish(staged, destination)

            assertTrue(destination.isFile)
            assertEquals(4L, destination.length())
            assertFalse(staged.exists())
            assertEquals(listOf<Byte>(1, 2, 3, 4), destination.readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyStagedFileWithoutTouchingPreviousDestination() {
        val directory = Files.createTempDirectory("bardo-atomic-preserve").toFile()
        try {
            val staged = File(directory, "empty.tmp").apply { createNewFile() }
            val destination = File(directory, "analysis.wav").apply { writeText("keep-this-file") }

            assertThrows(IllegalArgumentException::class.java) {
                BardoAtomicFilePublisher.publish(staged, destination)
            }

            assertEquals("keep-this-file", destination.readText())
            assertTrue(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun refusesCrossDirectoryPublicationBeforeChangingDestination() {
        val root = Files.createTempDirectory("bardo-atomic-paths").toFile()
        val stagedDirectory = File(root, "staged").apply { mkdirs() }
        val destinationDirectory = File(root, "destination").apply { mkdirs() }
        try {
            val staged = File(stagedDirectory, "ready.tmp").apply { writeText("new") }
            val destination = File(destinationDirectory, "analysis.wav").apply { writeText("old") }

            assertThrows(IllegalArgumentException::class.java) {
                BardoAtomicFilePublisher.publish(staged, destination)
            }

            assertEquals("old", destination.readText())
            assertEquals("new", staged.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
