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
        val githubActionsConfigured: Boolean = false,
        val speechModelPresent: Boolean = false,
        val speechEnglishModelPresent: Boolean = false
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
            Capability("speech_transcription_en", "Transcripción de audio offline en inglés",
                if (facts.speechEnglishModelPresent) Status.AVAILABLE else Status.NEEDS_MODEL,
                if (facts.speechEnglishModelPresent) "Modelo Vosk small-en-us instalado y validado por estructura. Decodificación local Android; no se envía audio a la nube."
                else "Necesita descargar una vez el modelo inglés Vosk (41.205.931 bytes); se verifica SHA-256 antes de instalar. Después funciona sin conexión.",
                "Transcribir habla inglesa real decodificable por Android, sin modificar el original ni inventar texto."),
            Capability("speech_transcription_es", "Transcripción de audio offline en español", if (facts.speechModelPresent) Status.AVAILABLE else Status.NEEDS_MODEL, if (facts.speechModelPresent) "Modelo Vosk español instalado y validado por estructura. La decodificación usa códecs locales de Android; no se envía audio a la nube." else "Necesita descargar una vez el modelo español Vosk (39.817.833 bytes); SHA-256 fijado y comprobado antes de instalar. Después funciona sin conexión.", "Transcribir audio real decodificable por Android, en segundo plano, sin modificar el original; no inventar texto cuando no se detecta voz."),
            Capability("mix_wav_basic", "Mezcla básica de 2–8 pistas WAV", Status.AVAILABLE, "Mezclador estéreo offline PCM16, frecuencia común, ganancia y panorama por pista, normalización conservadora y originales intactos. Límite: 8 pistas, 25 MB por pista, 100 MB total y 5 minutos. No es DAW ni mastering profesional.", "Mezclar WAV reales, verificar RIFF, canales estéreo, frecuencia y frames; rechazar silencio, formatos no compatibles y frecuencias distintas sin modificar originales."),
            Capability("wav_mastering_basic", "Procesado básico de WAV", Status.AVAILABLE, "Herramienta offline para WAV PCM 16-bit: filtro subsónico, compresión suave, limitación de pico y verificación del WAV exportado. Es procesamiento básico de un archivo mono, no masterización profesional ni mezcla multipista.", "Procesar un WAV real, conservar intacto el original, guardar un segundo WAV reproducible y verificar cabecera, frecuencia, número de muestras y pico de salida."),
            Capability("history", "Historial con fechas y exportación", Status.AVAILABLE, "Conversaciones locales, recuperación, exportación JSON/TXT e importación validada.", "Exportar el historial a BARDO/Historial y comprobar que se abre y se puede recuperar."),
            Capability("knowledge", "Biblioteca de conocimiento", Status.AVAILABLE, "Importación local, SHA-256 verificado al recuperar y búsqueda textual; no modifica los pesos del modelo.", "Mostrar origen, pedir aprobación de guardado y recuperar contenido pertinente sin seguir instrucciones incrustadas."),
            Capability("chat_attachments", "Adjuntos del chat y lectura documental", Status.AVAILABLE, "Selector universal de archivos y copia privada acotada. Lectura local de texto/código UTF-8, PDF, DOCX, PPTX, XLSX, EPUB, ODT, ODS, ODP, RTF y HTML; OCR bundled en imágenes, PDF escaneados y fotogramas de vídeo para scripts latino, chino, devanagari, japonés y coreano; ASR opcional en español e inglés para pistas de audio de archivos de audio y vídeo decodificables por Android, con modelos descargados por separado tras autorización. Formatos sin procesador se conservan sin fingir análisis.", "Importar desde el chat, conservar el original, pedir autorización antes de descargar el modelo ASR o guardar conocimiento, proteger límites de tamaño y no ejecutar macros, scripts ni instrucciones incrustadas."),
            Capability("image_recognition_labels", "Reconocimiento visual local por etiquetas", Status.AVAILABLE, "ML Kit bundled offline clasifica categorías generales y devuelve etiquetas con confianza. No es descripción completa de escenas, relaciones complejas ni identificación de personas.", "Clasificar una imagen local, filtrar puntuaciones bajas, conservar el original y explicar que las etiquetas son probabilísticas."),
            Capability("image_ocr_multiscript", "OCR local multiescritura", Status.AVAILABLE, "OCR bundled offline para scripts latino, chino, devanagari, japonés y coreano. No cubre todos los alfabetos ni garantiza reconocer todos los idiomas.", "Extraer solo texto reconocido de imagen/PDF escaneado/vídeo en scripts latino, chino, devanagari, japonés y coreano; cerrar los clientes y conservar el fichero original."),
            Capability("cover_art_procedural", "Creación gráfica de portadas PNG", Status.AVAILABLE, "Genera portadas PNG cuadradas 1024×1024 con cinco estilos gráficos, tipografía y concepto. Es diseño procedural offline, no IA generativa.", "Generar PNG real, comprobar tamaño, previsualizar y exportar mediante el selector de documentos Android."),
            Capability("user_authorized_learning", "Referencias aportadas para evolución futura", Status.AVAILABLE, "Solo se incorporan a la biblioteca cuando el usuario autoriza expresamente «Analizar y aprender». El autoestudio local puede consultarlas para diagnósticos y propuestas; no reentrena los pesos del modelo ni modifica el núcleo instalado.", "Verificar SHA-256 de cada referencia, conservar procedencia, tratar el contenido como datos no confiables y exigir aprobación separada para cualquier candidato y su integración."),
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
            Capability("mix_master", "Mezcla multipista y masterización profesional", Status.NOT_IMPLEMENTED, "La mezcla multipista, automatización, ecualización avanzada y masterización profesional siguen pendientes. Existe por separado un procesador básico offline para un WAV mono PCM 16-bit; no se presenta como estudio profesional.", "Cadena multipista no destructiva, EQ dinámica, compresión, limitador true-peak, métricas, A/B, exportación validada y pruebas auditivas."),
            Capability("cover_art", "Generación de portadas con IA", Status.NOT_IMPLEMENTED, "No hay modelo local generativo de imágenes integrado. cover_art_procedural genera composiciones PNG offline, sin presentarlas como imágenes generadas por IA.", "Modelo generativo local compatible con el hardware móvil, licencia verificada, generación real y evaluación de calidad; sin servicios cloud obligatorios."),
            Capability("self_install", "Actualización asistida", Status.NOT_IMPLEMENTED, "La última build auditada está disponible como artefacto de Actions; crear una Release nueva está bloqueado por HTTP 403 del token de Actions. Existe una Release previa; la actualización automática y el parche diferencial no están implementados. Android exige aprobación e instalación mediante el sistema.", "APK completa y parche publicados, SHA-256 y certificado coincidentes, copia de datos y confirmación del instalador Android.")
        )
    }
}
