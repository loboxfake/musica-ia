package com.musicaia.modulos

import java.io.File

/** Exportación PCM WAV -> MP3 mediante LAME nativo, sin servicios ni red. */
object Mp3Exporter {
    val available: Boolean = runCatching {
        System.loadLibrary("mp3encoder")
    }.isSuccess

    /**
     * Comprueba la compatibilidad real del WAV con el parser JNI y codifica.
     * Así se evitan errores genéricos cuando la cabecera es WAV válida pero
     * su frecuencia/canales no están soportados por esta versión de LAME.
     */
    fun validateInputWav(file: File): BardoWavHeader {
        val header = BardoWavHeaderValidator.inspect(file)
        require(header.bitsPerSample == 16 && header.channels in 1..2) {
            "El exportador MP3 solo admite WAV PCM16 mono o estéreo."
        }
        require(header.sampleRate in 32_000..48_000) {
            "El exportador MP3 admite frecuencias de 32 a 48 kHz; el WAV tiene ${header.sampleRate} Hz."
        }
        require(header.dataBytes > 0L) { "El WAV no contiene audio PCM." }
        return header
    }

    fun encodeValidatedWavToMp3(
        inputPath: String,
        outputPath: String,
        bitrateKbps: Int
    ): Boolean {
        require(bitrateKbps in 128..320) { "El bitrate MP3 debe estar entre 128 y 320 kbps." }
        val input = File(inputPath).canonicalFile
        val output = File(outputPath).canonicalFile
        require(input != output) { "La salida MP3 no puede sobrescribir el WAV original." }
        validateInputWav(input)
        check(available) { "El codificador MP3 nativo no está disponible en esta compilación." }
        return encodeWavToMp3(input.absolutePath, output.absolutePath, bitrateKbps)
    }

    /** Codifica WAV PCM16 mono/estéreo a MP3 CBR (128..320 kbps). */
    external fun encodeWavToMp3(
        inputPath: String,
        outputPath: String,
        bitrateKbps: Int
    ): Boolean
}
