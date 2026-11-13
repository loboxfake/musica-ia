package com.musicaia.desarrollo

/**
 * Private context that may be used only for on-device study of user-imported projects.
 * These values must never be copied into a prompt whose model output may be sent to GitHub.
 */
data class BardoLocalOnlyModelContext(
    val deviceInventory: String,
    val authorizedReferences: String,
    val previousTechnicalMemory: String,
    val latestCiDiagnostics: String
)

/**
 * Fully resolved model context and the provenance decision attached to it.
 * A submission-capable context contains source-code evidence only and never reads
 * the local-only provider. Imported-project study may receive private context but
 * is permanently ineligible for private-repository CI submission.
 */
data class BardoCodeGenerationPrivacyContext(
    val inventoryContext: String,
    val authorizedReferences: String,
    val previousTechnicalMemory: String,
    val latestCiDiagnostics: String,
    val githubSubmissionEligible: Boolean
)

/**
 * Single fail-closed boundary between local learning and GitHub-bound proposals.
 * The private context supplier is lazy so no user references, diagnostics, memory
 * or device metrics are even loaded for a submission-capable BARDO-source prompt.
 */
object BardoCandidatePromptPrivacyPolicy {
    private const val REDACTED_CONTEXT =
        "Contexto privado omitido deliberadamente. La propuesta se basa únicamente " +
            "en el snapshot de código propio de BARDO y su auditoría estática. " +
            "No se incluyen dispositivo, métricas, memoria, referencias, documentos, " +
            "proyectos, logs ni diagnósticos locales."

    fun create(
        snapshotOrigin: String?,
        localOnlyContext: () -> BardoLocalOnlyModelContext
    ): BardoCodeGenerationPrivacyContext {
        if (BardoSnapshotOriginPolicy.allowsPrivateRepositoryCi(snapshotOrigin)) {
            return BardoCodeGenerationPrivacyContext(
                inventoryContext = REDACTED_CONTEXT,
                authorizedReferences = "",
                previousTechnicalMemory = "",
                latestCiDiagnostics = "",
                githubSubmissionEligible = true
            )
        }

        val privateContext = localOnlyContext()
        return BardoCodeGenerationPrivacyContext(
            inventoryContext = privateContext.deviceInventory,
            authorizedReferences = privateContext.authorizedReferences,
            previousTechnicalMemory = privateContext.previousTechnicalMemory,
            latestCiDiagnostics = privateContext.latestCiDiagnostics,
            githubSubmissionEligible = false
        )
    }
}
