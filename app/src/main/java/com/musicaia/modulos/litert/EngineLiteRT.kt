package com.musicaia.modulos.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.Executors

/**
 * Una Engine y una Conversation, siempre serializadas en el mismo hilo.
 * withSession ejecuta una secuencia completa como una sola tarea del executor,
 * evitando que otra Activity o servicio cierre el modelo entre load y generate.
 */
object EngineLiteRT {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MusicaIA-LiteRT").apply { isDaemon = true }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    private var cachePath: String? = null
    private var backendGpu = false

    interface RuntimeSession {
        fun inicializar(path: String, cacheDir: String): Boolean
        fun estaCargado(path: String): Boolean
        fun generar(prompt: String, reiniciarConversacion: Boolean = false): String
        fun cerrar()
    }

    /**
     * Reserva el executor para toda la operación. Dentro del bloque deben usarse
     * los métodos de RuntimeSession y no los métodos públicos que vuelven a encolar.
     */
    fun <T> withSession(block: RuntimeSession.() -> T): T =
        executor.submit<T> {
            val session = object : RuntimeSession {
                override fun inicializar(path: String, cacheDir: String) =
                    inicializarInterno(path, cacheDir)

                override fun estaCargado(path: String) =
                    estaCargadoInterno(path)

                override fun generar(prompt: String, reiniciarConversacion: Boolean) =
                    generarInterno(prompt, reiniciarConversacion)

                override fun cerrar() = cerrarInterno()
            }
            session.block()
        }.get()

    fun inicializar(path: String, cacheDir: String): Boolean =
        executor.submit<Boolean> { inicializarInterno(path, cacheDir) }.get()

    private fun inicializarInterno(path: String, cacheDir: String): Boolean {
        if (engine != null && conversation != null && modelPath == path) return true
        cerrarInterno()
        modelPath = path
        cachePath = cacheDir

        var ultimoError: Throwable? = null
        val backends = listOf(Backend.GPU(), Backend.CPU())
        for (backend in backends) {
            var candidato: Engine? = null
            var transferidoAlRuntime = false
            try {
                candidato = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir))
                candidato.initialize()
                val nuevaConversacion = candidato.createConversation()
                engine = candidato
                conversation = nuevaConversacion
                backendGpu = backend is Backend.GPU
                transferidoAlRuntime = true
                return true
            } catch (error: Throwable) {
                ultimoError = error
                // Engine puede reservar memoria nativa antes de que initialize()
                // termine. Cierra también los candidatos parcialmente inicializados.
                if (!transferidoAlRuntime) runCatching { candidato?.close() }
                if (error is OutOfMemoryError) {
                    cerrarInterno()
                    throw error
                }
            }
        }
        throw IllegalStateException("LiteRT-LM no pudo iniciar ni con GPU ni con CPU.", ultimoError)
    }

    fun estaCargado(path: String): Boolean =
        executor.submit<Boolean> { estaCargadoInterno(path) }.get()

    private fun estaCargadoInterno(path: String): Boolean =
        engine != null && conversation != null && modelPath == path

    fun generar(prompt: String, reiniciarConversacion: Boolean = false): String =
        executor.submit<String> { generarInterno(prompt, reiniciarConversacion) }.get()

    private fun generarInterno(prompt: String, reiniciarConversacion: Boolean): String {
        if (reiniciarConversacion) {
            val actualEngine = engine
                ?: throw IllegalStateException("El modelo local no está cargado.")
            runCatching { conversation?.close() }
            conversation = actualEngine.createConversation()
        }
        val actual = conversation
            ?: throw IllegalStateException("El modelo local no está cargado.")
        try {
            return actual.sendMessage(prompt, maxOutputToken = 900).toString()
        } catch (error: Throwable) {
            if (error is OutOfMemoryError) {
                cerrarInterno()
                throw error
            }
            if (!backendGpu) throw error
            val path = modelPath ?: throw error
            val cache = cachePath ?: throw error
            cerrarInterno()
            // cerrarInterno limpia referencias; restaurarlas al crear el backend CPU.
            modelPath = path
            cachePath = cache
            val cpu = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache))
            var transferidoAlRuntime = false
            try {
                cpu.initialize()
                val nuevaConversacion = cpu.createConversation()
                engine = cpu
                conversation = nuevaConversacion
                backendGpu = false
                transferidoAlRuntime = true
                return nuevaConversacion.sendMessage(prompt, maxOutputToken = 900).toString()
            } catch (cpuError: Throwable) {
                // Si la inicialización falla, el Engine no llegó al runtime y se
                // libera explícitamente. Si falló la inferencia por OOM, cerrarInterno
                // libera la sesión CPU que ya habíamos transferido.
                if (!transferidoAlRuntime) runCatching { cpu.close() }
                if (cpuError is OutOfMemoryError) cerrarInterno()
                throw cpuError
            }
        }
    }

    /** Libera el modelo en este proceso y espera a que el executor termine. */
    fun cerrar() {
        executor.submit<Unit> { cerrarInterno() }.get()
    }

    /**
     * Cierre en cola: nunca interrumpe una sesión activa; se ejecuta antes o después
     * de la transacción completa, no entre sus operaciones internas.
     */
    fun cerrarAsync(onClosed: (() -> Unit)? = null) {
        executor.execute {
            cerrarInterno()
            runCatching { onClosed?.invoke() }
        }
    }

    private fun cerrarInterno() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        backendGpu = false
        modelPath = null
        cachePath = null
    }
}
