package com.musicaia.modelo

import android.content.Context
import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.litert.EngineLiteRT
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Arbitra en exclusiva los recursos pesados de texto, generación/procesado de audio, imagen y reconocimiento de voz.
 *
 * El lock de proceso serializa los hilos de esta instancia; el lock de archivo
 * en noBackupFilesDir coordina también el proceso aislado :audio y las tareas
 * de reconocimiento/procesado que compiten por memoria nativa. El bloqueo
 * del archivo se libera automáticamente si Android termina cualquiera de los
 * procesos, por lo que no quedan leases obsoletos tras un crash.
 */
object BardoResourceCoordinator {
    enum class Resource { TEXT_MODEL, AUDIO_MODEL, IMAGE_MODEL, SPEECH_MODEL }

    private val transitionLock = ReentrantLock(true)
    private val activeResource = ThreadLocal<Resource?>()

    /** Preserves the first failure while retaining every later cleanup error. */
    internal fun mergeCleanupFailure(primary: Throwable?, next: Throwable): Throwable {
        if (primary == null) return next
        if (primary !== next) primary.addSuppressed(next)
        return primary
    }

    fun currentResourceForCurrentThread(): Resource? = activeResource.get()

    fun <T> withTextModel(action: () -> T): T =
        withResource(Resource.TEXT_MODEL, null, action)

    fun <T> withTextModel(context: Context, action: () -> T): T =
        withResource(Resource.TEXT_MODEL, context, action)

    fun <T> withAudioModel(action: () -> T): T =
        withResource(Resource.AUDIO_MODEL, null, action)

    fun <T> withAudioModel(context: Context, action: () -> T): T =
        withResource(Resource.AUDIO_MODEL, context, action)

    fun <T> withImageModel(action: () -> T): T =
        withResource(Resource.IMAGE_MODEL, null, action)

    /** Reserva el runtime pesado de imágenes, incluido frente al proceso :audio. */
    fun <T> withImageModel(context: Context, action: () -> T): T =
        withResource(Resource.IMAGE_MODEL, context, action)

    /** Reserva RAM y motores nativos para reconocimiento Vosk/MediaCodec. */
    fun <T> withSpeechModel(context: Context, action: () -> T): T =
        withResource(Resource.SPEECH_MODEL, context, action)

    private fun <T> withResource(
        resource: Resource,
        context: Context?,
        action: () -> T
    ): T = transitionLock.withLock {
        val previous = activeResource.get()
        check(previous == null || previous == resource) {
            "No se puede cambiar del runtime $previous al runtime $resource dentro de la misma operación."
        }

        var lockChannel: FileChannel? = null
        var processLock: FileLock? = null
        var primaryFailure: Throwable? = null
        try {
            // Sólo la operación exterior adquiere el lock entre procesos.
            // Las llamadas anidadas del mismo runtime reutilizan la reserva.
            if (previous == null && context != null) {
                val lockFile = File(context.noBackupFilesDir, "bardo-heavy-runtime.lock")
                lockChannel = FileChannel.open(
                    lockFile.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
                )
                processLock = lockChannel.lock()
            }

            if (previous == null) releaseOtherRuntime(resource)
            activeResource.set(resource)
            var actionFailure: Throwable? = null
            try {
                action()
            } catch (error: Throwable) {
                actionFailure = error
                throw error
            } finally {
                try {
                    // No ocultar fallos de descarga: si no se libera memoria nativa,
                    // no debemos informar como si el runtime hubiera quedado limpio.
                    if (previous == null) releaseRuntime(resource)
                } catch (releaseError: Throwable) {
                    val primary = actionFailure
                    if (primary != null) {
                        primary.addSuppressed(releaseError)
                    } else {
                        throw releaseError
                    }
                } finally {
                    if (previous == null) activeResource.remove()
                    else activeResource.set(previous)
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            // Do not silently treat an unreleased cross-process lease as clean.
            // Attempt both releases and retain them as suppressed if the task failed.
            var lockCleanupFailure: Throwable? = null
            try {
                processLock?.release()
            } catch (error: Throwable) {
                lockCleanupFailure = mergeCleanupFailure(lockCleanupFailure, error)
            }
            try {
                lockChannel?.close()
            } catch (error: Throwable) {
                lockCleanupFailure = mergeCleanupFailure(lockCleanupFailure, error)
            }
            val cleanupError = lockCleanupFailure
            if (cleanupError != null) {
                val primary = primaryFailure
                if (primary == null) throw cleanupError
                mergeCleanupFailure(primary, cleanupError)
            }
        }
    }

    private fun releaseOtherRuntime(requested: Resource) {
        when (requested) {
            Resource.TEXT_MODEL -> AudioGenNative.releaseCachedModels()
            Resource.AUDIO_MODEL -> EngineLiteRT.cerrar()
            Resource.IMAGE_MODEL, Resource.SPEECH_MODEL -> {
                // Image and speech compete with both text and Stable Audio. Always
                // attempt to close both incompatible runtimes even if one close fails.
                var cleanupFailure: Throwable? = null
                try {
                    EngineLiteRT.cerrar()
                } catch (error: Throwable) {
                    cleanupFailure = mergeCleanupFailure(cleanupFailure, error)
                }
                try {
                    AudioGenNative.releaseCachedModels()
                } catch (error: Throwable) {
                    cleanupFailure = mergeCleanupFailure(cleanupFailure, error)
                }
                cleanupFailure?.let { throw it }
            }
        }
    }

    private fun releaseRuntime(resource: Resource) {
        when (resource) {
            Resource.TEXT_MODEL -> EngineLiteRT.cerrar()
            Resource.AUDIO_MODEL -> AudioGenNative.releaseCachedModels()
            // Bonsai closes each Interpreter graph; Vosk closes Recognizer and Model.
            Resource.IMAGE_MODEL, Resource.SPEECH_MODEL -> Unit
        }
    }
}
