package com.musicaia.modulos.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * Desenvuelve las excepciones de Future.get para que los consumidores distingan
 * errores fatales (OOM/JNI) y cancelaciones de fallos ordinarios.
 */
internal fun <T> awaitFuture(future: Future<T>): T {
    try {
        return future.get()
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.util.concurrent.CancellationException(
            "La espera de una operación LiteRT fue interrumpida."
        ).also { it.initCause(interrupted) }
    } catch (wrapped: ExecutionException) {
        val cause = wrapped.cause ?: wrapped
        when (cause) {
            is Error -> throw cause
            is RuntimeException -> throw cause
            else -> throw IllegalStateException("La operación LiteRT falló.", cause)
        }
    }
}

/** No reintentar en CPU tras una cancelación o una falta real de memoria. */
internal object LiteRtFallbackPolicy {
    fun shouldRetryCpu(error: Throwable): Boolean =
        error !is java.util.concurrent.CancellationException &&
            error !is OutOfMemoryError
}

/** Ejecuta todos los cierres nativos y no oculta ningún fallo de liberación. */
internal fun closeResourcesOrThrow(vararg closeActions: () -> Unit) {
    var failure: Throwable? = null
    closeActions.forEach { action ->
        try {
            action()
        } catch (error: Throwable) {
            val primary = failure
            if (primary == null) failure = error
            else if (primary !== error) primary.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

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
        }.let(::awaitFuture)

    fun inicializar(path: String, cacheDir: String): Boolean =
        executor.submit<Boolean> { inicializarInterno(path, cacheDir) }.let(::awaitFuture)

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
                // termine. Si no se consigue cerrar el candidato, no se intenta CPU:
                // la memoria nativa podría seguir ocupada.
                var cleanupFailed = false
                if (!transferidoAlRuntime) {
                    try {
                        candidato?.close()
                    } catch (cleanupError: Throwable) {
                        if (error !== cleanupError) error.addSuppressed(cleanupError)
                        cleanupFailed = true
                    }
                }
                if (cleanupFailed || !LiteRtFallbackPolicy.shouldRetryCpu(error)) {
                    try {
                        cerrarInterno()
                    } catch (cleanupError: Throwable) {
                        if (error !== cleanupError) error.addSuppressed(cleanupError)
                    }
                    throw error
                }
            }
        }
        throw IllegalStateException("LiteRT-LM no pudo iniciar ni con GPU ni con CPU.", ultimoError)
    }

    fun estaCargado(path: String): Boolean =
        executor.submit<Boolean> { estaCargadoInterno(path) }.let(::awaitFuture)

    private fun estaCargadoInterno(path: String): Boolean =
        engine != null && conversation != null && modelPath == path

    fun generar(prompt: String, reiniciarConversacion: Boolean = false): String =
        executor.submit<String> { generarInterno(prompt, reiniciarConversacion) }.let(::awaitFuture)

    private fun generarInterno(prompt: String, reiniciarConversacion: Boolean): String {
        try {
            if (reiniciarConversacion) {
                val actualEngine = engine
                    ?: throw IllegalStateException("El modelo local no está cargado.")
                val previousConversation = conversation
                conversation = null
                previousConversation?.close()
                conversation = actualEngine.createConversation()
            }
            val actual = conversation
                ?: throw IllegalStateException("El modelo local no está cargado.")
            return actual.sendMessage(prompt, maxOutputToken = 900).toString()
        } catch (error: Throwable) {
            if (!LiteRtFallbackPolicy.shouldRetryCpu(error) || !backendGpu) {
                try {
                    cerrarInterno()
                } catch (cleanupError: Throwable) {
                    if (error !== cleanupError) error.addSuppressed(cleanupError)
                }
                throw error
            }
            val path = modelPath ?: throw error
            val cache = cachePath ?: throw error
            try {
                cerrarInterno()
            } catch (cleanupError: Throwable) {
                if (error !== cleanupError) error.addSuppressed(cleanupError)
                throw error
            }
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
                // No ocultar tampoco los fallos al cerrar el candidato CPU o la sesión
                // CPU que ya había pasado a ser propiedad del runtime.
                try {
                    if (!transferidoAlRuntime) cpu.close()
                    else cerrarInterno()
                } catch (cleanupError: Throwable) {
                    if (cpuError !== cleanupError) cpuError.addSuppressed(cleanupError)
                }
                throw cpuError
            }
        }
    }

    /** Libera el modelo en este proceso y espera a que el executor termine. */
    fun cerrar() {
        executor.submit<Unit> { cerrarInterno() }.let(::awaitFuture)
    }

    /**
     * Cierre en cola: nunca interrumpe una sesión activa; se ejecuta antes o después
     * de la transacción completa, no entre sus operaciones internas.
     */
    fun cerrarAsync(
        onClosed: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        executor.execute {
            try {
                cerrarInterno()
            } catch (error: Throwable) {
                // OOM is process-critical; do not turn it into an ordinary async close callback.
                if (error is OutOfMemoryError) throw error
                android.util.Log.e("BARDO-LiteRT", "No se pudieron liberar todos los recursos nativos de texto.", error)
                try {
                    onFailure?.invoke(error)
                } catch (callbackError: Throwable) {
                    if (error !== callbackError) error.addSuppressed(callbackError)
                    android.util.Log.e("BARDO-LiteRT", "Falló el callback de error del cierre nativo.", callbackError)
                }
                return@execute
            }
            try {
                onClosed?.invoke()
            } catch (callbackError: Throwable) {
                android.util.Log.e("BARDO-LiteRT", "Falló el callback posterior al cierre de LiteRT.", callbackError)
            }
        }
    }

    private fun cerrarInterno() {
        val previousConversation = conversation
        val previousEngine = engine
        // Clear the logical state even if one native close reports a failure; both
        // handles are attempted and the cleanup error is propagated to the caller.
        conversation = null
        engine = null
        backendGpu = false
        modelPath = null
        cachePath = null
        closeResourcesOrThrow(
            { previousConversation?.close() },
            { previousEngine?.close() }
        )
    }
}
