package com.musicaia.modelo

import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.litert.EngineLiteRT
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Arbitra los runtimes pesados de texto, audio e imagen de los flujos coordinados.
 * Las transiciones son exclusivas dentro del proceso; no controla la RAM de otras aplicaciones.
 */
object BardoResourceCoordinator {
    enum class Resource { TEXT_MODEL, AUDIO_MODEL, IMAGE_MODEL }

    private val transitionLock = ReentrantLock(true)
    private val activeResource = ThreadLocal<Resource?>()

    fun currentResourceForCurrentThread(): Resource? = activeResource.get()

    fun <T> withTextModel(action: () -> T): T =
        withResource(Resource.TEXT_MODEL, action)

    fun <T> withAudioModel(action: () -> T): T =
        withResource(Resource.AUDIO_MODEL, action)

    /** Reserva el runtime pesado de imágenes e impide inferencias de texto/audio concurrentes. */
    fun <T> withImageModel(action: () -> T): T =
        withResource(Resource.IMAGE_MODEL, action)

    private fun <T> withResource(resource: Resource, action: () -> T): T =
        transitionLock.withLock {
            val previous = activeResource.get()
            check(previous == null || previous == resource) {
                "No se puede cambiar del runtime $previous al runtime $resource dentro de la misma operación."
            }
            releaseOtherRuntime(resource)
            activeResource.set(resource)
            var completed = false
            try {
                val result = action()
                completed = true
                result
            } finally {
                // Si una operación de primer nivel falla (incluido OOM),
                // libera cualquier carga parcial del runtime solicitado.
                val mustRelease = !completed
                if (mustRelease) {
                    runCatching { releaseRuntime(resource) }
                }
                if (previous == null) {
                    activeResource.remove()
                } else {
                    activeResource.set(previous)
                }
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
