package com.musicaia.modulos

import java.io.File

/** Inspección ligera de WAV; comparte el parser RIFF estricto del resto de BARDO. */
object WavSegmentDiagnostics {
    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val frames: Long,
        val dataBytes: Long,
        val durationSeconds: Double
    )

    fun inspect(file: File): Info {
        val header = BardoWavHeaderValidator.inspect(file)
        require(header.channels in 1..2) {
            "Número de canales no admitido para generación musical: \${header.channels}."
        }
        require(header.bitsPerSample == 16) {
            "Se esperaba PCM16; recibido \${header.bitsPerSample} bits."
        }
        val frameAlign = header.channels * 2L
        require(header.dataBytes > 0L && header.dataBytes % frameAlign == 0L) {
            "Bloque PCM vacío o desalineado en \${file.name}."
        }
        val frames = header.dataBytes / frameAlign
        return Info(
            sampleRate = header.sampleRate,
            channels = header.channels,
            bitsPerSample = header.bitsPerSample,
            frames = frames,
            dataBytes = header.dataBytes,
            durationSeconds = frames.toDouble() / header.sampleRate.toDouble()
        )
    }
}
