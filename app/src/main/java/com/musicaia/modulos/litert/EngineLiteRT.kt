package com.musicaia.modulos.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.Executors

/** Una Engine y una Conversation, siempre serializadas en el mismo hilo de trabajo. */
object EngineLiteRT {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicaIA-LiteRT").apply { isDaemon = true }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    private var cachePath: String? = null
    private var backendGpu = false

    fun inicializar(path: String, cacheDir: String): Boolean =
        executor.submit<Boolean> {
            if (engine != null && conversation != null && modelPath == path) return@submit true
            cerrarInterno()
            modelPath = path
            cachePath = cacheDir

            var ultimoError: Throwable? = null
            val backends = listOf(Backend.GPU(), Backend.CPU())
            for (backend in backends) {
                var candidato: Engine? = null
                var inicializado = false
                try {
                    candidato = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir))
                    candidato.initialize()
                    inicializado = true
                    val nuevaConversacion = candidato.createConversation()
                    engine = candidato
                    conversation = nuevaConversacion
                    backendGpu = backend is Backend.GPU
                    return@submit true
                } catch (error: Throwable) {
                    ultimoError = error
                    // No cerrar Engine si initialize() no llegó a completarse.
                    if (inicializado) runCatching { candidato?.close() }
                }
            }
            throw IllegalStateException("LiteRT-LM no pudo iniciar ni con GPU ni con CPU.", ultimoError)
        }.get()

    fun generar(prompt: String): String =
        executor.submit<String> {
            val actual = conversation ?: throw IllegalStateException("El modelo local no está cargado.")
            try {
                actual.sendMessage(prompt, maxOutputToken = 900).toString()
            } catch (error: Throwable) {
                if (!backendGpu) throw error
                val path = modelPath ?: throw error
                val cache = cachePath ?: throw error
                cerrarInterno()
                val cpu = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache))
                var inicializado = false
                try {
                    cpu.initialize()
                    inicializado = true
                    val nuevaConversacion = cpu.createConversation()
                    engine = cpu
                    conversation = nuevaConversacion
                    backendGpu = false
                    nuevaConversacion.sendMessage(prompt, maxOutputToken = 900).toString()
                } catch (cpuError: Throwable) {
                    if (inicializado && engine !== cpu) runCatching { cpu.close() }
                    throw cpuError
                }
            }
        }.get()

    fun cerrarAsync() {
        executor.execute { cerrarInterno() }
    }

    private fun cerrarInterno() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        backendGpu = false
    }
}
