package com.musicaia.modelos

import android.content.Context
import com.musicaia.modelo.BardoCapabilityRegistry
import com.musicaia.modelo.BardoResourceBudget
import com.musicaia.diagnostico.BardoCapabilityEvidenceStore
import com.musicaia.modulos.litert.BardoQwen2LiteRtModule
import com.musicaia.modulos.litert.EngineLiteRT
import java.io.File

/** Proveedor local ligero; se usa como alternativa cuando Gemma no cabe en la RAM disponible. */
class BardoQwen2LiteRtProvider : BardoModelProvider {
    override val descriptor = BardoModelDescriptor(
        id = "qwen2_0_5b_litert_local",
        version = BardoQwen2LiteRtModule.REVISION,
        capabilities = setOf("general_chat", "lyrics", "intent", "code_analysis", "local_learning"),
        location = BardoModelLocation.LOCAL,
        license = BardoQwen2LiteRtModule.LICENSE,
        modelSha256 = BardoQwen2LiteRtModule.EXPECTED_SHA256,
        modelSizeBytes = BardoQwen2LiteRtModule.EXPECTED_SIZE_BYTES,
        priority = 80
    )

    @Volatile private var loadedPath: String? = null

    override fun inspect(context: Context): BardoModelAvailability {
        val model = BardoQwen2LiteRtModule.modelFile(context)
        val path = model.absolutePath
        if (EngineLiteRT.estaCargado(path)) {
            loadedPath = path
            return BardoModelAvailability(BardoModelState.READY, "Qwen2 local cargado en LiteRT-LM.")
        }
        loadedPath = null
        if (!model.isFile) {
            return BardoModelAvailability(
                BardoModelState.MODEL_MISSING,
                "Qwen2-0.5B no está instalado. Puede descargarse una vez desde el gestor de modelos."
            )
        }
        if (model.length() != BardoQwen2LiteRtModule.EXPECTED_SIZE_BYTES) {
            return BardoModelAvailability(
                BardoModelState.MODEL_SIZE_MISMATCH,
                "El tamaño de Qwen2 no coincide con el manifiesto fijado; no se cargará."
            )
        }
        val block = BardoResourceBudget.blockingReason(
            context,
            BardoCapabilityRegistry.MIN_QWEN_FREE_MEMORY_BYTES
        )
        if (block != null) return BardoModelAvailability(BardoModelState.RESOURCE_BLOCKED, block)
        return BardoModelAvailability(
            BardoModelState.PRESENT_UNVERIFIED,
            "Archivo Qwen2 presente; se verificará SHA-256 antes de cargar."
        )
    }

    override fun load(context: Context): Boolean {
        val availability = inspect(context)
        val model = BardoQwen2LiteRtModule.modelFile(context)
        if (availability.state == BardoModelState.READY) {
            // Si LiteRT ya conserva el modelo, registrar la evidencia solo después
            // de comprobar el hash del archivo presente en disco.
            val stored = BardoCapabilityEvidenceStore.inspect(
                context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256,
                BardoQwen2LiteRtModule.EXPECTED_SIZE_BYTES
            )
            if (stored.status == BardoCapabilityRegistry.Status.MODEL_PRESENT) {
                check(BardoQwen2LiteRtModule.verificarModelo(model)) {
                    "El SHA-256 de Qwen2 no coincide con el manifiesto."
                }
                runCatching {
                    BardoCapabilityEvidenceStore.recordShaVerified(
                        context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256,
                        BardoQwen2LiteRtModule.EXPECTED_SHA256
                    )
                }
            }
            runCatching {
                BardoCapabilityEvidenceStore.recordLoadSucceeded(
                    context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256
                )
            }
            loadedPath = model.absolutePath
            return true
        }
        check(availability.state == BardoModelState.PRESENT_UNVERIFIED) { availability.reason }

        check(BardoQwen2LiteRtModule.verificarModelo(model)) {
            "El SHA-256 de Qwen2 no coincide. El archivo original se conserva sin borrarlo."
        }
        runCatching { BardoCapabilityEvidenceStore.recordShaVerified(context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256, BardoQwen2LiteRtModule.EXPECTED_SHA256) }
        val afterHashBlock = BardoResourceBudget.blockingReason(
            context,
            BardoCapabilityRegistry.MIN_QWEN_FREE_MEMORY_BYTES
        )
        check(afterHashBlock == null) {
            afterHashBlock ?: "Los recursos del dispositivo cambiaron durante la verificación."
        }
        val cache = File(context.cacheDir, "bardo-litert-cache")
        check(EngineLiteRT.inicializar(model.absolutePath, cache.absolutePath)) {
            "LiteRT-LM no pudo cargar Qwen2 en este dispositivo."
        }
        runCatching { BardoCapabilityEvidenceStore.recordLoadSucceeded(context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256) }
        loadedPath = model.absolutePath
        return true
    }

    override fun generate(context: Context, request: BardoModelRequest): BardoModelResponse {
        val started = System.nanoTime()
        val model = BardoQwen2LiteRtModule.modelFile(context)
        if (!EngineLiteRT.estaCargado(model.absolutePath)) {
            check(load(context)) { "No se pudo cargar Qwen2." }
        }
        val text = EngineLiteRT.withSession {
            if (!estaCargado(model.absolutePath)) {
                // No basta con confiar en load(): otro módulo puede haber cerrado
                // el runtime entre la carga y esta sesión. Revalidar archivo y RAM
                // dentro de la misma transacción que inicializa y ejecuta el modelo.
                check(model.isFile) {
                    "Qwen2 no está instalado. El archivo original se conserva."
                }
                check(model.length() == BardoQwen2LiteRtModule.EXPECTED_SIZE_BYTES) {
                    "El tamaño de Qwen2 no coincide con el manifiesto; no se cargará."
                }
                val actualHash = BardoQwen2LiteRtModule.sha256(model)
                check(actualHash.equals(BardoQwen2LiteRtModule.EXPECTED_SHA256, ignoreCase = true)) {
                    "El SHA-256 de Qwen2 no coincide. El archivo original se conserva."
                }
                val beforeLoadBlock = BardoResourceBudget.blockingReason(
                    context,
                    BardoCapabilityRegistry.MIN_QWEN_FREE_MEMORY_BYTES
                )
                check(beforeLoadBlock == null) {
                    beforeLoadBlock ?: "Los recursos del dispositivo no permiten cargar Qwen2."
                }
                val afterHashBlock = BardoResourceBudget.blockingReason(
                    context,
                    BardoCapabilityRegistry.MIN_QWEN_FREE_MEMORY_BYTES
                )
                check(afterHashBlock == null) {
                    afterHashBlock ?: "Los recursos cambiaron durante la verificación de Qwen2."
                }
                check(inicializar(
                    model.absolutePath,
                    File(context.cacheDir, "bardo-litert-cache").absolutePath
                )) { "No se pudo cargar Qwen2." }
                runCatching {
                    BardoCapabilityEvidenceStore.recordShaVerified(
                        context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256,
                        actualHash
                    )
                    BardoCapabilityEvidenceStore.recordLoadSucceeded(
                        context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256
                    )
                }
            }
            generar(
                request.prompt,
                reiniciarConversacion = request.resetConversation
            ).trim()
        }
        loadedPath = model.absolutePath
        check(text.isNotBlank()) { "Qwen2 devolvió una respuesta vacía." }
        runCatching { BardoCapabilityEvidenceStore.recordInferencePassed(context, "qwen2", model, BardoQwen2LiteRtModule.EXPECTED_SHA256, outputValidated = text.isNotBlank()) }
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
        loadedPath = null
    }
}
