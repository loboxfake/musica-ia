package com.musicaia.diagnostico

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import com.musicaia.desarrollo.BardoGitHubCredentialStore
import com.musicaia.modelo.BardoCapabilityRegistry
import com.musicaia.modelo.BardoModuleCatalog
import com.musicaia.modelo.BardoResourceBudget
import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.BardoOfflineAsr
import com.musicaia.modulos.Mp3Exporter
import com.musicaia.modulos.litert.LiteRTLMModule
import com.musicaia.modulos.imagen.BardoBonsaiModelInstaller

/**
 * Autoinspección de BARDO. No consulta PackageManager para enumerar aplicaciones ajenas.
 * Distingue código incluido, modelos descargados, recursos y capacidades pendientes de prueba.
 */
object BardoSelfInventory {
    data class ModelEntry(
        val id: String,
        val name: String,
        val installed: Boolean,
        val detail: String,
        val bytes: Long = 0L
    )

    data class HelperEntry(
        val id: String,
        val name: String,
        val integrated: Boolean,
        val detail: String
    )

    data class Snapshot(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val deviceModel: String,
        val androidRelease: String,
        val sdk: Int,
        val supportedAbis: List<String>,
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val lowMemory: Boolean,
        val availableStorageBytes: Long,
        val batteryPercent: Int?,
        val charging: Boolean,
        val thermalStatus: Int?,
        val sharedWorkspaceConnected: Boolean,
        val models: List<ModelEntry>,
        val helpers: List<HelperEntry>,
        val modules: List<BardoModuleCatalog.Descriptor>,
        val capabilities: List<BardoCapabilityRegistry.Capability>
    )

    private data class HelperSpec(
        val id: String,
        val name: String,
        val classes: List<String>
    )

    private val expectedAudioAssets = setOf(
        "conditioners_model.pte",
        "dit_model.pte",
        "autoencoder_model.pte",
        "spiece.model"
    )

    private val helperSpecs = listOf(
        HelperSpec("local_text_ai", "Asistente local Gemma / LiteRT-LM", listOf(
            "com.musicaia.modulos.litert.LiteRTLMModule",
            "com.musicaia.modulos.litert.ConversacionLiteRT"
        )),
        HelperSpec("orchestrator", "Orquestador e interpretación de intenciones", listOf(
            "com.musicaia.modelo.Orquestador",
            "com.musicaia.modelo.BardoIntentRouter",
            "com.musicaia.modelo.BardoGuidedResponseParser"
        )),
        HelperSpec("developer_agent", "Agente de autoestudio y desarrollo", listOf(
            "com.musicaia.desarrollo.BardoDeveloperCore",
            "com.musicaia.desarrollo.BardoDeveloperWorker"
        )),
        HelperSpec("autonomy_loop", "Bucle autónomo local planificar-actuar-evaluar-corregir", listOf(
            "com.musicaia.autonomia.BardoAutonomyEngine",
            "com.musicaia.autonomia.BardoGemmaTaskPlanner",
            "com.musicaia.autonomia.BardoTaskJournal",
            "com.musicaia.herramientas.BardoToolExecutor",
            "com.musicaia.herramientas.BardoCoreToolCatalog",
            "com.musicaia.evaluacion.BardoIndependentEvaluator"
        )),
        HelperSpec("model_registry", "Registro de proveedores de modelos sustituibles", listOf(
            "com.musicaia.modelos.BardoModelProvider",
            "com.musicaia.modelos.BardoModelRegistry",
            "com.musicaia.modelos.BardoGemmaLiteRtProvider"
        )),
        HelperSpec("skill_manager", "Gestión segura de skills declarativas", listOf(
            "com.musicaia.skills.BardoSkillManifestValidator",
            "com.musicaia.skills.BardoSkillManager"
        )),
        HelperSpec("authority", "Verificación criptográfica User/JARVIS y ledger de permisos", listOf(
            "com.musicaia.seguridad.BardoAuthorityPolicy",
            "com.musicaia.seguridad.BardoFileAuthorityLedger"
        )),
        HelperSpec("github_agent", "Ayudante de validación e integración GitHub", listOf(
            "com.musicaia.desarrollo.BardoGitHubCredentialStore",
            "com.musicaia.desarrollo.BardoGitHubPolicy",
            "com.musicaia.desarrollo.BardoGitHubActionsClient",
            "com.musicaia.desarrollo.BardoPatchApplier"
        )),
        HelperSpec("memory", "Memoria, conocimiento, actividad y copias", listOf(
            "com.musicaia.memoria.BardoConversationStore",
            "com.musicaia.memoria.BardoKnowledgeStore",
            "com.musicaia.memoria.BardoActivityStore",
            "com.musicaia.memoria.BardoBackupManager"
        )),
        HelperSpec("audio_generation", "Motor instrumental Stable Audio", listOf(
            "com.musicaia.modulos.AudioGenNative",
            "com.musicaia.modulos.ModuloInstrumental",
            "com.musicaia.modulos.AudioAssembler"
        )),
        HelperSpec("mp3", "Codificador MP3 nativo", listOf("com.musicaia.modulos.Mp3Exporter")),
        HelperSpec("offline_asr", "Reconocimiento de voz offline", listOf("com.musicaia.modulos.BardoOfflineAsr")),
        HelperSpec("ocr", "OCR y lectura documental", listOf(
            "com.musicaia.importacion.BardoOcrImporter",
            "com.musicaia.importacion.BardoOcrLanguageSupport",
            "com.musicaia.importacion.BardoPdfImporter",
            "com.musicaia.importacion.BardoOfficeImporter"
        )),
        HelperSpec("image_vision", "Visión y clasificación de imágenes", listOf("com.musicaia.importacion.BardoImageVision")),
        HelperSpec("procedural_covers", "Diseño procedural de portadas", listOf(
            "com.musicaia.modulos.BardoCoverArtGenerator",
            "com.musicaia.modulos.ModuloPortada"
        )),
        HelperSpec("bonsai_image", "Generador local de imágenes Bonsai", listOf(
            "com.musicaia.modulos.imagen.BardoBonsaiImageGenerator",
            "com.musicaia.modulos.imagen.BardoBonsaiPipeline",
            "com.musicaia.modulos.imagen.BardoBonsaiModelInstaller"
        )),
        HelperSpec("wav_tools", "Herramientas WAV y mezcla multipista", listOf(
            "com.musicaia.modulos.BardoWavMastering",
            "com.musicaia.modulos.BardoWavMultitrackMixer",
            "com.musicaia.modulos.AudioUtil"
        )),
        HelperSpec("voice_mix", "Mezcla de voz del usuario · Modo3", listOf(
            "com.musicaia.modulos.Modo3MezclaVozUsuario",
            "com.musicaia.modulos.GrabadoraWav",
            "com.musicaia.modulos.MezclaVoz",
            "com.musicaia.modulos.LectorWav"
        )),
        HelperSpec("background_tasks", "Servicio de tareas en segundo plano", listOf("com.musicaia.servicios.BardoTaskService")),
        HelperSpec("shared_storage", "Almacenamiento compartido BARDO / SAF", listOf("com.musicaia.storage.BardoSharedStorage")),
        HelperSpec("avatar", "Avatar e interfaz de estado", listOf("com.musicaia.ui.BardoAvatarView"))
    )

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val packageInfo = getOwnPackageInfo(app)
        val resources = BardoResourceBudget.inspect(app)
        val stat = StatFs(app.filesDir.absolutePath)
        val gemma = LiteRTLMModule.modelFile(app)
        val gemmaPresent = gemma.isFile && gemma.length() == LiteRTLMModule.EXPECTED_SIZE_BYTES

        val audioAssets = app.assets.list("audio_model")?.toSet().orEmpty()
        val audioAssetsPresent = expectedAudioAssets.all { it in audioAssets }
        val audioReady = audioAssetsPresent && AudioGenNative.available
        val mp3Ready = Mp3Exporter.available
        val spanishAsr = BardoOfflineAsr.isModelInstalled(app, BardoOfflineAsr.Language.SPANISH)
        val englishAsr = BardoOfflineAsr.isModelInstalled(app, BardoOfflineAsr.Language.ENGLISH)
        val bonsaiInstalled = BardoBonsaiModelInstaller.isInstalled(app)
        val githubConfigured = runCatching { BardoGitHubCredentialStore.hasToken(app) }.getOrDefault(false)
        val sharedTree = app.getSharedPreferences("bardo_storage", Context.MODE_PRIVATE)
            .getString("tree_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val sharedWorkspaceConnected = sharedTree != null && app.contentResolver.persistedUriPermissions.any {
            it.uri == sharedTree && it.isReadPermission && it.isWritePermission
        }

        val modules = BardoModuleCatalog.descriptors()
        val models = listOf(
            ModelEntry("gemma", "Gemma 4 E2B-it · asistente de texto local", gemmaPresent,
                when {
                    !gemma.exists() -> "No está copiado al almacenamiento privado de BARDO. Archivo esperado: " + LiteRTLMModule.MODEL_FILENAME + ". Carpeta compartida BARDO: " + (if (sharedWorkspaceConnected) "permiso conectado; importación automática pendiente" else "sin permiso persistente conectado") + "."
                    !gemmaPresent -> "El archivo existe, pero su tamaño no coincide con el modelo fijado (" + LiteRTLMModule.EXPECTED_SIZE_BYTES + " bytes). No se declara instalado."
                    else -> "Archivo y tamaño correctos en almacenamiento privado. SHA-256 esperado: " + LiteRTLMModule.EXPECTED_SHA256 + ". El inventario no recalcula 2,6 GB; la carga/autoestudio debe validar la huella antes de inferir."
                }, if (gemma.isFile) gemma.length() else 0L),
            ModelEntry("stable_audio", "Stable Audio Open Small · modelo instrumental", audioReady,
                if (audioReady) "Los cuatro artefactos PTE/tokenizer están incluidos en assets/audio_model y libaudiogen se ha cargado. El éxito de inferencia y la calidad WAV aún requieren prueba real."
                else "Falta algún artefacto interno de audio o la biblioteca nativa no se pudo cargar.", 876_975_464L),
            ModelEntry("vosk_es", "Vosk small · español", spanishAsr,
                if (spanishAsr) "Modelo descargado; estructura detectada en " + BardoOfflineAsr.modelDirectory(app, BardoOfflineAsr.Language.SPANISH).absolutePath + ". La prueba de transcripción real sigue pendiente."
                else "Modelo español opcional no instalado."),
            ModelEntry("vosk_en", "Vosk small · inglés", englishAsr,
                if (englishAsr) "Modelo descargado; estructura detectada en " + BardoOfflineAsr.modelDirectory(app, BardoOfflineAsr.Language.ENGLISH).absolutePath + ". La prueba de transcripción real sigue pendiente."
                else "Modelo inglés opcional no instalado."),
            ModelEntry("bonsai", "Bonsai Image 4B · generación local de imágenes", bonsaiInstalled,
                if (bonsaiInstalled) "Revisión " + BardoBonsaiModelInstaller.REVISION + " y tamaños de los seis artefactos coinciden en " + BardoBonsaiModelInstaller.modelDir(app).absolutePath + "; SHA-256 verificado durante instalación. Generación real y viabilidad térmica pendientes."
                else "Modelo opcional no instalado o no supera revisión/tamaños."),
            ModelEntry("mlkit_ocr", "ML Kit · OCR multiescritura incluido",
                classPresent(app, "com.google.mlkit.vision.text.TextRecognition"),
                "Biblioteca OCR integrada en APK; los idiomas admitidos y el reconocimiento real se prueban con documentos de muestra."),
            ModelEntry("mlkit_vision", "ML Kit · etiquetas visuales incluidas",
                classPresent(app, "com.google.mlkit.vision.label.ImageLabeling"),
                "Biblioteca de clasificación visual integrada en APK; no equivale a comprensión completa de escenas.")
        )

        val helpers = helperSpecs.map { spec ->
            val present = spec.classes.all { classPresent(app, it) }
            val detail = when {
                !present -> "Falta al menos una clase requerida en esta APK."
                spec.id == "local_text_ai" -> if (gemmaPresent) "Código integrado; Gemma tiene tamaño esperado. SHA/carga real pendientes."
                    else "Código integrado; falta Gemma local verificado."
                spec.id == "developer_agent" -> if (gemmaPresent) "Código integrado; autoestudio requiere Gemma cargable, snapshot, recursos y validación aislada."
                    else "Código integrado; necesita Gemma local y recursos suficientes."
                spec.id == "autonomy_loop" -> "Bucle local integrado al autoestudio: Gemma planifica y las acciones pasan por herramientas locales tipadas; investigación web y multiagente distribuido siguen pendientes."
                spec.id == "model_registry" -> "Contrato neutro de proveedor y registro local-first integrados; Gemma/LiteRT es el primer adaptador. No se declara una flota de modelos adicionales."
                spec.id == "skill_manager" -> "Cuarentena, integridad, instalación deshabilitada, activación aprobada, desactivación y rollback disponibles como API local; interfaz de catálogo/descarga pendiente."
                spec.id == "authority" -> "Verificación ECDSA, scopes, nonce y revocación persistente integrados; emparejamiento de claves y transporte JARVIS-PC↔Android todavía pendientes."
                spec.id == "github_agent" -> if (githubConfigured) "Código integrado; credencial local detectada. La operación exige aprobación humana."
                    else "Código integrado; falta configurar la credencial GitHub dentro de BARDO."
                spec.id == "audio_generation" -> if (audioReady) "Código, artefactos y biblioteca nativa presentes; inferencia de audio aún por probar."
                    else "Código integrado; falta artefacto o biblioteca nativa de audio."
                spec.id == "mp3" -> if (mp3Ready) "Biblioteca nativa MP3 cargada; exportación real aún pendiente."
                    else "Código integrado; biblioteca MP3 no disponible en runtime."
                spec.id == "offline_asr" -> "Código integrado; español: " + (if (spanishAsr) "modelo presente" else "falta modelo") +
                    "; inglés: " + (if (englishAsr) "modelo presente" else "falta modelo") + "."
                spec.id == "voice_mix" -> "El flujo activo de MainActivity usa MezclaVoz.mezclar y LectorWav para una mezcla PCM16 real; Modo3MezclaVozUsuario.kt se conserva como adaptador legacy y no es la ruta activa. Prueba auditiva en Xiaomi pendiente."
                spec.id == "shared_storage" -> if (sharedWorkspaceConnected) "Permiso SAF persistente de lectura/escritura para la carpeta BARDO detectado; no se recorren otras carpetas." else "Código integrado; falta conectar o restaurar el permiso SAF de la carpeta BARDO."
                spec.id == "bonsai_image" -> if (bonsaiInstalled) "Código y modelo opcional presentes; generación real pendiente."
                    else "Código integrado; falta instalar el modelo Bonsai opcional."
                else -> "Código integrado en el paquete BARDO; prueba funcional específica pendiente."
            }
            HelperEntry(spec.id, spec.name, present, detail)
        }

        val facts = BardoCapabilityRegistry.RuntimeFacts(
            gemmaFilePresent = gemmaPresent,
            audioEnginePresent = audioReady,
            mp3EncoderPresent = mp3Ready,
            availableMemoryBytes = resources.availableRamBytes,
            resourceBlockReason = BardoResourceBudget.blockingReason(resources, BardoCapabilityRegistry.MIN_GEMMA_FREE_MEMORY_BYTES),
            githubActionsConfigured = githubConfigured,
            speechModelPresent = spanishAsr,
            speechEnglishModelPresent = englishAsr,
            imageModelPresent = bonsaiInstalled,
            imageResourceBlockReason = BardoResourceBudget.blockingReason(resources, BardoCapabilityRegistry.MIN_IMAGE_GENERATION_FREE_MEMORY_BYTES),
            installedModelCount = models.count { it.installed },
            availableHelperCount = helpers.count { it.integrated },
            registeredModuleCount = modules.size
        )

        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return Snapshot(
            packageName = app.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong(),
            deviceModel = Build.MANUFACTURER + " " + Build.MODEL,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = resources.availableRamBytes,
            lowMemory = resources.lowMemory,
            availableStorageBytes = stat.availableBytes,
            batteryPercent = resources.batteryPercent,
            charging = resources.charging,
            thermalStatus = resources.thermalStatus,
            sharedWorkspaceConnected = sharedWorkspaceConnected,
            models = models,
            helpers = helpers,
            modules = modules,
            capabilities = BardoCapabilityRegistry.snapshot(facts)
        )
    }

    private fun classPresent(context: Context, className: String): Boolean =
        runCatching { Class.forName(className, false, context.classLoader) }.isSuccess

    @Suppress("DEPRECATION")
    private fun getOwnPackageInfo(context: Context) =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
}
