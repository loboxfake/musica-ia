package com.musicaia.modelo

import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.litert.EngineLiteRT
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Arbitra los dos runtimes que más memoria nativa reservan en este teléfono.
 * Nunca mantiene Gemma y Stable Audio cargados por decisión de BARDO a la vez.
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
            try {
                action()
            } finally {
                // Si una operación anidada cambió de motor, libera el motor
                // anidado antes de devolver el control al recurso anterior.
                if (previous != null && previous != resource) {
                    releaseRuntime(resource)
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
