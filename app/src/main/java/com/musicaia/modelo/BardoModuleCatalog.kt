package com.musicaia.modelo

import android.content.Context
import android.app.ActivityManager
import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.ModuloInstrumental
import com.musicaia.modulos.ModuloLetra
import com.musicaia.modulos.litert.LiteRTLMModule
import com.musicaia.modulos.Mp3Exporter
import java.io.File

/**
 * Registro único de módulos ejecutables. El orquestador recibe solo módulos
 * seleccionados aquí, según intención y requisitos detectados en runtime.
 */
object BardoModuleCatalog {
    data class Descriptor(
        val id: String,
        val name: String,
        val capabilityId: String,
        val modelId: String?,
        val modelSha256: String?,
        val license: String,
        val attribution: String,
        val heavyModel: Boolean,
        val implemented: Boolean
    )

    data class MusicPlan(
        val intent: String,
        val selectedModuleIds: List<String>,
        val modules: List<ModuloIA>
    )

    private val entries = listOf(
        Descriptor(
            id = "lyrics",
            name = "Generación de letras",
            capabilityId = "lyrics",
            modelId = LiteRTLMModule.MODEL_FILENAME,
            modelSha256 = LiteRTLMModule.EXPECTED_SHA256,
            license = "Apache-2.0",
            attribution = "Google / LiteRT Community · Gemma 4 E2B-it",
            heavyModel = true,
            implemented = true
        ),
        Descriptor(
            id = "instrumental",
            name = "Generación instrumental",
            capabilityId = "instrumental",
            modelId = "Stable Audio Open Small · cuatro artefactos ExecuTorch en assets/audio_model",
            modelSha256 = null,
            license = "Apache-2.0",
            attribution = "Stability AI y colaboradores upstream · https://github.com/Stability-AI/stable-audio-tools",
            heavyModel = true,
            implemented = true
        ),
        Descriptor("voice_synthesis", "Voz cantada", "voice_synthesis", null, null, "Pendiente", "MiVoz no integrada", true, false),
        Descriptor("stem_separation", "Separación de stems", "stem_separation", null, null, "En evaluación", "Demucs / UVR por investigar", true, false),
        Descriptor("mix_master", "Mezcla y masterización profesional", "mix_master", null, null, "Pendiente", "Procesamiento profesional no implementado", false, false),
        Descriptor("cover_art", "Generación de portadas con IA", "cover_art", null, null, "Pendiente", "Modelo de imagen local no integrado", false, false)
    )

    fun descriptors(): List<Descriptor> = entries.toList()

    /** Selección pura para poder probar que el plan no activa módulos ajenos. */
    fun selectMusicModuleIds(
        instrumentalOnly: Boolean,
        manualLyrics: Boolean,
        gemmaReady: Boolean,
        audioReady: Boolean
    ): List<String> {
        require(audioReady) { "El motor instrumental local no está disponible en esta APK." }
        if (instrumentalOnly) return listOf("instrumental")
        require(manualLyrics || gemmaReady) {
            "La creación de letra nueva necesita Gemma local verificado y recursos suficientes; puedes usar «Mi letra»."
        }
        return listOf("lyrics", "instrumental")
    }

    fun createMusicPlan(
        context: Context,
        instrumentalOnly: Boolean,
        manualLyrics: Boolean,
        prompt: String,
        quickTest: Boolean,
        onSegmentProgress: (Int, Int, String) -> Unit,
        isCancellationRequested: () -> Boolean
    ): MusicPlan {
        val app = context.applicationContext
        val model = LiteRTLMModule.modelFile(app)
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val resourceBlock = BardoResourceBudget.blockingReason(
            app, BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES
        )
        val facts = BardoCapabilityRegistry.RuntimeFacts(
            gemmaFilePresent = model.isFile && model.length() == LiteRTLMModule.EXPECTED_SIZE_BYTES,
            audioEnginePresent = AudioGenNative.available,
            mp3EncoderPresent = Mp3Exporter.available,
            availableMemoryBytes = memoryInfo.availMem,
            resourceBlockReason = resourceBlock,
            speechModelPresent = com.musicaia.modulos.BardoOfflineAsr.isModelInstalled(app, com.musicaia.modulos.BardoOfflineAsr.Language.SPANISH),
            speechEnglishModelPresent = com.musicaia.modulos.BardoOfflineAsr.isModelInstalled(app, com.musicaia.modulos.BardoOfflineAsr.Language.ENGLISH)
        )
        val capabilities = BardoCapabilityRegistry.snapshot(facts).associateBy { it.id }
        val selected = selectMusicModuleIds(
            instrumentalOnly = instrumentalOnly,
            manualLyrics = manualLyrics,
            gemmaReady = capabilities["lyrics"]?.status == BardoCapabilityRegistry.Status.AVAILABLE,
            audioReady = capabilities["instrumental"]?.status == BardoCapabilityRegistry.Status.AVAILABLE
        )
        val modules = selected.map { id ->
            when (id) {
                "lyrics" -> ModuloLetra(app, modoManual = manualLyrics)
                "instrumental" -> ModuloInstrumental(
                    app,
                    prompt,
                    quickTest = quickTest,
                    onSegmentProgress = onSegmentProgress,
                    isCancellationRequested = isCancellationRequested
                )
                else -> error("Módulo no registrado: $id")
            }
        }
        return MusicPlan(
            intent = if (instrumentalOnly) "instrumental" else "cancion",
            selectedModuleIds = selected,
            modules = modules
        )
    }
}
