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

/**
 * El origen forma parte de la evidencia; un booleano "passed" sin una referencia
 * auditable nunca se considera un check superado.
 */
enum class BardoEvidenceOrigin {
    LOCAL_AUDIT,
    GITHUB_ACTIONS,
    DEVICE_TEST
}

data class BardoCheckEvidence internal constructor(
    val check: BardoCheck,
    val passed: Boolean,
    val detail: String,
    val evidenceRef: String,
    val origin: BardoEvidenceOrigin
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
 *
 * Clasifica únicamente evidencias construidas por productores internos. El
 * constructor de BardoCheckEvidence es internal y cada check exige un origen
 * y una referencia con formato específico. Esta validación de procedencia no
 * sustituye la verificación de la fuente: los adaptadores de CI/dispositivo
 * todavía deben resolver y comprobar la referencia antes de construirla.
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

    private val githubRunPattern =
        Regex("^https://github\\.com/loboxfake/musica-ia/actions/runs/[0-9]{6,20}$")
    private val localAuditPattern =
        Regex("^bardo-audit://[A-Za-z0-9._/-]{3,200}$")
    private val deviceRunPattern =
        Regex("^bardo-device://[A-Za-z0-9._/-]{3,200}$")

    fun evaluate(evidence: List<BardoCheckEvidence>): BardoEvaluationReport {
        require(evidence.map { it.check }.distinct().size == evidence.size) {
            "La evaluación contiene checks duplicados."
        }
        val byCheck = evidence.associateBy { it.check }
        val invalidProvenance = evidence.filterNot(::hasValidProvenance)
            .map { it.check }
            .toSet()
        val passed = evidence.filter { it.passed && it.check !in invalidProvenance }
            .map { it.check }
            .toSet()
        val failed = evidence.filter { !it.passed || it.check in invalidProvenance }
            .map { it.check }
            .toSet()
        val missing = (requiredCi + requiredDevice) - byCheck.keys

        val failedCi = failed intersect requiredCi
        val missingCi = missing intersect requiredCi
        val ciComplete = failedCi.isEmpty() && missingCi.isEmpty()
        val deviceComplete = ciComplete &&
            (requiredDevice - passed).isEmpty()

        val status = when {
            failed.isNotEmpty() -> BardoEvaluationStatus.REJECTED
            !ciComplete -> BardoEvaluationStatus.INCOMPLETE
            deviceComplete -> BardoEvaluationStatus.DEVICE_VERIFIED
            else -> BardoEvaluationStatus.READY_FOR_HUMAN_REVIEW
        }
        val summary = when (status) {
            BardoEvaluationStatus.REJECTED ->
                "La candidatura se rechaza: hay un control obligatorio fallido o evidencia sin procedencia válida."
            BardoEvaluationStatus.INCOMPLETE ->
                "Falta evidencia obligatoria de CI. No se puede aprobar la candidatura."
            BardoEvaluationStatus.READY_FOR_HUMAN_REVIEW ->
                "CI completa; requiere revisión humana. No equivale a prueba en dispositivo."
            BardoEvaluationStatus.DEVICE_VERIFIED ->
                "Los controles de CI y las pruebas físicas aportadas tienen referencias de procedencia válidas."
        }
        return BardoEvaluationReport(status, passed, failed, missing, summary)
    }

    private fun hasValidProvenance(evidence: BardoCheckEvidence): Boolean {
        if (evidence.detail.isBlank() || evidence.detail.length > 2_000) return false
        if (evidence.evidenceRef.isBlank() || evidence.evidenceRef.length > 1_000 ||
            evidence.evidenceRef.any { it.isWhitespace() }
        ) return false

        return when (evidence.check) {
            BardoCheck.SOURCE_BASE_CURRENT,
            BardoCheck.PATCH_INTEGRITY,
            BardoCheck.PROTECTED_PATHS,
            BardoCheck.MODO3_HASH_UNCHANGED,
            BardoCheck.APK_AUDIT ->
                evidence.origin == BardoEvidenceOrigin.LOCAL_AUDIT &&
                    localAuditPattern.matches(evidence.evidenceRef)

            BardoCheck.GITLEAKS,
            BardoCheck.ACTIONLINT,
            BardoCheck.UNIT_TESTS,
            BardoCheck.DEBUG_BUILD ->
                evidence.origin == BardoEvidenceOrigin.GITHUB_ACTIONS &&
                    githubRunPattern.matches(evidence.evidenceRef)

            BardoCheck.DEVICE_INSTALL,
            BardoCheck.GEMMA_INFERENCE,
            BardoCheck.OFFLINE_OPERATION,
            BardoCheck.WAV_GENERATION,
            BardoCheck.WAV_LISTENED,
            BardoCheck.USER_VOICE_MIX ->
                evidence.origin == BardoEvidenceOrigin.DEVICE_TEST &&
                    deviceRunPattern.matches(evidence.evidenceRef)
        }
    }

    fun mandatoryCiChecks(): Set<BardoCheck> = requiredCi.toSet()
    fun mandatoryDeviceChecks(): Set<BardoCheck> = requiredDevice.toSet()
}
