package com.musicaia.modelo

/** Registro declarativo de capacidades y sus requisitos reales. */
object BardoCapabilityRegistry {
    enum class Status { AVAILABLE, NEEDS_MODEL, NEEDS_FULL_APK, REQUIRES_USER_ACTION, NOT_IMPLEMENTED }

    data class Capability(
        val id: String,
        val name: String,
        val status: Status,
        val detail: String
    )

    data class RuntimeFacts(
        val gemmaFilePresent: Boolean,
        val audioEnginePresent: Boolean,
        val mp3EncoderPresent: Boolean
    )

    fun snapshot(facts: RuntimeFacts): List<Capability> = listOf(
        Capability(
            "general_chat", "Conversación general local",
            if (facts.gemmaFilePresent) Status.AVAILABLE else Status.NEEDS_MODEL,
            if (facts.gemmaFilePresent) "Gemma está presente; se verifica su SHA-256 y compatibilidad al cargar." else "Necesita descargar Gemma local (aprox. 2,6 GB)."
        ),
        Capability(
            "lyrics", "Generación de letras",
            if (facts.gemmaFilePresent) Status.AVAILABLE else Status.NEEDS_MODEL,
            if (facts.gemmaFilePresent) "Generación local condicionada a que Gemma pueda ejecutarse con la RAM disponible." else "Necesita Gemma local."
        ),
        Capability(
            "instrumental", "Generación instrumental",
            if (facts.audioEnginePresent) Status.AVAILABLE else Status.NEEDS_FULL_APK,
            if (facts.audioEnginePresent) "Motor nativo local incluido; la generación real requiere superar las comprobaciones de memoria y modelo." else "Necesita la APK offline completa con ExecuTorch y Stable Audio."
        ),
        Capability(
            "mp3_export", "Exportación MP3",
            if (facts.mp3EncoderPresent) Status.AVAILABLE else Status.NEEDS_FULL_APK,
            if (facts.mp3EncoderPresent) "Codificador local disponible." else "Codificador nativo no incluido en esta compilación."
        ),
        Capability("history", "Historial con fechas y exportación", Status.AVAILABLE, "Conversaciones locales, recuperación, JSON y TXT."),
        Capability("knowledge", "Biblioteca de conocimiento", Status.AVAILABLE, "Importación local, hash y recuperación textual; no modifica los pesos del modelo."),
        Capability("research", "Investigación web autorizada", Status.REQUIRES_USER_ACTION, "Solo tras pulsar investigar; HTTPS y dominios de documentación permitidos."),
        Capability("code_audit", "Auditoría de código", Status.AVAILABLE, "Análisis estático de un snapshot; no demuestra que el proyecto compile."),
        Capability("patch_sandbox", "Propuestas de parches aislados", Status.AVAILABLE, "Solo crea una copia candidata; no modifica la aplicación instalada."),
        Capability("voice_synthesis", "Generación de voz cantada", Status.NOT_IMPLEMENTED, "Todavía no está integrada."),
        Capability("self_install", "Autoactualización silenciosa", Status.NOT_IMPLEMENTED, "Android exige aprobación e instalación mediante el sistema.")
    )
}
