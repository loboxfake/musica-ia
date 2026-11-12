package com.musicaia.modulos

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BardoOfflineAsrTest {
    @Test
    fun downmixesStereoPcm16ToMono() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1000).putShort(-1000).putShort(3000).putShort(1000).apply { flip() }
        val output = BardoOfflineAsr.convertToMonoPcm16(input, 2, AudioFormat.ENCODING_PCM_16BIT)
        val samples = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, samples.getShort().toInt())
        assertEquals(2000, samples.getShort().toInt())
    }

    @Test
    fun clampsFloatSamplesIntoPcm16Range() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-1.5f).putFloat(0.5f).apply { flip() }
        val output = BardoOfflineAsr.convertToMonoPcm16(input, 1, AudioFormat.ENCODING_PCM_FLOAT)
        val samples = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32768, samples.getShort().toInt())
        assertEquals(16383, samples.getShort().toInt())
    }

    @Test
    fun rejectsIncompletePcmFrame() {
        val input = ByteBuffer.allocate(3).put(byteArrayOf(1, 2, 3)).apply { flip() }
        assertThrows(IllegalArgumentException::class.java) {
            BardoOfflineAsr.convertToMonoPcm16(input, 2, AudioFormat.ENCODING_PCM_16BIT)
        }
    }

    @Test
    fun reportsNoAudioTrackForAMissingMediaFileWithoutThrowing() {
        assertFalse(BardoOfflineAsr.hasAudioTrack(File("/definitely/not/a/media/file.mp4")))
    }

    @Test
    fun exposesIndependentSpanishAndEnglishModelProfiles() {
        val spanish = BardoOfflineAsr.Language.SPANISH
        val english = BardoOfflineAsr.Language.ENGLISH
        assertEquals(39_817_833L, spanish.archiveSizeBytes)
        assertEquals("09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f", spanish.archiveSha256)
        assertEquals(41_205_931L, english.archiveSizeBytes)
        assertEquals("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", english.archiveSha256)
        assertEquals("https", java.net.URL(spanish.archiveUrl).protocol)
        assertEquals("https", java.net.URL(english.archiveUrl).protocol)
        assertFalse(spanish.archiveSha256 == english.archiveSha256)
        assertFalse(spanish.directoryName == english.directoryName)
    }

    @Test
    fun decoderIdleTimeoutHasAnExplicitBoundAndResetsOnProgress() {
        val timeoutNanos = BardoOfflineAsr.MAX_DECODER_IDLE_MILLIS * 1_000_000L
        val startedAt = 1_000_000_000L

        assertFalse(BardoOfflineAsr.decoderIdleExpired(startedAt, startedAt + timeoutNanos - 1L))
        assertEquals(true, BardoOfflineAsr.decoderIdleExpired(startedAt, startedAt + timeoutNanos))
        val progressAt = startedAt + timeoutNanos - 5_000_000L
        assertFalse(BardoOfflineAsr.decoderIdleExpired(progressAt, startedAt + timeoutNanos))
        assertEquals(60_000L, BardoOfflineAsr.MAX_DECODER_IDLE_MILLIS)
    }

    @Test
    fun offlineSpeechCancellationIsCooperativeAndExplicit() {
        BardoOfflineAsr.checkNotCancelled(null)
        BardoOfflineAsr.checkNotCancelled(AtomicBoolean(false))

        val cancelled = runCatching {
            BardoOfflineAsr.checkNotCancelled(AtomicBoolean(true))
        }.exceptionOrNull()

        assertTrue(cancelled is CancellationException)
        assertEquals("Transcripción offline cancelada.", cancelled?.message)
    }

    @Test
    fun hasPinnedSpanishModelIntegrityMetadata() {
        assertEquals(39_817_833L, BardoOfflineAsr.MODEL_ARCHIVE_SIZE_BYTES)
        assertEquals("09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f",
            BardoOfflineAsr.MODEL_ARCHIVE_SHA256)
        assertEquals("https", java.net.URL(BardoOfflineAsr.MODEL_ARCHIVE_URL).protocol)
    }
}