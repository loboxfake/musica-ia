package com.musicaia.modulos

/** Exportación PCM WAV -> MP3 mediante LAME nativo, sin servicios ni red. */
object Mp3Exporter {
    val available: Boolean = runCatching {
        System.loadLibrary("mp3encoder")
    }.isSuccess

    /** Codifica WAV PCM16 mono/estéreo a MP3 CBR (128..320 kbps). */
    external fun encodeWavToMp3(
        inputPath: String,
        outputPath: String,
        bitrateKbps: Int
    ): Boolean
}
