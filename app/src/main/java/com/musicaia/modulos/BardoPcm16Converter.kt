package com.musicaia.modulos

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conversión PCM de MediaCodec a PCM16 little-endian.
 * No depende de Android para permitir pruebas deterministas en JVM.
 */
internal object BardoPcm16Converter {
    const val PCM_8BIT = 3
    const val PCM_16BIT = 2
    const val PCM_24BIT_PACKED = 21
    const val PCM_32BIT = 22
    const val PCM_FLOAT = 4

    fun convert(
        input: ByteBuffer,
        inputChannels: Int,
        outputChannels: Int,
        encoding: Int
    ): ByteArray {
        require(inputChannels in 1..8)
        require(outputChannels == 1 || outputChannels == 2)
        require(outputChannels == 2 || inputChannels == 1 || inputChannels > 2)
        val bytesPerSample = when (encoding) {
            PCM_8BIT -> 1
            PCM_16BIT -> 2
            PCM_24BIT_PACKED -> 3
            PCM_32BIT, PCM_FLOAT -> 4
            else -> error("Formato PCM no admitido.")
        }
        val frameBytes = bytesPerSample * inputChannels
        require(input.remaining() % frameBytes == 0) {
            "El búfer PCM termina en mitad de una muestra."
        }
        val frames = input.remaining() / frameBytes
        val outputBytes = frames.toLong() * outputChannels * 2L
        require(outputBytes <= Int.MAX_VALUE) { "El bloque PCM es demasiado grande." }
        val source = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        val result = ByteBuffer.allocate(outputBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val values = IntArray(inputChannels)
        repeat(frames) {
            repeat(inputChannels) { channel -> values[channel] = readSample(source, encoding) }
            if (outputChannels == 2) {
                result.putShort(values[0].coerceIn(-32768, 32767).toShort())
                result.putShort(values[1].coerceIn(-32768, 32767).toShort())
            } else {
                val sample = if (inputChannels == 1) values[0]
                else values.average().toInt()
                result.putShort(sample.coerceIn(-32768, 32767).toShort())
            }
        }
        return result.array()
    }

    private fun readSample(source: ByteBuffer, encoding: Int): Int = when (encoding) {
        PCM_8BIT -> ((source.get().toInt() and 0xff) - 128) shl 8
        PCM_16BIT -> source.getShort().toInt()
        PCM_24BIT_PACKED -> {
            val raw = (source.get().toInt() and 0xff) or
                ((source.get().toInt() and 0xff) shl 8) or
                ((source.get().toInt() and 0xff) shl 16)
            (if ((raw and 0x800000) != 0) raw or -0x1000000 else raw) shr 8
        }
        PCM_32BIT -> source.getInt() shr 16
        PCM_FLOAT -> {
            val sample = source.getFloat()
            require(sample.isFinite()) { "El decodificador produjo una muestra float no finita." }
            val bounded = sample.coerceIn(-1.0f, 1.0f)
            if (bounded <= -1.0f) -32768 else (bounded * 32767.0f).toInt()
        }
        else -> error("Formato PCM no admitido.")
    }
}
