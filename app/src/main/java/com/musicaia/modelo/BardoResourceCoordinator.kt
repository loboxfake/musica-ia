package com.musicaia.modelo

import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.litert.EngineLiteRT
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Arbitra los runtimes de texto y audio de los flujos coordinados.
 * Antes de usar uno, intenta liberar el otro; no controla la RAM de otras aplicaciones.
 */
object BardoResourceCoordinator {
    enum class Resource { TEXT_MODEL, AUDIO_MODEL }

    private val transitionLock = ReentrantLock(true)
    private val activeResource = ThreadLocal<Resource?>()

    fun currentResourceForCurrentThread(): Resource? = activeResource.get()

    fun <T> withTextModel(action: () -> T): T =
        withResource(Resource.TEXT_MODEL, action)

    fun <T> withAudioModel(action: () -> T): T =
        withResource(Resource.AUDIO_MODEL, action)

    private fun <T> withResource(resource: Resource, action: () -> T): T =
        transitionLock.withLock {
            val previous = activeResource.get()
            releaseOtherRuntime(resource)
            activeResource.set(resource)
            var completed = false
            try {
                val result = action()
                completed = true
                result
            } finally {
                // Libera un motor anidado al volver al anterior. Si una operación
                // de primer nivel falla (incluido OOM), libera posibles cargas parciales.
                val mustRelease = (previous != null && previous != resource) ||
                    (previous == null && !completed)
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
        }
    }

    private fun releaseRuntime(resource: Resource) {
        when (resource) {
            Resource.TEXT_MODEL -> EngineLiteRT.cerrar()
            Resource.AUDIO_MODEL -> AudioGenNative.releaseCachedModels()
        }
    }
}
