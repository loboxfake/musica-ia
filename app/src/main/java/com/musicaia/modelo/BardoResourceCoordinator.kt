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
 * Arbitra los runtimes pesados de texto, audio e imagen.
 *
 * El lock de proceso serializa los hilos de esta instancia; el lock de archivo
 * en noBackupFilesDir coordina también el proceso aislado :audio. El bloqueo
 * del archivo se libera automáticamente si Android termina cualquiera de los
 * procesos, por lo que no quedan leases obsoletos tras un crash.
 */
object BardoResourceCoordinator {
    enum class Resource { TEXT_MODEL, AUDIO_MODEL, IMAGE_MODEL }

    private val transitionLock = ReentrantLock(true)
    private val activeResource = ThreadLocal<Resource?>()

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

            releaseOtherRuntime(resource)
            activeResource.set(resource)
            try {
                action()
            } finally {
                // Los runtimes se descargan al terminar cada operación. Así una
                // carga retenida en un proceso no compite por RAM con el siguiente
                // runtime, que puede ejecutarse en otro proceso Android.
                runCatching { releaseRuntime(resource) }
                if (previous == null) activeResource.remove()
                else activeResource.set(previous)
            }
        } finally {
            runCatching { processLock?.release() }
            runCatching { lockChannel?.close() }
        }
    }

    private fun releaseOtherRuntime(requested: Resource) {
        when (requested) {
            Resource.TEXT_MODEL -> AudioGenNative.releaseCachedModels()
            Resource.AUDIO_MODEL -> EngineLiteRT.cerrar()
            Resource.IMAGE_MODEL -> {
                EngineLiteRT.cerrar()
                AudioGenNative.releaseCachedModels()
            }
        }
    }

    private fun releaseRuntime(resource: Resource) {
        when (resource) {
            Resource.TEXT_MODEL -> EngineLiteRT.cerrar()
            Resource.AUDIO_MODEL -> AudioGenNative.releaseCachedModels()
            // The Bonsai pipeline owns and closes its own native runtime.
            Resource.IMAGE_MODEL -> Unit
        }
    }
}
