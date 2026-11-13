package com.musicaia.modelo

/** Registro declarativo de capacidades y sus requisitos reales. */
object BardoCapabilityRegistry {
    enum class Status { AVAILABLE, INTEGRATED, MODEL_PRESENT, VERIFIED, LOADABLE, INFERENCE_PASSED, DEVICE_VERIFIED, NEEDS_MODEL, NEEDS_MEMORY, NEEDS_RESOURCES, NEEDS_FULL_APK, REQUIRES_USER_ACTION, NOT_IMPLEMENTED }

    // Se mantiene falso hasta integrar y probar un motor local real de voz cantada.
    const val VOICE_SYNTHESIS_AVAILABLE = false
    const val MIN_GEMMA_FREE_MEMORY_BYTES = 4_000L * 1024L * 1024L
    const val MIN_QWEN_FREE_MEMORY_BYTES = 1_600L * 1024L * 1024L
    const val MIN_IMAGE_GENERATION_FREE_MEMORY_BYTES = 3_500L * 1024L * 1024L

    fun manualLyricsCanProceed(
        instrumentalOnly: Boolean,
        manualLyrics: String,
        userVoiceAvailable: Boolean = false
    ): Boolean =
        instrumentalOnly || manualLyrics.isBlank() || userVoiceAvailable || VOICE_SYNTHESIS_AVAILABLE

    fun manualLyricsBlockReason(): String =
        "La voz cantada generada por IA todavía no está integrada. «Mi letra» sí puede combinarse con una grabación propia WAV PCM16; sin una voz propia válida, BARDO no sustituirá el canto por un instrumental. Elige «solo instrumental» o aporta tu grabación."

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
        val speechEnglishModelPresent: Boolean = false,
        val imageModelPresent: Boolean = false,
        val imageResourceBlockReason: String? = null,
        val installedModelCount: Int = 0,
        val availableHelperCount: Int = 0,
        val registeredModuleCount: Int = 0,
        val qwenFilePresent: Boolean = false,
        val qwenResourceBlockReason: String? = null,
        val gemmaEvidenceStatus: Status = Status.MODEL_PRESENT,
        val gemmaEvidenceDetail: String? = null,
        val qwenEvidenceStatus: Status = Status.MODEL_PRESENT,
        val qwenEvidenceDetail: String? = null
    )

    private fun normalizeEvidenceStatus(status: Status): Status = when (status) {
        Status.VERIFIED, Status.LOADABLE, Status.INFERENCE_PASSED, Status.DEVICE_VERIFIED -> status
        else -> Status.MODEL_PRESENT
    }

    fun canAttemptWithRuntimeChecks(status: Status): Boolean = status in setOf(
        Status.AVAILABLE, Status.MODEL_PRESENT, Status.VERIFIED, Status.LOADABLE,
        Status.INFERENCE_PASSED, Status.DEVICE_VERIFIED
    )

    private fun gemmaStatus(facts: RuntimeFacts): Status = when {
        !facts.gemmaFilePresent -> Status.NEEDS_MODEL
        facts.availableMemoryBytes < MIN_GEMMA_FREE_MEMORY_BYTES -> Status.NEEDS_MEMORY
        !facts.resourceBlockReason.isNullOrBlank() -> Status.NEEDS_RESOURCES
        else -> normalizeEvidenceStatus(facts.gemmaEvidenceStatus)
    }

    private fun qwenStatus(facts: RuntimeFacts): Status = when {
        !facts.qwenFilePresent -> Status.NEEDS_MODEL
        facts.availableMemoryBytes < MIN_QWEN_FREE_MEMORY_BYTES -> Status.NEEDS_MEMORY
        !facts.qwenResourceBlockReason.isNullOrBlank() -> Status.NEEDS_RESOURCES
        else -> normalizeEvidenceStatus(facts.qwenEvidenceStatus)
    }

    private fun localTextStatus(facts: RuntimeFacts): Status {
        val gemma = gemmaStatus(facts)
        if (canAttemptWithRuntimeChecks(gemma)) return gemma
        val qwen = qwenStatus(facts)
        if (canAttemptWithRuntimeChecks(qwen)) return qwen
        return if (facts.qwenFilePresent) qwen else gemma
    }

    private fun localTextDetail(facts: RuntimeFacts): String {
        val gemma = gemmaStatus(facts)
        return if (canAttemptWithRuntimeChecks(gemma) || !facts.qwenFilePresent) {
            facts.gemmaEvidenceDetail ?: gemmaDetail(facts)
        } else {
            facts.qwenEvidenceDetail ?: when (qwenStatus(facts)) {
                Status.NEEDS_MEMORY -> "Qwen2 está presente, pero hay menos de 1,6 GiB de RAM disponible."
                Status.NEEDS_RESOURCES -> facts.qwenResourceBlockReason.orEmpty()
                Status.MODEL_PRESENT -> "Qwen2-0.5B está presente por tamaño; hash, carga e inferencia se registran por separado."
                else -> "Qwen2: " + qwenStatus(facts).name + "."
            }
        }
    }

    private fun gemmaDetail(facts: RuntimeFacts): String = when (gemmaStatus(facts)) {
        Status.NEEDS_MODEL -> "Necesita importar Gemma local desde Descargas/BARDO/Modelos (aprox. 2,6 GB). Si ya guardaste allí el archivo, no necesitas descargar otra copia."
        Status.NEEDS_MEMORY -> "Gemma está presente, pero Android informa menos de 4 GB de RAM disponible. Cierra otras aplicaciones y vuelve a intentarlo. La comprobación es preventiva, no una garantía de compatibilidad."
        Status.NEEDS_RESOURCES -> facts.resourceBlockReason.orEmpty()
        else -> "Gemma está presente y hay memoria disponible suficiente según el umbral preventivo. Se verifica su SHA-256 y compatibilidad al cargar."
    }

    private fun imageGenerationStatus(facts: RuntimeFacts): Status = when {
        !facts.imageModelPresent -> Status.NEEDS_MODEL
        facts.availableMemoryBytes < MIN_IMAGE_GENERATION_FREE_MEMORY_BYTES -> Status.NEEDS_MEMORY
        !facts.imageResourceBlockReason.isNullOrBlank() -> Status.NEEDS_RESOURCES
        else -> Status.MODEL_PRESENT
    }

    private fun imageGenerationDetail(facts: RuntimeFacts): String = when (imageGenerationStatus(facts)) {
        Status.NEEDS_MODEL -> "Bonsai Image 4B LiteRT opcional: descarga explícita de unos 4,27 GB; requiere 4,8 GB libres. En 8 GB de RAM es el mínimo práctico y puede ser lento/calentar el teléfono."
        Status.NEEDS_MEMORY -> "Bonsai está instalado, pero hay menos de 3,5 GiB de RAM libre. Cierra aplicaciones y motores de audio/Gemma antes de generar."
        Status.NEEDS_RESOURCES -> facts.imageResourceBlockReason.orEmpty()
        else -> "Bonsai Image 4B está instalado; generación 512×512 local con LiteRT/XNNPACK, sin nube. La compatibilidad térmica y de velocidad debe comprobarse en el Xiaomi."
    }

    fun snapshot(facts: RuntimeFacts): List<Capability> {
        val localModelStatus = gemmaStatus(facts)
        val localModelDetail = gemmaDetail(facts)
        val localTextModelStatus = localTextStatus(facts)
        val localTextModelDetail = localTextDetail(facts)
        return listOf(
            Capability("general_chat", "Conversación general local", localTextModelStatus, localTextModelDetail, "Gemma o Qwen2 local verificado por SHA-256; conversación real sin Internet en el Xiaomi, sin cierres ni respuestas simuladas."),
            Capability(
                "lyrics", "Generación de letras",
                localTextModelStatus,
                localTextModelDetail,
                "Generar texto local coherente con los datos; no usar plantillas fijas. La síntesis vocal cantada es una capacidad separada y todavía no está implementada."
            ),
            Capability(
                "instrumental", "Generación instrumental",
                if (facts.audioEnginePresent) Status.MODEL_PRESENT else Status.NEEDS_FULL_APK,
                if (facts.audioEnginePresent) "Artefactos del modelo y biblioteca JNI presentes. La generación real y el WAV deben validarse antes de elevar la evidencia." else "Necesita la APK offline completa con ExecuTorch y Stable Audio.",
                "Prueba rápida en modo avión; luego 18 segmentos, diagnóstico WAV, duración final mínima de 180 s y escucha sin huecos artificiales."
            ),
            Capability(
                "mp3_export", "Exportación MP3",
                if (facts.mp3EncoderPresent) Status.INTEGRATED else Status.NEEDS_FULL_APK,
                if (facts.mp3EncoderPresent) "Codificador local disponible." else "Codificador nativo no incluido en esta compilación.",
                "Guardar MP3 reproducible en Descargas/BARDO y compartirlo con una acción independiente."
            ),
            Capability("speech_transcription_en", "Transcripción de audio offline en inglés",
                if (facts.speechEnglishModelPresent) Status.MODEL_PRESENT else Status.NEEDS_MODEL,
                if (facts.speechEnglishModelPresent) "Modelo Vosk small-en-us instalado y validado por estructura. Decodificación local Android; no se envía audio a la nube."
                else "Necesita descargar una vez el modelo inglés Vosk (41.205.931 bytes); se verifica SHA-256 antes de instalar. Después funciona sin conexión.",
                "Transcribir habla inglesa real decodificable por Android, sin modificar el original ni inventar texto."),
            Capability("speech_transcription_es", "Transcripción de audio offline en español", if (facts.speechModelPresent) Status.MODEL_PRESENT else Status.NEEDS_MODEL, if (facts.speechModelPresent) "Modelo Vosk español instalado y validado por estructura. La decodificación usa códecs locales de Android; no se envía audio a la nube." else "Necesita descargar una vez el modelo español Vosk (39.817.833 bytes); SHA-256 fijado y comprobado antes de instalar. Después funciona sin conexión.", "Transcribir audio real decodificable por Android, en segundo plano, sin modificar el original; no inventar texto cuando no se detecta voz."),
            Capability("mix_wav_basic", "Mezcla básica de 2–8 pistas WAV", Status.INTEGRATED, "Mezclador estéreo offline PCM16, frecuencia común, ganancia y panorama por pista, normalización conservadora y originales intactos. Límite: 8 pistas, 25 MB por pista, 100 MB total y 5 minutos. No es DAW ni mastering profesional.", "Mezclar WAV reales, verificar RIFF, canales estéreo, frecuencia y frames; rechazar silencio, formatos no compatibles y frecuencias distintas sin modificar originales."),
            Capability("voice_mix_user", "Mezcla real WAV con voz del usuario", Status.INTEGRATED, "El flujo activo de MainActivity combina la grabación/archivo de voz con el instrumental mediante MezclaVoz.mezclar y LectorWav (PCM16). Modo3MezclaVozUsuario.kt se conserva como adaptador legacy, no como ruta activa. La escucha real en Xiaomi sigue pendiente.", "Seleccionar voz WAV PCM16 e instrumental WAV reales; generar un WAV de salida válido, reproducirlo y escucharlo, comprobar muestras/duración y conservar los originales."),
            Capability("stem_separation", "Separación de stems", Status.NOT_IMPLEMENTED, "No hay todavía un separador local de stems integrado y probado. No se presenta como disponible ni se descarga un modelo sin autorización.", "Separar voz, batería, bajo y acompañamiento de un audio real; exportar stems WAV sincronizados, medir calidad y conservar el original."),
            Capability("wav_mastering_basic", "Procesado básico de WAV", Status.INTEGRATED, "Herramienta offline para WAV PCM 16-bit: filtro subsónico, compresión suave, limitación de pico y verificación del WAV exportado. Es procesamiento básico de un archivo mono, no masterización profesional ni mezcla multipista.", "Procesar un WAV real, conservar intacto el original, guardar un segundo WAV reproducible y verificar cabecera, frecuencia, número de muestras y pico de salida."),
            Capability("self_inventory", "Inventario interno y autoinspección de BARDO", Status.INTEGRATED, "BARDO inspecciona solo su propia instalación: " + facts.registeredModuleCount + " módulos registrados, " + facts.installedModelCount + " modelos/motores presentes y " + facts.availableHelperCount + " ayudantes internos con código integrado. El catálogo de habilidades se obtiene del registro propio de capacidades; no consulta paquetes ni aplicaciones externas.", "Comprobar que cada modelo se distingue entre incluido en APK, descargado, verificado, cargable y probado; listar módulos y ayudantes reales; refrescar el inventario tras instalar o retirar modelos; no marcar como funcional lo que solo está registrado en código."),
            Capability("history", "Historial con fechas y exportación", Status.INTEGRATED, "Conversaciones locales, recuperación, exportación JSON/TXT e importación validada.", "Exportar el historial a BARDO/Historial y comprobar que se abre y se puede recuperar."),
            Capability("knowledge", "Biblioteca de conocimiento", Status.INTEGRATED, "Importación local, SHA-256 verificado al recuperar y búsqueda textual; no modifica los pesos del modelo.", "Mostrar origen, pedir aprobación de guardado y recuperar contenido pertinente sin seguir instrucciones incrustadas."),
            Capability("chat_attachments", "Adjuntos del chat y lectura documental", Status.INTEGRATED, "Selector universal de archivos y copia privada acotada. Lectura local de texto/código UTF-8, PDF, DOCX, PPTX, XLSX, EPUB, ODT, ODS, ODP, RTF y HTML; OCR bundled en imágenes, PDF escaneados y fotogramas de vídeo para scripts latino, chino, devanagari, japonés y coreano; ASR opcional en español e inglés para pistas de audio de archivos de audio y vídeo decodificables por Android, con modelos descargados por separado tras autorización. Formatos sin procesador se conservan sin fingir análisis.", "Importar desde el chat, conservar el original, pedir autorización antes de descargar el modelo ASR o guardar conocimiento, proteger límites de tamaño y no ejecutar macros, scripts ni instrucciones incrustadas."),
            Capability("image_recognition_labels", "Reconocimiento visual local por etiquetas", Status.INTEGRATED, "ML Kit bundled offline clasifica categorías generales y devuelve etiquetas con confianza. No es descripción completa de escenas, relaciones complejas ni identificación de personas.", "Clasificar una imagen local, filtrar puntuaciones bajas, conservar el original y explicar que las etiquetas son probabilísticas."),
            Capability("image_ocr_multiscript", "OCR local multiescritura", Status.INTEGRATED, "OCR bundled offline para scripts latino, chino, devanagari, japonés y coreano. No cubre todos los alfabetos ni garantiza reconocer todos los idiomas.", "Extraer solo texto reconocido de imagen/PDF escaneado/vídeo en scripts latino, chino, devanagari, japonés y coreano; cerrar los clientes y conservar el fichero original."),
            Capability("cover_art_procedural", "Creación gráfica de portadas PNG", Status.INTEGRATED, "Genera portadas PNG cuadradas 1024×1024 con cinco estilos gráficos, tipografía y concepto. Es diseño procedural offline, no IA generativa.", "Generar PNG real, comprobar tamaño, previsualizar y exportar mediante el selector de documentos Android."),
            Capability("user_authorized_learning", "Referencias aportadas para evolución futura", Status.INTEGRATED, "Solo se incorporan a la biblioteca cuando el usuario autoriza expresamente «Analizar y aprender». El autoestudio local puede consultarlas para diagnósticos y propuestas; las respuestas de modelos también se guardan en un diario separado como candidatas no verificadas. No se reentrenan los pesos del modelo ni se promueve una respuesta a conocimiento fiable sin un verificador independiente conectado.", "Verificar SHA-256 de cada referencia, conservar procedencia, tratar el contenido como datos no confiables y exigir evidencia independiente para promover cualquier candidato."),
            Capability("research", "Investigación web autorizada", Status.REQUIRES_USER_ACTION, "La investigación web HTTPS con citas aún no está integrada. Actualmente BARDO busca en la biblioteca local importada; el acceso de red permanece deshabilitado.", "Requiere autorización explícita y cliente HTTPS implementado; citar fuentes fechadas y no afirmar que se ha investigado en la web cuando solo se consultó la biblioteca local."),
            Capability(
                "autonomous_loop", "Bucle autónomo local",
                localModelStatus,
                if (canAttemptWithRuntimeChecks(localModelStatus))
                    "Planificar→actuar→observar→evaluar→corregir está conectado al autoestudio en segundo plano. Solo inventario BARDO, biblioteca importada y auditoría estática; no ejecuta código ni accede a la red."
                else localModelDetail,
                "Completar una tarea local con pasos, límites, corrección medida y diario privado; bloquear herramientas fuera de los scopes autorizados."
            ),
            Capability(
                "model_provider_registry", "Proveedores de modelos sustituibles",
                Status.INTEGRATED,
                "Contrato común y registro local-first integrados; Gemma/LiteRT es el primer adaptador. BardoAgentFleet ya permite registrar proveedores y perfiles especializados por capacidad; el flujo de autoestudio solo conecta Gemma por ahora.",
                "Cambiar de proveedor sin cambiar el núcleo; verificar licencia, hash, recursos, versión, inferencia y modo avión."
            ),
            Capability(
                "agent_fleet", "Flota multi-modelo y agentes especializados",
                Status.REQUIRES_USER_ACTION,
                "BardoAgentFleet selecciona por capacidad, conserva proveedor/versión y consulta modelos secuencialmente; el autoestudio actual solo registra un agente Gemma local. No hay todavía una flota de proveedores adicionales operativa y los modelos remotos se rechazan por defecto.",
                "Registrar varios adaptadores/modelos con licencias, SHA, capacidades y límites; asignar tareas a perfiles distintos; demostrar contraste de resultados, uso secuencial por RAM y política de red autorizada."
            ),
            Capability(
                "model_learning_journal", "Memoria de aprendizaje desde otros modelos",
                Status.REQUIRES_USER_ACTION,
                "BardoLearningJournal conserva respuestas de agentes con SHA-256 y procedencia. El verificador local solo promueve extractos literales que coinciden byte a byte con el snapshot oficial de BARDO; las interpretaciones y respuestas sin evidencia permanecen como candidatas. No se modifican pesos del modelo.",
                "Promover una lección solo después de corroboración independiente, evidencia trazable y resultado reproducible; conservar también discrepancias y fallos."
            ),
            Capability(
                "skill_lifecycle", "Gestor de skills declarativas",
                Status.REQUIRES_USER_ACTION,
                "API local para cuarentena, SHA-256, instalación deshabilitada, activación aprobada, deshabilitación y rollback. BardoCoreSkillSmokeTestRunner ejecuta pruebas escritas en el APK sobre las acciones registradas; BardoSkillToolHandler permite al planificador invocar solo acciones declaradas en una skill habilitada. El worker integra el runtime. Actualmente solo hay cuatro acciones locales de lectura/estudio registradas; UI de catálogo, importación guiada y descubrimiento/descarga siguen pendientes.",
                "Importar un paquete a cuarentena, verificar hash/licencia, instalar deshabilitado, ejecutar smoke-tests escritos en el APK con evidencia, exigir aprobación explícita y permisos mínimos; ejecutar solo acciones tipadas declaradas y registradas, pasando cada acción por ToolExecutor."
            ),
            Capability(
                "jarvis_authority", "Autoridad delegada User/JARVIS",
                Status.REQUIRES_USER_ACTION,
                "Verificación ECDSA, scopes, caducidad, nonce y ledger de revocación disponibles; falta emparejar las claves y conectar el transporte seguro PC↔Android.",
                "Aceptar solo órdenes firmadas y delegadas por el usuario; permitir revocación y prioridad de la instrucción directa del usuario."
            ),
            Capability(
                "independent_evaluator", "Evaluador independiente de evidencias",
                Status.REQUIRES_USER_ACTION,
                "Evaluador determinista de CI, seguridad, APK y pruebas de dispositivo integrado como componente; conexión automática a todos los resultados de Actions y al verificador de aprendizaje pendiente.",
                "No aprobar por puntuación del modelo; exigir evidencia para cada control y prueba física antes de declarar device_verified."
            ),
            Capability(
                "autonomous_developer",
                "Autoestudio local y propuesta de correcciones",
                gemmaStatus(facts),
                when (gemmaStatus(facts)) {
                    Status.NEEDS_MODEL -> "Necesita Gemma local verificado para estudiar el snapshot del proyecto."
                    Status.NEEDS_MEMORY -> "Gemma está presente, pero falta RAM libre para realizar autoestudio con seguridad."
                    Status.NEEDS_RESOURCES -> facts.resourceBlockReason.orEmpty()
                    else -> "BARDO puede analizar estáticamente un snapshot mediante la flota (Gemma es el único proveedor conectado actualmente), registrar la respuesta como candidato de aprendizaje no verificado y proponer un diff en una copia aislada. No ejecuta código importado ni modifica el original; validación CI y merge requieren el flujo autorizado."
                },
                "Ejecutar autoestudio con Gemma verificado; conservar snapshot original; validar diff y rutas protegidas; no ejecutar código importado; compilar y probar candidato en runner aislado; exigir aprobación explícita antes de integrar."
            ),
            Capability("code_audit", "Auditoría de código", Status.INTEGRATED, "Análisis estático de un snapshot; no demuestra que el proyecto compile.", "Informe reproducible de archivos y hallazgos; diferenciar análisis estático de compilación y pruebas."),
            Capability("patch_sandbox", "Propuestas de parches aislados", Status.INTEGRATED, "Solo crea una copia candidata; no modifica la aplicación instalada.", "Diff aislado, pruebas previas, SHA de protecciones intactos, auditoría y reversión demostrable."),
            Capability(
                "project_build",
                "Validación de proyectos en runner aislado",
                if (facts.githubActionsConfigured) Status.INTEGRATED else Status.REQUIRES_USER_ACTION,
                if (facts.githubActionsConfigured) {
                    "BARDO puede enviar diffs permitidos a un runner efímero de GitHub Actions, ejecutar tests y assembleDebug y consultar el resultado. El teléfono no ejecuta Gradle."
                } else {
                    "Requiere conectar un token fine-grained de GitHub limitado a este repositorio (Actions: Read and write; Contents: Read). La compilación se ejecuta en runner remoto, no en Android."
                },
                "Enviar un candidato basado en el SHA exacto de main, ejecutar testDebugUnitTest y assembleDebug en runner sin secretos, recuperar conclusión y pasos; rechazar si falla o el commit base está obsoleto."
            ),
            Capability(
                "github_development",
                "Propuestas autónomas mediante ramas y PR",
                if (facts.githubActionsConfigured) Status.INTEGRATED else Status.REQUIRES_USER_ACTION,
                if (facts.githubActionsConfigured) {
                    "La conexión GitHub está configurada; BARDO valida el candidato y puede crear una rama bardo/* y PR borrador tras una aprobación explícita. No escribe ni fusiona main. La protección de rama debe comprobarse en GitHub."
                } else {
                    "Requiere configurar la conexión GitHub. No se incrustan credenciales en la APK ni se realizan pushes directos a main."
                },
                "Validar el SHA base exacto, ejecutar tests y compilación en runner sin secretos, exigir aprobación explícita para crear un PR borrador y no fusionar ni publicar APK automáticamente."
            ),
            Capability("voice_engine_runtime", "Runtime local ONNX para DiffSinger", Status.INTEGRATED, "ONNX Runtime Android está incluido. BARDO inspecciona paquetes DiffSinger y prueba la apertura del grafo acústico; además incorpora códec, decodificador autoregresivo, runtime CPU G2P español y mapeo al inventario de la voz. No hay pesos G2P con licencia verificada ni inferencia real de G2P/acústico/vocoder validada; este código no genera canto.", "Cargar una voicebank y un G2P autorizados; validar inventarios y contratos de tensores; ejecutar G2P, predictor de duración, acústico y vocoder en ARM64; exportar y escuchar un WAV real."),
            Capability("voice_synthesis", "Generación de voz cantada", Status.NOT_IMPLEMENTED, "Motor en desarrollo: existen inspección DiffSinger, códec/decodificador G2P y runtime CPU para un ONNX aportado localmente, pero no se incluye un modelo G2P con licencia verificada ni se ha validado su inferencia real. También faltan predictor de duración, renderizador acústico, vocoder, ensamblado WAV y sincronización completa. No genera canto todavía.", "Modelo G2P y voicebank legítimos con derechos y consentimiento; inferencia G2P, duraciones, acústico y vocoder; WAV reproducible, sincronización de fonemas/notas y pruebas auditivas en Xiaomi 11T Pro."),
            Capability("mix_master", "Mezcla multipista y masterización profesional", Status.NOT_IMPLEMENTED, "La mezcla multipista, automatización, ecualización avanzada y masterización profesional siguen pendientes. Existe por separado un procesador básico offline para un WAV mono PCM 16-bit; no se presenta como estudio profesional.", "Cadena multipista no destructiva, EQ dinámica, compresión, limitador true-peak, métricas, A/B, exportación validada y pruebas auditivas."),
            Capability("cover_art", "Generación de portadas con IA local", imageGenerationStatus(facts), imageGenerationDetail(facts), "Bonsai Image 4B descargado y verificado, imagen PNG 512×512 real sin nube, guardado y previsualización; medir RAM/temperatura/tiempo en Xiaomi 11T Pro."),
            Capability("self_install", "Actualización asistida", Status.NOT_IMPLEMENTED, "La publicación manual de APK firmada y auditada mediante GitHub Release ya funciona. La aplicación todavía no descarga ni instala actualizaciones por sí sola ni aplica parches diferenciales; Android exige confirmación del usuario mediante el instalador del sistema.", "Publicar APK completa y parche con SHA-256 y certificado coincidentes; verificar versión instalada, conservar datos y exigir confirmación del instalador Android.")
        )
    }
}
