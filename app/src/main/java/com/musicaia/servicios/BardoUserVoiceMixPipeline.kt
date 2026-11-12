package com.musicaia.servicios

import com.musicaia.modulos.BardoWavHeaderValidator
import com.musicaia.modulos.LectorWav
import com.musicaia.modulos.MezclaVoz
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Prepara exactamente el WAV que debe archivarse y codificarse:
 * conserva el instrumental si no hay voz; si la hay, genera y valida
 * una mezcla PCM real. Nunca modifica los dos archivos de entrada.
 */
object BardoUserVoiceMixPipeline {
    fun prepareWav(
        instrumentalWav: File,
        voiceWav: File?,
        outputWav: File,
        isCancellationRequested: () -> Boolean = { false }
    ): File {
        fun checkCancellation() {
            if (isCancellationRequested()) {
                throw CancellationException("La preparación del WAV fue cancelada.")
            }
        }

        checkCancellation()
        val instrumentalHeader = BardoWavHeaderValidator.inspect(instrumentalWav)
        require(instrumentalHeader.dataBytes > 0L) {
            "El instrumental no contiene muestras PCM válidas."
        }
        if (voiceWav == null) return instrumentalWav

        require(voiceWav.canonicalFile != instrumentalWav.canonicalFile) {
            "La grabación de voz y el instrumental deben ser archivos distintos."
        }
        require(voiceWav.length() <= LectorWav.MAX_FILE_BYTES) {
            "El WAV de voz supera el límite de seguridad de 120 MiB."
        }
        val voiceHeader = BardoWavHeaderValidator.inspect(voiceWav)
        require(voiceHeader.dataBytes > 0L) {
            "La grabación de voz no contiene muestras PCM válidas."
        }
        checkCancellation()

        val result = MezclaVoz.mezclar(
            rutaVoz = voiceWav.absolutePath,
            rutaInstrumental = instrumentalWav.absolutePath,
            rutaSalida = outputWav.absolutePath,
            isCancellationRequested = isCancellationRequested
        )
        val mixedWav = File(result.rutaWav)
        if (isCancellationRequested()) {
            // La ruta de salida es exclusiva de esta tarea; las fuentes quedan intactas.
            mixedWav.delete()
            throw CancellationException("La mezcla de voz fue cancelada.")
        }
        val mixedHeader = BardoWavHeaderValidator.inspect(mixedWav)
        require(
            mixedHeader.dataBytes > 0L &&
                mixedHeader.sampleRate == result.frecuenciaMuestreo &&
                mixedHeader.bitsPerSample == 16
        ) {
            "El WAV mezclado no superó la validación PCM final."
        }
        return mixedWav
    }
}
