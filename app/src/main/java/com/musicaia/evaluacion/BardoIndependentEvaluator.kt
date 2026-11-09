package com.musicaia.evaluacion

enum class BardoCheck {
    SOURCE_BASE_CURRENT,
    PATCH_INTEGRITY,
    PROTECTED_PATHS,
    MODO3_HASH_UNCHANGED,
    GITLEAKS,
    ACTIONLINT,
    UNIT_TESTS,
    DEBUG_BUILD,
    APK_AUDIT,
    DEVICE_INSTALL,
    GEMMA_INFERENCE,
    OFFLINE_OPERATION,
    WAV_GENERATION,
    WAV_LISTENED,
    USER_VOICE_MIX
}

data class BardoCheckEvidence(
    val check: BardoCheck,
    val passed: Boolean,
    val detail: String,
    val evidenceRef: String? = null
)

enum class BardoEvaluationStatus {
    INCOMPLETE,
    REJECTED,
    READY_FOR_HUMAN_REVIEW,
    DEVICE_VERIFIED
}

data class BardoEvaluationReport(
    val status: BardoEvaluationStatus,
    val passedChecks: Set<BardoCheck>,
    val failedChecks: Set<BardoCheck>,
    val missingChecks: Set<BardoCheck>,
    val summary: String
)

/**
 * Evaluador determinista independiente del modelo que propone un parche.
 * No acepta una puntuación subjetiva del modelo como evidencia de éxito.
 */
object BardoIndependentEvaluator {
    private val requiredCi = setOf(
        BardoCheck.SOURCE_BASE_CURRENT,
        BardoCheck.PATCH_INTEGRITY,
        BardoCheck.PROTECTED_PATHS,
        BardoCheck.MODO3_HASH_UNCHANGED,
        BardoCheck.GITLEAKS,
        BardoCheck.ACTIONLINT,
        BardoCheck.UNIT_TESTS,
        BardoCheck.DEBUG_BUILD
    )

    private val requiredDevice = setOf(
        BardoCheck.APK_AUDIT,
        BardoCheck.DEVICE_INSTALL,
        BardoCheck.GEMMA_INFERENCE,
        BardoCheck.OFFLINE_OPERATION,
        BardoCheck.WAV_GENERATION,
        BardoCheck.WAV_LISTENED,
        BardoCheck.USER_VOICE_MIX
    )

    fun evaluate(evidence: List<BardoCheckEvidence>): BardoEvaluationReport {
        require(evidence.map { it.check }.distinct().size == evidence.size) {
            "La evaluación contiene checks duplicados."
        }
        val byCheck = evidence.associateBy { it.check }
        val passed = evidence.filter { it.passed }.map { it.check }.toSet()
        val failed = evidence.filterNot { it.passed }.map { it.check }.toSet()
        val missing = (requiredCi + requiredDevice) - byCheck.keys

        val failedCi = failed intersect requiredCi
        val missingCi = missing intersect requiredCi
        val ciComplete = failedCi.isEmpty() && missingCi.isEmpty()
        val deviceComplete = ciComplete &&
            (requiredDevice - passed).isEmpty()

        val status = when {
            failedCi.isNotEmpty() -> BardoEvaluationStatus.REJECTED
            !ciComplete -> BardoEvaluationStatus.INCOMPLETE
            deviceComplete -> BardoEvaluationStatus.DEVICE_VERIFIED
            else -> BardoEvaluationStatus.READY_FOR_HUMAN_REVIEW
        }
        val summary = when (status) {
            BardoEvaluationStatus.REJECTED ->
                "La candidatura se rechaza: al menos un control obligatorio de CI ha fallado."
            BardoEvaluationStatus.INCOMPLETE ->
                "Falta evidencia obligatoria de CI. No se puede aprobar la candidatura."
            BardoEvaluationStatus.READY_FOR_HUMAN_REVIEW ->
                "CI completa; requiere revisión humana. No equivale a prueba en dispositivo."
            BardoEvaluationStatus.DEVICE_VERIFIED ->
                "Los controles de CI y todas las pruebas físicas declaradas han pasado."
        }
        return BardoEvaluationReport(status, passed, failed, missing, summary)
    }

    fun mandatoryCiChecks(): Set<BardoCheck> = requiredCi.toSet()
    fun mandatoryDeviceChecks(): Set<BardoCheck> = requiredDevice.toSet()
}
