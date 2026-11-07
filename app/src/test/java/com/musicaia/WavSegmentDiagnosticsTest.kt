package com.musicaia

import com.musicaia.modulos.WavSegmentDiagnostics
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WavSegmentDiagnosticsTest {
    @Test
    fun readsPcmFramesDurationAndSampleRate() {
        val wav = createPcm16Wav(sampleRate = 44_100, channels = 2, frames = 44_100)
        try {
            val info = WavSegmentDiagnostics.inspect(wav)
            assertEquals(44_100, info.sampleRate)
            assertEquals(2, info.channels)
            assertEquals(16, info.bitsPerSample)
            assertEquals(44_100L, info.frames)
            assertEquals(1.0, info.durationSeconds, 0.0001)
        } finally {
            wav.delete()
        }
    }

    @Test
    fun rejectsTruncatedWavChunks() {
        val wav = File.createTempFile("bardo-truncated-", ".wav")
        try {
            wav.writeBytes("RIFF".toByteArray() + ByteArray(8) + "WAVE".toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                WavSegmentDiagnostics.inspect(wav)
            }
        } finally {
            wav.delete()
        }
    }

    private fun createPcm16Wav(sampleRate: Int, channels: Int, frames: Int): File {
        val dataBytes = frames * channels * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        repeat(frames * channels) { buffer.putShort(0) }
        val file = File.createTempFile("bardo-segment-", ".wav")
        file.writeBytes(buffer.array())
        return file
    }
}
