package com.musicaia.modelos

import android.content.Context
import com.musicaia.modelo.BardoCapabilityRegistry
import com.musicaia.modelo.BardoResourceBudget
import com.musicaia.modelo.BardoResourceCoordinator
import com.musicaia.diagnostico.BardoCapabilityEvidenceStore
import com.musicaia.modulos.litert.EngineLiteRT
import com.musicaia.modulos.litert.LiteRTLMModule
import java.io.File
import java.security.MessageDigest

enum class BardoModelLocation { LOCAL, REMOTE }
enum class BardoModelState {
    READY, MODEL_MISSING, MODEL_SIZE_MISMATCH, PRESENT_UNVERIFIED, RESOURCE_BLOCKED, LOAD_FAILED
}

data class BardoModelDescriptor(
    val id: String,
    val version: String,
    val capabilities: Set<String>,
    val location: BardoModelLocation,
    val license: String,
    val modelSha256: String?,
    val modelSizeBytes: Long?,
    val priority: Int = 0
)

data class BardoModelAvailability(val state: BardoModelState, val reason: String)
data class BardoModelRequest(val prompt: String, val resetConversation: Boolean = false) {
    init {
        require(prompt.isNotBlank()) { "La petición al modelo no puede estar vacía." }
        require(prompt.length <= 120_000) { "El contexto supera el límite de BARDO." }
    }
}
data class BardoModelResponse(
    val text: String,
    val providerId: String,
    val modelVersion: String,
    val elapsedMillis: Long
)

interface BardoModelProvider {
    val descriptor: BardoModelDescriptor
    fun inspect(context: Context): BardoModelAvailability
    fun load(context: Context): Boolean
    fun generate(context: Context, request: BardoModelRequest): BardoModelResponse
    fun unload()
}

/**
 * Adaptador inicial para Gemma/LiteRT-LM. El contrato no expone clases nativas
 * de LiteRT al núcleo, por lo que se pueden añadir otros proveedores sin cambiarlo.
 */
class BardoGemmaLiteRtProvider : BardoModelProvider {
    override val descriptor = BardoModelDescriptor(
        id = "gemma_litert_local",
        version = LiteRTLMModule.COMMIT_SHA,
        capabilities = setOf("general_chat", "lyrics", "code_analysis", "local_learning"),
        location = BardoModelLocation.LOCAL,
        license = "Apache-2.0",
        modelSha256 = LiteRTLMModule.EXPECTED_SHA256,
        modelSizeBytes = LiteRTLMModule.EXPECTED_SIZE_BYTES,
        priority = 100
    )

    @Volatile private var loaded = false
    @Volatile private var loadedPath: String? = null

    override fun inspect(context: Context): BardoModelAvailability {
        val model = LiteRTLMModule.modelFile(context)
        val runtimeLoaded = EngineLiteRT.estaCargado(model.absolutePath)
        loaded = runtimeLoaded
        loadedPath = if (runtimeLoaded) model.absolutePath else null
        if (runtimeLoaded) {
            return BardoModelAvailability(BardoModelState.READY, "Modelo local verificado y runtime cargado.")
        }
        if (!model.isFile) {
            return BardoModelAvailability(BardoModelState.MODEL_MISSING, "Gemma no está copiado al almacenamiento privado de BARDO.")
        }
        if (model.length() != LiteRTLMModule.EXPECTED_SIZE_BYTES) {
            return BardoModelAvailability(BardoModelState.MODEL_SIZE_MISMATCH, "El tamaño no coincide con el modelo fijado.")
        }
        val resourceBlock = BardoResourceBudget.blockingReason(
            context,
            BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES
        )
        if (resourceBlock != null) {
            return BardoModelAvailability(BardoModelState.RESOURCE_BLOCKED, resourceBlock)
        }
        return BardoModelAvailability(
            BardoModelState.PRESENT_UNVERIFIED,
            "Archivo y recursos presentes; falta verificar SHA-256 antes de cargar."
        )
    }

    override fun load(context: Context): Boolean {
        val current = inspect(context)
        val model = LiteRTLMModule.modelFile(context)
        if (current.state == BardoModelState.READY) {
            // El runtime puede venir de una carga anterior al registro de evidencias.
            // Verificamos el archivo una vez antes de registrar la carga ya residente.
            val stored = BardoCapabilityEvidenceStore.inspect(
                context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256,
                LiteRTLMModule.EXPECTED_SIZE_BYTES
            )
            if (stored.status == BardoCapabilityRegistry.Status.MODEL_PRESENT) {
                val actualHash = sha256(model)
                check(actualHash == LiteRTLMModule.EXPECTED_SHA256) {
                    "El SHA-256 de Gemma no coincide con el manifiesto."
                }
                runCatching {
                    BardoCapabilityEvidenceStore.recordShaVerified(
                        context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256, actualHash
                    )
                }
            }
            runCatching {
                BardoCapabilityEvidenceStore.recordLoadSucceeded(
                    context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256
                )
            }
            loadedPath = model.absolutePath
            loaded = true
            return true
        }
        check(current.state == BardoModelState.PRESENT_UNVERIFIED) { current.reason }

        val actualHash = sha256(model)
        check(actualHash == LiteRTLMModule.EXPECTED_SHA256) {
            "El SHA-256 de Gemma no coincide. El archivo original se conserva sin borrarlo."
        }
        runCatching { BardoCapabilityEvidenceStore.recordShaVerified(context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256, actualHash) }
        // El cálculo del hash puede tardar; volver a medir justo antes de reservar memoria nativa.
        val postHashResourceBlock = BardoResourceBudget.blockingReason(
            context,
            BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES
        )
        check(postHashResourceBlock == null) {
            postHashResourceBlock ?: "Los recursos del dispositivo cambiaron durante la verificación."
        }
        val cache = File(context.cacheDir, "bardo-litert-cache")
        check(EngineLiteRT.inicializar(model.absolutePath, cache.absolutePath)) {
            "LiteRT-LM no pudo cargar Gemma en el runtime."
        }
        runCatching { BardoCapabilityEvidenceStore.recordLoadSucceeded(context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256) }
        loadedPath = model.absolutePath
        loaded = true
        return true
    }

    override fun generate(context: Context, request: BardoModelRequest): BardoModelResponse {
        val started = System.nanoTime()
        // Load + inferencia se ejecutan en una transacción atómica del executor LiteRT.
        // Un cierre solicitado por otro módulo queda en cola antes o después de esta sesión.
        val text = EngineLiteRT.withSession {
            val model = LiteRTLMModule.modelFile(context)
            if (!estaCargado(model.absolutePath)) {
                check(model.isFile && model.length() == LiteRTLMModule.EXPECTED_SIZE_BYTES) {
                    "Gemma no está instalado o el tamaño del archivo no coincide."
                }
                val resourceBlock = BardoResourceBudget.blockingReason(
                    context,
                    BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES
                )
                check(resourceBlock == null) {
                    resourceBlock ?: "Los recursos del dispositivo no permiten cargar Gemma."
                }
                val actualHash = sha256(model)
                check(actualHash == LiteRTLMModule.EXPECTED_SHA256) {
                    "El SHA-256 de Gemma no coincide. El archivo original se conserva."
                }
                runCatching { BardoCapabilityEvidenceStore.recordShaVerified(context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256, actualHash) }
                // Revalidación final: RAM, batería y temperatura pueden variar durante el hash.
                val postHashResourceBlock = BardoResourceBudget.blockingReason(
                    context,
                    BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES
                )
                check(postHashResourceBlock == null) {
                    postHashResourceBlock ?: "Los recursos del dispositivo cambiaron durante la verificación."
                }
                check(inicializar(
                    model.absolutePath,
                    File(context.cacheDir, "bardo-litert-cache").absolutePath
                )) { "LiteRT-LM no pudo cargar Gemma." }
                runCatching { BardoCapabilityEvidenceStore.recordLoadSucceeded(context, "gemma", model, LiteRTLMModule.EXPECTED_SHA256) }
            }
            generar(
                request.prompt,
                reiniciarConversacion = request.resetConversation
            ).trim()
        }
        check(text.isNotBlank()) { "Gemma devolvió una respuesta vacía." }
        val completedModel = LiteRTLMModule.modelFile(context)
        runCatching { BardoCapabilityEvidenceStore.recordInferencePassed(context, "gemma", completedModel, LiteRTLMModule.EXPECTED_SHA256, outputValidated = text.isNotBlank()) }
        loadedPath = completedModel.absolutePath
        loaded = true
        return BardoModelResponse(
            text = text,
            providerId = descriptor.id,
            modelVersion = descriptor.version,
            elapsedMillis = (System.nanoTime() - started) / 1_000_000L
        )
    }

    override fun unload() {
        val path = loadedPath
        if (!path.isNullOrBlank() && EngineLiteRT.estaCargado(path)) {
            EngineLiteRT.cerrar()
        }
        loaded = false
        loadedPath = null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Registro neutral respecto al proveedor; los modelos locales preceden a los remotos. */
class BardoModelRegistry(providers: List<BardoModelProvider>) {
    private val providersById: Map<String, BardoModelProvider>

    init {
        require(providers.map { it.descriptor.id }.distinct().size == providers.size) {
            "Hay identificadores de proveedor duplicados."
        }
        providersById = providers.associateBy { it.descriptor.id }
    }

    fun orderedCandidates(
        capability: String,
        allowRemoteModels: Boolean
    ): List<BardoModelProvider> =
        providersById.values
            .filter { capability in it.descriptor.capabilities }
            .filter { allowRemoteModels || it.descriptor.location == BardoModelLocation.LOCAL }
            .sortedWith(
                compareBy<BardoModelProvider> { it.descriptor.location.ordinal }
                    .thenByDescending { it.descriptor.priority }
                    .thenBy { it.descriptor.id }
            )

    fun generate(
        context: Context,
        capability: String,
        request: BardoModelRequest,
        allowRemoteModels: Boolean = false
    ): BardoModelResponse {
        val candidates = orderedCandidates(capability, allowRemoteModels)
        require(candidates.isNotEmpty()) {
            "No hay un proveedor autorizado para la capacidad " + capability + "."
        }
        return BardoResourceCoordinator.withTextModel {
            synchronized(executionLock) {
                val failures = mutableListOf<String>()
                for (provider in candidates) {
                    try {
                        val availability = provider.inspect(context)
                        check(
                            availability.state == BardoModelState.READY ||
                                availability.state == BardoModelState.PRESENT_UNVERIFIED
                        ) { availability.reason }
                        check(provider.load(context)) {
                            "El proveedor " + provider.descriptor.id + " no pudo cargarse."
                        }
                        return@synchronized provider.generate(context, request)
                    } catch (error: OutOfMemoryError) {
                        failures += provider.descriptor.id + ": memoria insuficiente durante carga o inferencia."
                        runCatching { provider.unload() }
                        runCatching { System.gc() }
                    } catch (error: Exception) {
                        failures += provider.descriptor.id + ": " +
                            (error.message ?: error.javaClass.simpleName)
                        runCatching { provider.unload() }
                    }
                }
                throw IllegalStateException(
                    "Ningún proveedor pudo completar la petición. " + failures.joinToString("; ")
                )
            }
        }
    }

    fun unloadAll() = BardoResourceCoordinator.withTextModel {
        synchronized(executionLock) {
            providersById.values.forEach { provider -> provider.unload() }
        }
    }

    companion object {
        private val executionLock = Any()
    }
}
