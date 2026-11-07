package com.musicaia.modelo

/** Registro declarativo de capacidades y sus requisitos reales. */
object BardoCapabilityRegistry {
    enum class Status { AVAILABLE, NEEDS_MODEL, NEEDS_MEMORY, NEEDS_RESOURCES, NEEDS_FULL_APK, REQUIRES_USER_ACTION, NOT_IMPLEMENTED }

    const val MIN_GEMMA_FREE_MEMORY_BYTES = 4_000L * 1024L * 1024L

    data class Capability(
        val id: String,
        val name: String,
        val status: Status,
        val detail: String,
        val acceptanceCriteria: String
    )

    data class RuntimeFacts(
        val gemmaFilePresent: Boolean,
        val audioEnginePresent: Boolean,
        val mp3EncoderPresent: Boolean,
        val availableMemoryBytes: Long = Long.MAX_VALUE,
        val resourceBlockReason: String? = null,
        val githubActionsConfigured: Boolean = false
    )

    private fun gemmaStatus(facts: RuntimeFacts): Status = when {
        !facts.gemmaFilePresent -> Status.NEEDS_MODEL
        facts.availableMemoryBytes < MIN_GEMMA_FREE_MEMORY_BYTES -> Status.NEEDS_MEMORY
        !facts.resourceBlockReason.isNullOrBlank() -> Status.NEEDS_RESOURCES
        else -> Status.AVAILABLE
    }

    private fun gemmaDetail(facts: RuntimeFacts): String = when (gemmaStatus(facts)) {
        Status.NEEDS_MODEL -> "Necesita importar Gemma local desde Descargas/BARDO/Modelos (aprox. 2,6 GB). Si ya guardaste allí el archivo, no necesitas descargar otra copia."
        Status.NEEDS_MEMORY -> "Gemma está presente, pero Android informa menos de 4 GB de RAM disponible. Cierra otras aplicaciones y vuelve a intentarlo. La comprobación es preventiva, no una garantía de compatibilidad."
        Status.NEEDS_RESOURCES -> facts.resourceBlockReason.orEmpty()
        else -> "Gemma está presente y hay memoria disponible suficiente según el umbral preventivo. Se verifica su SHA-256 y compatibilidad al cargar."
    }

    fun snapshot(facts: RuntimeFacts): List<Capability> {
        val localModelStatus = gemmaStatus(facts)
        val localModelDetail = gemmaDetail(facts)
        return listOf(
            Capability("general_chat", "Conversación general local", localModelStatus, localModelDetail, "Gemma verificado por SHA-256; conversación real sin Internet en el Xiaomi, sin cierres ni respuestas simuladas."),
            Capability(
                "lyrics", "Generación de letras",
                localModelStatus,
                when (localModelStatus) {
                    Status.NEEDS_MODEL -> "Necesita importar Gemma local desde Descargas/BARDO/Modelos; no hace falta volver a descargarlo si ya está allí."
                    Status.NEEDS_MEMORY -> "Necesita más memoria RAM libre para cargar Gemma de forma prudente."
                    Status.NEEDS_RESOURCES -> facts.resourceBlockReason.orEmpty()
                    else -> "Generación local condicionada a que Gemma supere la verificación y pueda ejecutarse."
                },
                "Crear letra nueva y coherente con los datos; no usar plantillas fijas; reproducir y revisar el resultado."
            ),
            Capability(
                "instrumental", "Generación instrumental",
                if (facts.audioEnginePresent) Status.AVAILABLE else Status.NEEDS_FULL_APK,
                if (facts.audioEnginePresent) "Motor nativo local incluido; la generación real requiere superar las comprobaciones de memoria y modelo." else "Necesita la APK offline completa con ExecuTorch y Stable Audio.",
                "Prueba rápida en modo avión; luego 18 segmentos, diagnóstico WAV, duración final mínima de 180 s y escucha sin huecos artificiales."
            ),
            Capability(
                "mp3_export", "Exportación MP3",
                if (facts.mp3EncoderPresent) Status.AVAILABLE else Status.NEEDS_FULL_APK,
                if (facts.mp3EncoderPresent) "Codificador local disponible." else "Codificador nativo no incluido en esta compilación.",
                "Guardar MP3 reproducible en Descargas/BARDO y compartirlo con una acción independiente."
            ),
            Capability("history", "Historial con fechas y exportación", Status.AVAILABLE, "Conversaciones locales, recuperación, exportación JSON/TXT e importación validada.", "Exportar el historial a BARDO/Historial y comprobar que se abre y se puede recuperar."),
            Capability("knowledge", "Biblioteca de conocimiento", Status.AVAILABLE, "Importación local, SHA-256 verificado al recuperar y búsqueda textual; no modifica los pesos del modelo.", "Mostrar origen, pedir aprobación de guardado y recuperar contenido pertinente sin seguir instrucciones incrustadas."),
            Capability("research", "Investigación web autorizada", Status.REQUIRES_USER_ACTION, "Solo tras pulsar investigar; HTTPS y dominios de documentación permitidos.", "Acción explícita del usuario, fuentes citadas y ninguna afirmación de investigación sin conexión."),
            Capability("code_audit", "Auditoría de código", Status.AVAILABLE, "Análisis estático de un snapshot; no demuestra que el proyecto compile.", "Informe reproducible de archivos y hallazgos; diferenciar análisis estático de compilación y pruebas."),
            Capability("patch_sandbox", "Propuestas de parches aislados", Status.AVAILABLE, "Solo crea una copia candidata; no modifica la aplicación instalada.", "Diff aislado, pruebas previas, SHA de protecciones intactos, auditoría y reversión demostrable."),
            Capability(
                "project_build",
                "Validación de proyectos en runner aislado",
                if (facts.githubActionsConfigured) Status.AVAILABLE else Status.REQUIRES_USER_ACTION,
                if (facts.githubActionsConfigured) {
                    "BARDO puede enviar diffs permitidos a un runner efímero de GitHub Actions, ejecutar tests y assembleDebug y consultar el resultado. El teléfono no ejecuta Gradle."
                } else {
                    "Requiere conectar un token fine-grained de GitHub limitado a este repositorio (Actions: Read and write; Contents: Read). La compilación se ejecuta en runner remoto, no en Android."
                },
                "Enviar un candidato basado en el SHA exacto de main, ejecutar testDebugUnitTest y assembleDebug en runner sin secretos, recuperar conclusión y pasos; rechazar si falla o el commit base está obsoleto."
            ),
            Capability(
                "github_development",
                "Integración autónoma aprobada en main",
                if (facts.githubActionsConfigured) Status.AVAILABLE else Status.REQUIRES_USER_ACTION,
                if (facts.githubActionsConfigured) {
                    "BARDO puede solicitar validación y, tras tu aprobación en la aplicación, integrar un candidato mediante commit fast-forward en main. El token permanece cifrado en Android Keystore; el runner usa su GITHUB_TOKEN temporal."
                } else {
                    "Necesita configurar la conexión GitHub en BARDO. No se incrustan credenciales en el APK ni se crean ramas paralelas."
                },
                "No integrar sin validación SUCCESS; exigir aprobación explícita; revalidar en el workflow; exigir que main siga en el SHA base; commit fast-forward y disparar full-build.yml para generar la APK firmada."
            ),
            Capability("voice_synthesis", "Generación de voz cantada", Status.NOT_IMPLEMENTED, "Todavía no está integrada; el antiguo módulo de tonos se ha desactivado para evitar simular voz.", "Modelo de voz legítimo, consentimiento, WAV real reproducible y pruebas de calidad; MiVoz sigue pendiente."),
            Capability("mix_master", "Mezcla y masterización profesional", Status.NOT_IMPLEMENTED, "El módulo heredado que fabricaba una onda de prueba se ha desactivado; la mezcla vocal PCM básica disponible no equivale a una estación de mezcla profesional.", "Cadena no destructiva, métricas, A/B, exportación validada y pruebas auditivas."),
            Capability("cover_art", "Generación de portadas con IA", Status.NOT_IMPLEMENTED, "No hay modelo de imagen integrado; la tarjeta tipográfica heredada se ha desactivado para no presentarla como arte generado por IA.", "Modelo local de imagen compatible, licencia verificada y archivo de portada real."),
            Capability("self_install", "Actualización asistida", Status.NOT_IMPLEMENTED, "La última build auditada está disponible como artefacto de Actions; crear una Release nueva está bloqueado por HTTP 403 del token de Actions. Existe una Release previa; la actualización automática y el parche diferencial no están implementados. Android exige aprobación e instalación mediante el sistema.", "APK completa y parche publicados, SHA-256 y certificado coincidentes, copia de datos y confirmación del instalador Android.")
        )
    }
}
