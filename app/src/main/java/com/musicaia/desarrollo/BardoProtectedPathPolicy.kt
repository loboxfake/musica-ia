package com.musicaia.desarrollo

/**
 * Política común para los cambios que BARDO propone sobre proyectos.
 * La seguridad no depende de que el modelo obedezca instrucciones del prompt.
 */
object BardoProtectedPathPolicy {
    const val MODO3_PATH =
        "app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt"
    const val MODO3_EXPECTED_BLOB_SHA1 = "8f2745fa841a06af43bf909732fba834474bf7dc"

    enum class PathClass {
        AUTONOMOUS_EDIT,
        ADDITIVE_TEST_ONLY,
        HUMAN_REVIEW_REQUIRED,
        FORBIDDEN
    }

    private val protectedExact = setOf(
        "BARDO_01_VISION_OBJETIVOS_DIRECTIVAS.txt",
        "BARDO_02_INVENTARIO_TECNICO_Y_ESTADO.txt",
        "app/src/main/java/com/musicaia/MainActivity.kt",
        "app/src/main/java/com/musicaia/BardoDeveloperActivity.kt",
        "app/src/main/java/com/musicaia/BardoGitHubDevelopmentActivity.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoDeveloperCore.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoDeveloperWorker.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoGitHubAutomation.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoPatchApplier.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoProtectedPathPolicy.kt",
        "app/src/main/java/com/musicaia/desarrollo/BardoWorkspaceManager.kt",
        "app/src/main/java/com/musicaia/seguridad/BardoAuthorityPolicy.kt",
        "app/src/main/java/com/musicaia/seguridad/BardoAuthorityLedger.kt",
        "app/src/main/java/com/musicaia/modelos/BardoModelProvider.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillManifest.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillManager.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillExecutor.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillToolHandler.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillWorkflowExecutor.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillWorkflowPackageValidator.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillArchiveReader.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillWorkflowToolHandler.kt",
        "app/src/main/java/com/musicaia/skills/BardoCoreSkillSmokeTestRunner.kt",
        "app/src/main/java/com/musicaia/skills/BardoSkillMaintenancePlanner.kt",
        "app/src/main/java/com/musicaia/storage/BardoMobileFilePolicy.kt",
        "app/src/main/java/com/musicaia/storage/BardoSharedStorage.kt",
        "app/src/main/java/com/musicaia/herramientas/BardoToolExecutor.kt",
        "app/src/main/java/com/musicaia/herramientas/BardoCoreToolCatalog.kt",
        "app/src/main/java/com/musicaia/autonomia/BardoAutonomyEngine.kt",
        "app/src/main/java/com/musicaia/evaluacion/BardoIndependentEvaluator.kt",
        "app/src/main/java/com/musicaia/modelo/BardoCapabilityRegistry.kt",
        "app/src/main/java/com/musicaia/diagnostico/BardoSelfInventory.kt",
        "app/src/main/java/com/musicaia/modelo/BardoIntentRouter.kt",
        "app/src/main/java/com/musicaia/modelo/BardoResourceBudget.kt",
        "app/src/main/java/com/musicaia/modelo/BardoModuleCatalog.kt",
        "app/src/main/java/com/musicaia/modelo/Orquestador.kt",
        "app/src/main/java/com/musicaia/modelo/Catalogo.kt",
        "app/src/main/java/com/musicaia/modelo/ManifiestoModelo.kt",
        "app/src/main/java/com/musicaia/modulos/litert/EngineLiteRT.kt",
        "app/src/main/java/com/musicaia/modulos/litert/LiteRTLMModule.kt",
        "app/src/main/java/com/musicaia/modulos/DescargadorModelos.kt",
        "app/src/main/java/com/musicaia/servicios/BardoTaskService.kt",
        "app/src/main/java/com/musicaia/memoria/BardoActivityStore.kt",
        "app/src/main/java/com/musicaia/memoria/BardoBackupManager.kt",
        "app/src/main/java/com/musicaia/memoria/BardoKnowledgeStore.kt",
        "app/src/main/java/com/musicaia/memoria/BardoConversationStore.kt",
        "app/src/main/AndroidManifest.xml",
        "app/build.gradle.kts",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradle/wrapper/gradle-wrapper.properties"
    )

    private val protectedPrefixes = listOf(
        ".github/",
        ".git/",
        "gradle/wrapper/",
        "app/src/main/assets/",
        "app/src/main/jniLibs/"
    )

    private val autonomousPrefixes = listOf(
        "app/src/main/java/",
        "app/src/main/res/",
        "docs/"
    )

    private val standaloneSourceExtensions = setOf("kt", "java", "md", "txt")

    fun normalize(path: String): String? {
        if (path.isBlank() || path != path.trim()) return null
        if (path.startsWith("/") || path.contains('\\') || path.any { it.isISOControl() } ||
            path.contains(':') || path.startsWith("~")
        ) return null
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        return path
    }

    fun classify(rawPath: String): PathClass {
        val path = normalize(rawPath) ?: return PathClass.FORBIDDEN
        val fileName = path.substringAfterLast('/')

        if (fileName.equals("Modo3MezclaVozUsuario.kt", ignoreCase = true)) {
            return PathClass.FORBIDDEN
        }
        if (path in protectedExact || protectedPrefixes.any { path.startsWith(it) }) {
            return PathClass.HUMAN_REVIEW_REQUIRED
        }
        if (path.startsWith("app/src/test/")) {
            return if (path.endsWith(".kt", ignoreCase = true)) {
                PathClass.ADDITIVE_TEST_ONLY
            } else {
                PathClass.FORBIDDEN
            }
        }
        if (autonomousPrefixes.any { path.startsWith(it) }) return PathClass.AUTONOMOUS_EDIT
        if (!path.contains('/') &&
            fileName.substringAfterLast('.', "").lowercase() in standaloneSourceExtensions
        ) return PathClass.AUTONOMOUS_EDIT
        return PathClass.FORBIDDEN
    }

    fun canModifyExisting(path: String): Boolean =
        classify(path) == PathClass.AUTONOMOUS_EDIT

    /** La integración debe comprobar además que el fichero de prueba aún no existe. */
    fun canAddNewTest(path: String): Boolean =
        classify(path) == PathClass.ADDITIVE_TEST_ONLY

    fun validateAutonomousChanges(paths: List<String>) {
        require(paths.isNotEmpty() && paths.size <= 30) {
            "El candidato debe contener entre 1 y 30 archivos."
        }
        require(paths.distinct().size == paths.size) {
            "El candidato contiene rutas duplicadas."
        }
        paths.forEach { path ->
            require(canModifyExisting(path)) {
                "La política de BARDO bloquea la modificación automática de: " +
                    path + " (" + classify(path) + ")."
            }
        }
    }

    fun validateNewTests(paths: List<String>) {
        require(paths.isNotEmpty() && paths.size <= 50) {
            "La incorporación debe contener entre 1 y 50 pruebas nuevas."
        }
        require(paths.distinct().size == paths.size) {
            "La incorporación contiene rutas de prueba duplicadas."
        }
        paths.forEach { path ->
            require(canAddNewTest(path)) {
                "Solo se pueden añadir pruebas Kotlin nuevas; las pruebas existentes son inmutables: " +
                    path + "."
            }
        }
    }
}
