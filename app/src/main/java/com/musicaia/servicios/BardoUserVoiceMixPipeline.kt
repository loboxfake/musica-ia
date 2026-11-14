package com.musicaia.servicios

import com.musicaia.modulos.BardoWavHeaderValidator
import com.musicaia.modulos.BardoAtomicFilePublisher
import com.musicaia.modulos.LectorWav
import com.musicaia.modulos.BardoStereoVoiceMixer
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
        if (voiceWav == null) {
            require(instrumentalWav.canonicalFile != outputWav.canonicalFile) {
                "El destino WAV debe ser distinto del instrumental original."
            }
            BardoAtomicFilePublisher.copyAndPublish(
                source = instrumentalWav,
                destination = outputWav,
                isCancellationRequested = isCancellationRequested
            )
            val archivedHeader = BardoWavHeaderValidator.inspect(outputWav)
            require(
                archivedHeader.dataBytes == instrumentalHeader.dataBytes &&
                    archivedHeader.sampleRate == instrumentalHeader.sampleRate &&
                    archivedHeader.channels == instrumentalHeader.channels &&
                    archivedHeader.bitsPerSample == instrumentalHeader.bitsPerSample
            ) { "El WAV archivado no coincide con el instrumental original." }
            return outputWav
        }

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

        val result = BardoStereoVoiceMixer.mix(
            voiceFile = voiceWav,
            instrumentalFile = instrumentalWav,
            outputFile = outputWav,
            isCancellationRequested = isCancellationRequested
        )
        // El mezclador comprueba la cancelación hasta antes de publicar atómicamente.
        // Una vez publicado el destino, la operación ya está completada: no se debe
        // borrar la salida porque una cancelación tardía podría destruir una versión válida.
        val mixedWav = result.output
        val mixedHeader = BardoWavHeaderValidator.inspect(mixedWav)
        require(
            mixedHeader.dataBytes > 0L &&
                mixedHeader.sampleRate == result.sampleRate &&
                mixedHeader.bitsPerSample == 16
        ) {
            "El WAV mezclado no superó la validación PCM final."
        }
        return mixedWav
    }
}
