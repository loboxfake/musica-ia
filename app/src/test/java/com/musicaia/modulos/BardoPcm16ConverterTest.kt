package com.musicaia.modulos

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BardoPcm16ConverterTest {
    private fun shorts(bytes: ByteArray): List<Int> =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { view ->
            List(view.remaining()) { view.get().toInt() }
        }

    @Test
    fun convertsUnsignedEightBitPcmToSignedPcm16() {
        val input = ByteBuffer.wrap(byteArrayOf(0, 0x80.toByte(), 0xff.toByte()))
        assertEquals(listOf(-32768, 0, 32512), shorts(
            BardoPcm16Converter.convert(input, 1, 1, BardoPcm16Converter.PCM_8BIT)
        ))
    }

    @Test
    fun preservesStereoChannelsWithoutDownmixing() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(12_000).putShort((-8_000).toShort())
            .putShort(20_000).putShort((-16_000).toShort())
        input.flip()
        assertEquals(listOf(12_000, -8_000, 20_000, -16_000), shorts(
            BardoPcm16Converter.convert(input, 2, 2, BardoPcm16Converter.PCM_16BIT)
        ))
    }

    @Test
    fun downmixesMultichannelByAveragingAndClampsToPcm16() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(30_000).putShort(30_000).putShort(-30_000).putShort(-30_000)
        input.flip()
        assertEquals(listOf(0), shorts(
            BardoPcm16Converter.convert(input, 4, 1, BardoPcm16Converter.PCM_16BIT)
        ))
    }

    @Test
    fun convertsPacked24BitAndSigned32BitSamples() {
        val pcm24 = ByteBuffer.wrap(byteArrayOf(
            0x00, 0x00, 0x80.toByte(),
            0xff.toByte(), 0xff.toByte(), 0x7f
        ))
        assertEquals(listOf(-32768, 32767), shorts(
            BardoPcm16Converter.convert(pcm24, 1, 1, BardoPcm16Converter.PCM_24BIT_PACKED)
        ))

        val pcm32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MIN_VALUE).putInt(Int.MAX_VALUE)
        pcm32.flip()
        assertEquals(listOf(-32768, 32767), shorts(
            BardoPcm16Converter.convert(pcm32, 1, 1, BardoPcm16Converter.PCM_32BIT)
        ))
    }

    @Test
    fun clampsFloatSamplesAndRejectsNonFiniteValues() {
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-1.5f).putFloat(0.5f).putFloat(1.5f)
        input.flip()
        assertEquals(listOf(-32768, 16383, 32767), shorts(
            BardoPcm16Converter.convert(input, 1, 1, BardoPcm16Converter.PCM_FLOAT)
        ))

        val invalid = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
        invalid.flip()
        assertThrows(IllegalArgumentException::class.java) {
            BardoPcm16Converter.convert(invalid, 1, 1, BardoPcm16Converter.PCM_FLOAT)
        }
    }

    @Test
    fun rejectsStereoOutputWhenDecoderOnlyProvidedMono() {
        assertThrows(IllegalArgumentException::class.java) {
            val mono = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            mono.putShort(1)
            mono.flip()
            BardoPcm16Converter.convert(mono, 1, 2, BardoPcm16Converter.PCM_16BIT)
        }
    }

    @Test
    fun rejectsPartialFramesAndUnsupportedChannelCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            BardoPcm16Converter.convert(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 1, 1, BardoPcm16Converter.PCM_16BIT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BardoPcm16Converter.convert(ByteBuffer.allocate(0), 9, 1, BardoPcm16Converter.PCM_16BIT)
        }
    }
}
